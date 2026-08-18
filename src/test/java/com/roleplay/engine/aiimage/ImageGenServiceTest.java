package com.roleplay.engine.aiimage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL）：ImageGenService 验收（mock ComfyUI 客户端）。
 *
 * <p>① 角色注册表：注册/更新/查询/初始角色装载
 * ② 生成任务：头像 1 + 表情 6 + 全身立绘 1（8 张），异步线程池执行，任务状态 IDLE→RUNNING→DONE
 * ③ 参数替换：正向含 score tag + rating_safe + 外貌 + 风格 + 表情 + 构图；
 *    负向含 nsfw 拦截；头像=portrait 构图、表情=bust 构图、全身立绘=fullbody 构图；seed 同角色一致
 * ④ URL 生成：/ai-images/{id}/{frame}.png；重启后磁盘扫描仍可见
 * ⑤ 未知角色 / 重复提交（RUNNING 中）防护
 */
class ImageGenServiceTest {

    /** 假 ComfyUI 客户端：不发起真实 HTTP，直接落盘假字节并记录 WorkflowSpec（含 img2img 调用记录）。 */
    static class FakeComfyClient extends ComfyUIClient {
        final List<WorkflowSpec> specs = new CopyOnWriteArrayList<>();
        /** 调用类型序列（txt2img=generateOnce / img2img=generateImg2Img，按调用顺序）。 */
        final List<String> callKinds = new CopyOnWriteArrayList<>();
        /** img2img 参考图路径（按调用顺序；txt2img 调用位为 null）。 */
        final List<String> refImages = new CopyOnWriteArrayList<>();
        /** img2img denoise（按调用顺序；txt2img 调用位为 -1）。 */
        final List<Double> denoises = new CopyOnWriteArrayList<>();
        /** 第几号调用（1 起）抛 IOException（仅该次调用失败，其余正常）；0=不失败。 */
        volatile int failAtCall;

        FakeComfyClient() {
            super(new ObjectMapper(), "http://127.0.0.1:1", 5, 50);
        }

        @Override
        public List<String> generateOnce(WorkflowSpec spec, Path outputDir, String fileName) throws IOException {
            recordCall("txt2img", spec, null, -1.0);
            writeFake(outputDir, fileName);
            return List.of(fileName);
        }

        @Override
        public List<String> generateImg2Img(WorkflowSpec spec, Path refImagePath, double denoise,
                                            Path outputDir, String fileName) throws IOException {
            recordCall("img2img", spec, refImagePath, denoise);
            writeFake(outputDir, fileName);
            return List.of(fileName);
        }

        private void recordCall(String kind, WorkflowSpec spec, Path ref, double denoise) throws IOException {
            specs.add(spec);
            callKinds.add(kind);
            refImages.add(ref == null ? null : ref.toString());
            denoises.add(denoise);
            if (failAtCall > 0 && callKinds.size() == failAtCall) {
                throw new IOException("模拟失败: call " + callKinds.size());
            }
        }

        private void writeFake(Path outputDir, String fileName) throws IOException {
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve(fileName), new byte[]{1, 2, 3});
        }
    }

    private ImageGenService newService(FakeComfyClient client, Path outputDir) {
        AiImageProperties props = new AiImageProperties();
        props.setOutputDir(outputDir.toString());
        props.setLoraName("pixel_art_sakuemonq_pony.safetensors");
        props.setRmbgEnabled(false); // 既有用例走纯生成基线；RMBG 接线由 S-5/S-6 专门覆盖
        return new ImageGenService(client, props);
    }

    /** 等任务到终态（DONE/FAILED），默认 10s 上限。 */
    private ImageGenService.GenTask awaitDone(ImageGenService svc, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ImageGenService.GenTask t = null;
        while (System.currentTimeMillis() < deadline) {
            t = svc.taskOf(id);
            if (t != null && t.status() != ImageGenService.GenTask.Status.RUNNING) return t;
            Thread.sleep(20);
        }
        return t;
    }

    @Test
    @DisplayName("S-1 角色注册/更新/查询 + 初始角色装载")
    void registerCharacter() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-reg");
        FakeComfyClient client = new FakeComfyClient();
        AiImageProperties props = new AiImageProperties();
        props.setOutputDir(dir.toString());
        AiImageProperties.CharacterSeed seed = new AiImageProperties.CharacterSeed();
        seed.setId("preset1");
        seed.setName("预设角色");
        seed.setAppearance("黑色短发，红色眼睛");
        seed.setStyle("anime style");
        props.setCharacters(List.of(seed));
        ImageGenService svc = new ImageGenService(client, props);

        // 初始角色已装载
        assertNotNull(svc.getCharacter("preset1"));
        assertEquals("预设角色", svc.getCharacter("preset1").name());
        // 注册新角色
        ImageGenService.CharacterProfile p = svc.registerCharacter("heroine", "小铃", "银色长发，紫色眼眸", "anime style");
        assertEquals("heroine", p.id());
        assertEquals("小铃", svc.getCharacter("heroine").name());
        // 更新已存在角色
        svc.registerCharacter("heroine", "小铃改", "金发", "pixel art");
        assertEquals("小铃改", svc.getCharacter("heroine").name());
        assertEquals("pixel art", svc.getCharacter("heroine").style());
        assertEquals(2, svc.allCharacters().size());
        svc.shutdown();
    }

    @Test
    @DisplayName("S-2 生成任务：头像 1 + 表情 6 + 全身立绘 1，URL 生成正确，seed 同角色一致")
    void generateAvatarAndExpressions() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-gen");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc = newService(client, dir);
        svc.registerCharacter("heroine", "小铃", "银色长发，紫色眼眸，白色和服", "anime style, cel shading");

        ImageGenService.GenTask task = svc.triggerGenerate("heroine");
        assertNotNull(task);
        assertEquals(ImageGenService.GenTask.Status.RUNNING, task.status());
        task = awaitDone(svc, "heroine");
        assertNotNull(task, "任务应在 10s 内完成");
        assertEquals(ImageGenService.GenTask.Status.DONE, task.status(), "error=" + task.error());

        // 8 次出图调用：avatar + 6 表情 + fullbody 全身立绘
        assertEquals(8, client.specs.size(), "应生成 1 头像 + 6 表情 + 1 全身立绘共 8 张");

        // URL 生成
        Map<String, String> images = svc.imagesOf("heroine");
        assertEquals(8, images.size());
        assertEquals("/ai-images/heroine/avatar.png", images.get("avatar"));
        assertEquals("/ai-images/heroine/happy.png", images.get("happy"));
        assertEquals("/ai-images/heroine/neutral.png", images.get("neutral"));
        assertEquals("/ai-images/heroine/fullbody.png", images.get("fullbody"));
        assertEquals(ImageGenService.EXPRESSIONS.size(), 6);

        // 参数替换：所有正向都含 score tag + rating_safe + 外貌 + 风格；负向含 nsfw
        for (WorkflowSpec s : client.specs) {
            assertTrue(s.positivePrompt().contains("score_9"), s.positivePrompt());
            assertTrue(s.positivePrompt().contains("score_8_up"), s.positivePrompt());
            assertTrue(s.positivePrompt().contains("score_7_up"), s.positivePrompt());
            assertTrue(s.positivePrompt().contains("rating_safe"), s.positivePrompt());
            assertTrue(s.positivePrompt().contains("银色长发，紫色眼眸，白色和服"), s.positivePrompt());
            assertTrue(s.positivePrompt().contains("anime style, cel shading"), s.positivePrompt());
            assertTrue(s.negativePrompt().contains("nsfw"), "非 NSFW 防线");
            assertTrue(s.negativePrompt().contains("nude"));
            assertTrue(s.negativePrompt().contains("worst quality"));
            assertEquals("pixel_art_sakuemonq_pony.safetensors", s.loraName());
        }
        // 头像 = portrait 构图（1024x1024 + 构图词）；表情 = bust 构图；全身立绘 = fullbody 构图（832x1216）
        WorkflowSpec avatar = client.specs.get(0);
        assertTrue(avatar.positivePrompt().contains("head and shoulders portrait"), avatar.positivePrompt());
        assertEquals(1024, avatar.width());
        assertEquals(1024, avatar.height());
        for (int i = 1; i <= 6; i++) {
            WorkflowSpec s = client.specs.get(i);
            assertTrue(s.positivePrompt().contains("bust shot"), s.positivePrompt());
            assertEquals(1024, s.width());
            assertEquals(1024, s.height());
        }
        WorkflowSpec fullbody = client.specs.get(7);
        assertTrue(fullbody.positivePrompt().contains("full body shot"), fullbody.positivePrompt());
        assertTrue(fullbody.positivePrompt().contains("standing"), fullbody.positivePrompt());
        assertTrue(fullbody.positivePrompt().contains("full outfit visible"), fullbody.positivePrompt());
        assertEquals(832, fullbody.width());
        assertEquals(1216, fullbody.height());
        // 表情描述按固定表注入
        assertTrue(client.specs.get(1).positivePrompt().contains("happy expression"));
        assertTrue(client.specs.get(2).positivePrompt().contains("angry expression"));
        assertTrue(client.specs.get(3).positivePrompt().contains("sad expression"));
        assertTrue(client.specs.get(4).positivePrompt().contains("surprised expression"));
        assertTrue(client.specs.get(5).positivePrompt().contains("blushing"));
        assertTrue(client.specs.get(6).positivePrompt().contains("neutral calm expression"));
        // seed 同角色一致（base + 帧序号，跨帧确定性；fullbody=base+7）
        long base = ImageGenService.stableSeed("heroine");
        for (int i = 0; i < client.specs.size(); i++) {
            assertEquals(base + i, client.specs.get(i).seed(), "第 " + i + " 帧 seed 应 = base + i");
        }
        svc.shutdown();
    }

    @Test
    @DisplayName("S-3 未知角色 / 重复提交防护 / 失败任务如实标记")
    void guardsAndFailure() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-guard");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc = newService(client, dir);

        // 未知角色
        assertNull(svc.triggerGenerate("nobody"));
        assertNull(svc.getCharacter("nobody"));
        // imagesResponse 空
        assertTrue(svc.imagesResponse("nobody").isEmpty());

        // 重复提交：RUNNING 中返回同一任务
        svc.registerCharacter("heroine", "小铃", "银发", "anime");
        ImageGenService.GenTask t1 = svc.triggerGenerate("heroine");
        ImageGenService.GenTask t2 = svc.triggerGenerate("heroine");
        assertSame(t1, t2, "RUNNING 中重复触发应返回同一任务");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status());
        assertEquals(8, client.specs.size());

        // 完成后再触发 → 新任务（重新生成）
        ImageGenService.GenTask t3 = svc.triggerGenerate("heroine");
        assertNotSame(done, t3);
        awaitDone(svc, "heroine");
        assertEquals(16, client.specs.size(), "二次生成应再出 8 张");

        // 失败任务如实标记
        FakeComfyClient failing = new FakeComfyClient() {
            @Override
            public List<String> generateOnce(WorkflowSpec spec, Path outputDir, String fileName) throws IOException {
                throw new IOException("ComfyUI 连接拒绝（模拟）");
            }
        };
        ImageGenService svc2 = newService(failing, Files.createTempDirectory("aiimg-fail"));
        svc2.registerCharacter("bad", "失败角色", "外貌", "风格");
        svc2.triggerGenerate("bad");
        ImageGenService.GenTask ft = awaitDone(svc2, "bad");
        assertNotNull(ft);
        assertEquals(ImageGenService.GenTask.Status.FAILED, ft.status());
        assertTrue(ft.error().contains("连接拒绝"), ft.error());
        svc.shutdown();
        svc2.shutdown();
    }

    @Test
    @DisplayName("S-4 磁盘持久化：重启（新实例同目录）后已生成图自动可见")
    void persistedAcrossRestart() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-persist");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc1 = newService(client, dir);
        svc1.registerCharacter("heroine", "小铃", "银发", "anime");
        svc1.triggerGenerate("heroine");
        awaitDone(svc1, "heroine");
        assertEquals(8, client.specs.size());
        svc1.shutdown();

        // 模拟重启：新实例指向同一输出目录（不重新生成）
        ImageGenService svc2 = newService(new FakeComfyClient(), dir);
        svc2.registerCharacter("heroine", "小铃", "银发", "anime");
        Map<String, String> images = svc2.imagesOf("heroine");
        assertEquals(8, images.size(), "重启后应扫描到磁盘已有图片");
        assertEquals("/ai-images/heroine/avatar.png", images.get("avatar"));
        assertEquals("/ai-images/heroine/fullbody.png", images.get("fullbody"));
        assertTrue(svc2.imagesResponse("heroine").containsKey("avatar"));
        assertEquals("/ai-images/heroine/fullbody.png", svc2.imagesResponse("heroine").get("fullbody"));
        assertEquals("/ai-images/heroine/happy.png",
                ((Map<?, ?>) svc2.imagesResponse("heroine").get("expressions")).get("happy"));
        svc2.shutdown();
    }

    @Test
    @DisplayName("S-5 P-0810-04 RMBG 接线：每张生成图自动产透明版 {frame}_t，imagesOf 含 _t 条目")
    void rmbgWiringGeneratesTransparentVariants() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-rmbg");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc = newService(client, dir);
        FakeRmbg fake = new FakeRmbg();
        svc.setRmbgRemover(fake);
        svc.setRmbgEnabled(true);
        svc.registerCharacter("heroine", "小铃", "银色长发", "anime");

        svc.triggerGenerate("heroine");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status(), "error=" + done.error());

        // 8 张原图各触发一次抠图
        assertEquals(8, fake.calls, "每张生成图应触发一次抠背景");
        // 磁盘上原图 + 透明版并存
        assertTrue(Files.exists(dir.resolve("heroine/avatar.png")));
        assertTrue(Files.exists(dir.resolve("heroine/avatar_t.png")));
        assertTrue(Files.exists(dir.resolve("heroine/happy_t.png")));
        assertTrue(Files.exists(dir.resolve("heroine/fullbody.png")));
        assertTrue(Files.exists(dir.resolve("heroine/fullbody_t.png")));

        // imagesOf 同时含原帧与 _t 条目（16 = 8 原帧 + 8 透明版）
        Map<String, String> images = svc.imagesOf("heroine");
        assertEquals(16, images.size());
        assertEquals("/ai-images/heroine/avatar.png", images.get("avatar"));
        assertEquals("/ai-images/heroine/avatar_t.png", images.get("avatar_t"));
        assertEquals("/ai-images/heroine/happy_t.png", images.get("happy_t"));
        assertEquals("/ai-images/heroine/neutral_t.png", images.get("neutral_t"));
        assertEquals("/ai-images/heroine/fullbody.png", images.get("fullbody"));
        assertEquals("/ai-images/heroine/fullbody_t.png", images.get("fullbody_t"));
        // characterStatus / imagesResponse 的 images 映射同样含 _t（前端立绘可优先用）
        Map<String, Object> status = svc.characterStatus("heroine");
        assertTrue(((Map<?, ?>) status.get("images")).containsKey("avatar_t"));
        assertTrue(((Map<?, ?>) status.get("images")).containsKey("fullbody_t"));
        assertTrue(((Map<?, ?>) svc.imagesResponse("heroine").get("images")).containsKey("happy_t"));
        assertTrue(((Map<?, ?>) svc.imagesResponse("heroine").get("images")).containsKey("fullbody_t"));
        // avatar/expressions 键仍指向原图（非透明版），契约不破坏；fullbody 顶层键存在
        assertEquals("/ai-images/heroine/avatar.png", svc.imagesResponse("heroine").get("avatar"));
        assertEquals("/ai-images/heroine/fullbody.png", svc.imagesResponse("heroine").get("fullbody"));
        assertEquals("/ai-images/heroine/fullbody_t.png", svc.imagesResponse("heroine").get("fullbody_t"));
        svc.shutdown();
    }

    @Test
    @DisplayName("S-6 P-0810-04 开关关闭：只存原图不产透明版；抠图失败不影响主流程")
    void rmbgDisabledOrFailureKeepsOriginal() throws Exception {
        // 开关关闭 → 无 _t
        Path dir = Files.createTempDirectory("aiimg-rmbg-off");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc = newService(client, dir);
        svc.registerCharacter("heroine", "小铃", "银色长发", "anime");
        svc.triggerGenerate("heroine");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status());
        assertEquals(8, client.specs.size());
        assertEquals(8, svc.imagesOf("heroine").size(), "关闭时不应有 _t 条目");
        assertFalse(Files.exists(dir.resolve("heroine/avatar_t.png")));
        assertFalse(Files.exists(dir.resolve("heroine/fullbody_t.png")));
        svc.shutdown();

        // 抠图失败（stub 抛异常）→ 任务仍 DONE，原图保留，主流程不受影响
        Path dir2 = Files.createTempDirectory("aiimg-rmbg-fail");
        ImageGenService svc2 = newService(new FakeComfyClient(), dir2);
        svc2.setRmbgRemover(new FakeRmbg(true)); // 抛异常版
        svc2.setRmbgEnabled(true);
        svc2.registerCharacter("heroine", "小铃", "银色长发", "anime");
        svc2.triggerGenerate("heroine");
        ImageGenService.GenTask done2 = awaitDone(svc2, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done2.status(), "抠图失败不应影响生成任务，error=" + done2.error());
        assertTrue(Files.exists(dir2.resolve("heroine/avatar.png")), "原图保留");
        assertFalse(Files.exists(dir2.resolve("heroine/avatar_t.png")));
        assertEquals(8, svc2.imagesOf("heroine").size());
        svc2.shutdown();
    }

    @Test
    @DisplayName("S-7 P-0810-05 生成顺序：avatar 文生图先行，6 表情 img2img 且底图=avatar.png、denoise 可配")
    void img2imgOrderAndBaseImage() throws Exception {
        Path dir = Files.createTempDirectory("aiimg-img2img");
        FakeComfyClient client = new FakeComfyClient();
        AiImageProperties props = new AiImageProperties();
        props.setOutputDir(dir.toString());
        props.setLoraName("pixel_art_sakuemonq_pony.safetensors");
        props.setRmbgEnabled(false);
        props.setImg2imgDenoise(0.45); // 可配置：非默认值验证透传
        ImageGenService svc = new ImageGenService(client, props);
        svc.registerCharacter("heroine", "小铃", "银色长发，紫色眼眸", "anime style");

        svc.triggerGenerate("heroine");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status(), "error=" + done.error());

        // 顺序：avatar（txt2img）→ 6 表情（img2img）→ fullbody（txt2img）
        assertEquals(8, client.callKinds.size());
        assertEquals("txt2img", client.callKinds.get(0), "avatar 必须是文生图（无底图）");
        for (int i = 1; i <= 6; i++) {
            assertEquals("img2img", client.callKinds.get(i), "第 " + i + " 帧应为 img2img");
        }
        assertEquals("txt2img", client.callKinds.get(7), "fullbody 应为文生图（与 avatar 同源，不用 img2img）");
        // 底图 = avatar.png 原图非透明版（绝对路径，同角色目录）
        Path avatar = dir.toAbsolutePath().resolve("heroine/avatar.png");
        for (int i = 1; i <= 6; i++) {
            assertEquals(avatar.toString(), client.refImages.get(i), "表情底图应为 avatar.png");
            assertEquals(0.45, client.denoises.get(i), 1e-9, "denoise 应取配置值");
        }
        // 8 帧 prompt/seed 结构保持（score tag + 表情描述 + bust 构图；seed=base+i，fullbody=base+7）
        long base = ImageGenService.stableSeed("heroine");
        for (int i = 0; i < 8; i++) {
            assertTrue(client.specs.get(i).positivePrompt().contains("rating_safe"));
            assertEquals(base + i, client.specs.get(i).seed());
        }
        assertTrue(client.specs.get(1).positivePrompt().contains("happy expression"));
        assertTrue(client.specs.get(1).positivePrompt().contains("bust shot"));
        assertTrue(client.specs.get(7).positivePrompt().contains("full body shot"));
        assertEquals(832, client.specs.get(7).width());
        assertEquals(1216, client.specs.get(7).height());
        // 进度事件顺序不变（avatar → happy → ... → neutral → fullbody → done）
        assertEquals("done", done.progress());
        svc.shutdown();
    }

    @Test
    @DisplayName("S-8 单帧失败跳过继续（任务仍 DONE）；avatar 失败则任务 FAILED")
    void img2imgSingleFrameFailureSkips() throws Exception {
        // ① 第 3 号调用（angry 表情）起失败 → 跳过该帧，其余 6 帧成功，任务 DONE
        Path dir = Files.createTempDirectory("aiimg-skip");
        FakeComfyClient client = new FakeComfyClient();
        client.failAtCall = 3;
        ImageGenService svc = newService(client, dir);
        svc.registerCharacter("heroine", "小铃", "银发", "anime");
        svc.triggerGenerate("heroine");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status(), "单帧失败不应拖垮整任务，error=" + done.error());
        assertEquals(8, client.callKinds.size(), "8 帧都应被尝试");
        assertEquals("txt2img", client.callKinds.get(0));
        assertEquals(6, client.callKinds.stream().filter("img2img"::equals).count());
        // 失败帧（angry）不出图，其余 7 帧出图（avatar/happy/sad/surprised/embarrassed/neutral/fullbody）
        Map<String, String> images = svc.imagesOf("heroine");
        assertEquals(7, images.size());
        assertNotNull(images.get("avatar"));
        assertNotNull(images.get("happy"));
        assertNull(images.get("angry"));
        assertNotNull(images.get("neutral"));
        assertNotNull(images.get("fullbody"), "表情帧失败不应影响 fullbody 全身立绘");
        svc.shutdown();

        // ② avatar 失败（第 1 号调用）→ 任务 FAILED（无底图后续 img2img 无意义，终态按现有逻辑）
        Path dir2 = Files.createTempDirectory("aiimg-avatarfail");
        FakeComfyClient client2 = new FakeComfyClient();
        client2.failAtCall = 1;
        ImageGenService svc2 = newService(client2, dir2);
        svc2.registerCharacter("heroine", "小铃", "银发", "anime");
        svc2.triggerGenerate("heroine");
        ImageGenService.GenTask failed = awaitDone(svc2, "heroine");
        assertEquals(ImageGenService.GenTask.Status.FAILED, failed.status());
        assertEquals(1, client2.callKinds.size(), "avatar 失败后不再尝试表情");
        assertTrue(failed.error().contains("模拟失败"), failed.error());
        svc2.shutdown();
    }

    /**
     * P-0811-G(C-2)：SSE 终态广播 —— 任务 DONE 推 ai_image_ready（含 characterId/url），
     * FAILED 推 ai_image_error（含 characterId/error）；未注入广播器零影响。
     */
    static class FakeBroadcaster implements com.roleplay.engine.broadcast.SseBroadcaster {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();

        @Override
        public void broadcast(String eventType, Object data) {
            events.add(eventType);
            payloads.add((Map<String, Object>) data);
        }
    }

    @Test
    @DisplayName("S-9 P-0811-G(C-2)：DONE→ai_image_ready / FAILED→ai_image_error；未注入不广播")
    void sseEventBroadcastOnTerminal() throws Exception {
        // ① 成功任务 → ai_image_ready（characterId + avatar url）
        Path dir = Files.createTempDirectory("aiimg-sse-ok");
        FakeComfyClient client = new FakeComfyClient();
        ImageGenService svc = newService(client, dir);
        FakeBroadcaster sse = new FakeBroadcaster();
        svc.setSseBroadcaster(sse);
        svc.registerCharacter("heroine", "小铃", "银发", "anime");
        svc.triggerGenerate("heroine");
        ImageGenService.GenTask done = awaitDone(svc, "heroine");
        assertEquals(ImageGenService.GenTask.Status.DONE, done.status());
        assertEquals(1, sse.events.size(), "成功任务应推 1 条事件");
        assertEquals("ai_image_ready", sse.events.get(0));
        assertEquals("heroine", sse.payloads.get(0).get("characterId"));
        assertNotNull(sse.payloads.get(0).get("url"), "ai_image_ready 应含 avatar url");
        svc.shutdown();

        // ② 失败任务 → ai_image_error（characterId + error）
        Path dir2 = Files.createTempDirectory("aiimg-sse-fail");
        FakeComfyClient failing = new FakeComfyClient() {
            @Override
            public List<String> generateOnce(WorkflowSpec spec, Path outputDir, String fileName) throws IOException {
                throw new IOException("ComfyUI 不可用（模拟）");
            }
        };
        ImageGenService svc2 = newService(failing, dir2);
        FakeBroadcaster sse2 = new FakeBroadcaster();
        svc2.setSseBroadcaster(sse2);
        svc2.registerCharacter("bad", "失败角色", "外貌", "风格");
        svc2.triggerGenerate("bad");
        ImageGenService.GenTask ft = awaitDone(svc2, "bad");
        assertEquals(ImageGenService.GenTask.Status.FAILED, ft.status());
        assertEquals(1, sse2.events.size());
        assertEquals("ai_image_error", sse2.events.get(0));
        assertEquals("bad", sse2.payloads.get(0).get("characterId"));
        assertTrue(String.valueOf(sse2.payloads.get(0).get("error")).contains("不可用"));
        svc2.shutdown();

        // ③ 未注入广播器 → 不抛异常、不广播（零影响）
        ImageGenService svc3 = newService(new FakeComfyClient(), Files.createTempDirectory("aiimg-sse-none"));
        svc3.registerCharacter("nobody", "无广播", "外貌", "风格");
        svc3.triggerGenerate("nobody");
        ImageGenService.GenTask t3 = awaitDone(svc3, "nobody");
        assertEquals(ImageGenService.GenTask.Status.DONE, t3.status());
        svc3.shutdown();
    }

    /** P-0810-04：假抠背景器（记录调用次数，可配置抛异常）。 */
    static class FakeRmbg extends RmbgRemover {
        int calls;
        final boolean fail;

        FakeRmbg() {
            this(false);
        }

        FakeRmbg(boolean fail) {
            super(""); // 假实现不触模型
            this.fail = fail;
        }

        @Override
        public boolean removeBackground(Path rgbPng, Path transparentPng) {
            calls++;
            if (fail) throw new RuntimeException("模拟抠图失败");
            try {
                Files.createDirectories(transparentPng.toAbsolutePath().getParent());
                Files.write(transparentPng, new byte[]{9, 9, 9});
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return true;
        }
    }
}
