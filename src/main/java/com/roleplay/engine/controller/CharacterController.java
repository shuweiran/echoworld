package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.service.GeneratorService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Character CRUD endpoints.
 * Maps from Python api/routes_characters.py.
 *
 * <p>Storage: in-memory list mirrors H2 (DatabaseService). Startup loads persisted
 * characters from H2; every write (create/update/delete/batch) is persisted
 * synchronously so data survives restarts.
 *
 * <p>P-0811-D：生成角色自动落库 + 五层卡外部目录持久化 + 旧角色批量升级（详见 {@link #generate} /
 * {@link #upgrade} / {@link #persistCardToDisk}）。五层卡写入外部目录
 * {@code roleplay.game.persona-cards-dir}（默认 {@code ./data/persona}，{角色名}.json，UTF-8 无 BOM），
 * 重启后由 {@link PersonaCardLoader} 扫描合并（classpath 默认卡 + 外部目录，外部优先）→ 卡不丢。
 */
@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private static final Logger log = LoggerFactory.getLogger(CharacterController.class);
    private static final ObjectMapper PERSONA_MAPPER = new ObjectMapper();

    private final List<Map<String, Object>> characters = new CopyOnWriteArrayList<>();
    /** P-0810-10：五层 persona 卡内存库（按角色名索引，POST /api/characters/{name}/persona 导入）。
     *  独立于角色表存储——绝不并入 characters map，任何对外 API 天然不透出五层内部设定。 */
    private final Map<String, Map<String, Object>> personaCards = new ConcurrentHashMap<>();
    private final GeneratorService generator;
    private final DatabaseService databaseService;

    /** P-0811-D：五层卡外部持久化目录（null=未配置/测试直构 → 落盘关闭，纯内存零破坏）。
     *  配置键 {@code roleplay.game.persona-cards-dir}（默认 ./data/persona），测试经 {@link #setPersonaCardsDir} 指临时目录。 */
    @Value("${roleplay.game.persona-cards-dir:./data/persona}")
    private String personaCardsDirConfig;
    private volatile Path personaCardsDir;

    /** P-0811-D：批量升级进度（内存 Map，GET /api/characters/upgrade/status 查询）。 */
    private final Map<String, Object> upgradeStatus = new ConcurrentHashMap<>();

    public CharacterController(GeneratorService generator, DatabaseService databaseService) {
        this.generator = generator;
        this.databaseService = databaseService;
    }

    /** P-0811-D：测试/运行时注入外部卡目录（null/空 → 落盘关闭）。 */
    public void setPersonaCardsDir(String dir) {
        this.personaCardsDir = (dir == null || dir.isBlank()) ? null : Path.of(dir);
    }

    @PostConstruct
    public void init() {
        // P-0811-D：配置值装载 + 外部卡目录注册到加载器（classpath + 外部合并，外部优先）
        setPersonaCardsDir(personaCardsDirConfig);
        if (personaCardsDir != null) {
            PersonaCardLoader.setExternalCardsDir(personaCardsDir);
        }
        // Load persisted characters from H2 (survives restarts)
        characters.addAll(databaseService.getAllCharacters());
        // Seed default only when DB is empty — avoids duplicates on restart
        if (characters.isEmpty()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", "助手");
            def.put("persona", "温柔体贴的助手");
            def.put("voice", "温和");
            def.put("background", "一直在你身边");
            characters.add(def);
            databaseService.saveCharacter("助手", "温柔体贴的助手", "温和", "一直在你身边");
        }
    }

    /**
     * P-0810-10：角色列表（含五层卡表层增强）。只附加 appearance/summary 两个表层字段
     * （来自 persona 卡 Layer 1），绝不附加 layer0/layer3/layer4 等内部设定。
     */
    public List<Map<String, Object>> getAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ch : characters) {
            Map<String, Object> m = new LinkedHashMap<>(ch);
            Map<String, Object> card = personaCards.get(String.valueOf(ch.get("name")));
            if (card != null) {
                if (card.get("appearance") != null) m.putIfAbsent("appearance", card.get("appearance"));
                if (card.get("summary") != null) m.putIfAbsent("summary", card.get("summary"));
            }
            out.add(m);
        }
        return out;
    }

    /** P-0810-10：取角色已导入的五层卡（无则 null）。 */
    public Map<String, Object> personaCardFor(String name) {
        return name == null ? null : personaCards.get(name);
    }

    /** P-0810-10：给 Persona 挂五层卡（优先导入卡，回退默认资源卡；已有 layer 不覆盖）。 */
    public void attachPersonaCard(Persona p) {
        if (p == null) return;
        PersonaCardLoader.attach(p, personaCards.get(p.getName()));
    }

    /**
     * P-0802-P3（改造方案 §4.2 角色库改名）：内存列表改名 + DB 删旧建新（playerId 绑定随新行保留）。
     * 由 PlayerIdentityService.renamePlayerCharacter 编排调用（调用方已完成撞名校验②）。
     * 返回更新后的角色 map；旧名不存在返回 null（调用方按 404 处理）。
     * 撞名兜底：DB unique 冲突（并发窗口）→ 回滚内存列表并抛 DataIntegrityViolationException（调用方回滚）。
     */
    public synchronized Map<String, Object> renameCharacterInMemory(String oldName, String newName) {
        for (int i = 0; i < characters.size(); i++) {
            if (oldName.equals(characters.get(i).get("name"))) {
                Map<String, Object> original = new LinkedHashMap<>(characters.get(i));
                Map<String, Object> updated = new LinkedHashMap<>(original);
                updated.put("name", newName);
                characters.set(i, updated);
                // P-0810-10：角色改名时五层卡跟随换键（旧名卡不存在则 no-op）
                // ⚠️ P-0810-09 解阻塞修正：原代码变量名笔误 name→oldName（name 不在作用域，编译失败）
                if (!oldName.equals(newName) && personaCards.containsKey(oldName)) {
                    personaCards.put(newName, personaCards.remove(oldName));
                    // P-0811-D：磁盘卡文件跟随改名（防旧名卡文件残留，重启后按新名加载）
                    renameCardFileOnDisk(oldName, newName);
                }
                // 改名：H2 name 是唯一键 → 先删旧行再存新行（playerId 绑定随新行保留）
                databaseService.deleteCharacter(oldName);
                try {
                    databaseService.saveCharacter(newName, str(updated.get("persona"), ""),
                            str(updated.get("voice"), ""), str(updated.get("background"), ""),
                            pid(updated.get("player_id")));
                } catch (DataIntegrityViolationException e) {
                    // 并发撞名兜底：回滚内存列表，交给调用方统一回滚
                    characters.set(i, original);
                    throw e;
                }
                return updated;
            }
        }
        return null;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(getAll());
    }

    /**
     * P-0810-10：五层 persona 卡导入（POST /api/characters/{name}/persona）。
     * body = 五层卡 JSON（见 docs/persona-五层卡-格式.md）：layer0~layer4 / contrast / humanDetails 至少一项。
     * 卡只存内存 personaCards 库（独立于角色表/H2），启动后需重新导入或用默认资源卡。
     * 响应只回表层字段（name/appearance/summary/layers 键名列表），绝不回 layer 内容（防透出内部设定）。
     */
    @PostMapping("/{name}/persona")
    public ResponseEntity<?> importPersonaCard(@PathVariable String name, @RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "persona 卡内容不能为空", "detail", "body 需为五层卡 JSON（见 docs/persona-五层卡-格式.md）"));
        }
        boolean hasLayer = PersonaCardLoader.LAYER_KEYS.stream().anyMatch(body::containsKey);
        if (!hasLayer) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "persona 卡缺少层数据",
                    "detail", "必须包含 layer0~layer4 / contrast / humanDetails 至少一个键"));
        }
        Map<String, Object> card = new LinkedHashMap<>(body);
        card.put("name", name); // 以路径名为准，防卡内 name 与路径不一致
        personaCards.put(name, card);
        // P-0811-D：导入卡写盘（外部目录持久化，重启不丢）
        persistCardToDisk(name, card);
        // 表层响应：只回 name/appearance/summary + 层键名列表，绝不回 layer 内容
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "ok");
        res.put("name", name);
        if (card.get("appearance") != null) res.put("appearance", card.get("appearance"));
        if (card.get("summary") != null) res.put("summary", card.get("summary"));
        res.put("layers", PersonaCardLoader.LAYER_KEYS.stream().filter(card::containsKey).toList());
        return ResponseEntity.ok(res);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String nm = str(body.get("name"), "未命名");
        // 撞名校验 ①（改造方案 §5 层 ①）：内存列表已有同名 → 409，不落库不覆盖
        if (hasName(nm)) {
            return conflict("角色名已存在: " + nm);
        }
        String pid = pid(body.get("player_id"));
        if (pid != null && hasPlayerId(pid)) {
            return conflict("该玩家已绑定角色");
        }
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", nm);
        ch.put("persona", str(body.get("persona"), ""));
        ch.put("voice", str(body.get("voice"), ""));
        ch.put("background", str(body.get("background"), ""));
        ch.put("player_id", pid);
        characters.add(ch);
        try {
            databaseService.saveCharacter(nm, (String) ch.get("persona"),
                    (String) ch.get("voice"), (String) ch.get("background"), pid);
        } catch (DataIntegrityViolationException e) {
            // DB unique 兜底（③层，并发窗口）：player_id/name 被并发占用 → 回滚内存列表
            characters.removeIf(c -> nm.equals(c.get("name")));
            return conflict("该玩家已绑定角色");
        }
        return ResponseEntity.ok(ch);
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody Map<String, Object> body) {
        for (int i = 0; i < characters.size(); i++) {
            if (name.equals(characters.get(i).get("name"))) {
                Map<String, Object> original = new LinkedHashMap<>(characters.get(i));
                Map<String, Object> updated = new LinkedHashMap<>(original);
                body.forEach((k, v) -> { if (v != null) updated.put(k, v); });
                String newName = str(updated.get("name"), "未命名");
                // 撞名校验 ①：改名撞名（排除自身）→ 409，不覆盖同名角色 persona
                if (!newName.equals(name) && hasName(newName)) {
                    return conflict("角色名已存在: " + newName);
                }
                // playerId 绑定校验：换绑到已被其他角色占用的玩家 → 409（update 保留既有绑定）
                String pid = pid(updated.get("player_id"));
                if (pid != null && !pid.equals(original.get("player_id")) && hasPlayerId(pid)) {
                    return conflict("该玩家已绑定角色");
                }
                characters.set(i, updated);
                if (!name.equals(newName)) {
                    // Rename: name is the unique key in H2 → drop old row first
                    databaseService.deleteCharacter(name);
                }
                try {
                    databaseService.saveCharacter(newName, str(updated.get("persona"), ""),
                            str(updated.get("voice"), ""), str(updated.get("background"), ""), pid);
                } catch (DataIntegrityViolationException e) {
                    // DB unique 兜底：回滚内存列表（旧行已删则按 original 恢复）
                    characters.set(i, original);
                    if (!name.equals(newName)) {
                        characters.removeIf(c -> newName.equals(c.get("name")));
                    }
                    return conflict("该玩家已绑定角色");
                }
                return ResponseEntity.ok(updated);
            }
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        characters.removeIf(c -> name.equals(c.get("name")));
        databaseService.deleteCharacter(name);
        // P-0811-D：删除角色时清理磁盘卡文件（防重启后旧卡文件残留复活）
        deleteCardFileOnDisk(name);
        return ResponseEntity.ok().build();
    }

    /**
     * P-0811-B：AI 生成角色（五层 persona 拟合 + 场景上下文注入）。
     *
     * <p>body 可选 keywords / scene_name / scene_description（场景上下文透传给 generator，
     * prompt 注入「当前场景」要求角色与场景契合）。
     *
     * <p>P-0811-D（自动落库）：生成成功后自动保存——name/persona/voice/background 落 H2
     * （复用 {@link DatabaseService#saveCharacter(String, String, String, String)}，对齐 create 端点逻辑）
     * + 同步内存列表（getAll/列表可见）；结果含五层键 → 挂 personaCards + 写盘持久化
     * （见 {@link #persistCardToDisk}）。撞名（库内已有同 name）→ 409 复用 {@link #conflict}，
     * 不落库不挂卡。响应补 saved=true（已有返回结构上增量，不破坏既有消费）。
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) body = Map.of();
        String keywords = body.getOrDefault("keywords", "");
        String sceneName = body.get("scene_name");
        String sceneDescription = body.get("scene_description");
        Map<String, Object> result = generator.generateCharacter(keywords, sceneName, sceneDescription);

        String name = str(result.get("name"), "路人");
        // P-0811-D：撞名校验——库内已有同 name → 409，不落库不挂卡（对齐 create 端点撞名语义）
        if (hasName(name)) {
            return conflict("角色名已存在: " + name);
        }
        // P-0811-D：自动落库（对齐 create 端点逻辑：内存列表 + H2 四字段）
        String persona = str(result.get("persona"), "");
        String voice = str(result.get("voice"), "");
        String background = str(result.get("background"), "");
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", name);
        ch.put("persona", persona);
        ch.put("voice", voice);
        ch.put("background", background);
        ch.put("player_id", null);
        characters.add(ch);
        try {
            databaseService.saveCharacter(name, persona, voice, background);
        } catch (DataIntegrityViolationException e) {
            // DB unique 兜底（并发窗口）：回滚内存列表
            characters.removeIf(c -> name.equals(c.get("name")));
            return conflict("角色名已存在: " + name);
        }

        // 五层卡挂载 + 写盘（格式与 POST /{name}/persona 导入卡一致，键名对齐契约）
        boolean hasLayer = PersonaCardLoader.LAYER_KEYS.stream().anyMatch(result::containsKey);
        if (hasLayer) {
            Map<String, Object> card = buildCardFromResult(name, result);
            personaCards.put(name, card);
            persistCardToDisk(name, card);
        }

        // 表层响应：saved=true + name + appearance/summary + layers 键名列表，绝不回 layer 内容
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("saved", true);
        res.put("name", name);
        if (result.get("appearance") != null) res.put("appearance", result.get("appearance"));
        if (result.get("summary") != null) res.put("summary", result.get("summary"));
        if (hasLayer) {
            res.put("layers", PersonaCardLoader.LAYER_KEYS.stream().filter(result::containsKey).toList());
        }
        return ResponseEntity.ok(res);
    }

    /**
     * P-0811-E（追加）：场景配套角色批量自动落库（供 SceneController.generate 逐角色调用）。
     *
     * <p>与 {@link #generate} 的单角色自动落库同逻辑（内存列表 + H2 四字段 + 五层卡挂载 + 写盘），
     * 差异：<b>撞名自动加序号后缀</b>（「名字_2」「名字_3」…最多 10 次）而非 409 中断整批；
     * 全部尝试均冲突/落库异常 → log.warn 返回 null（调用方跳过落库但保留角色在响应里）。
     *
     * @return 表层响应 map（{saved:true, name:最终落库名, appearance?, summary?, layers?}，绝不回五层内容）；
     *         落库失败返回 null
     */
    public Map<String, Object> persistGeneratedRole(Map<String, Object> roleResult) {
        String baseName = str(roleResult.get("name"), "场景角色");
        String persona = str(roleResult.get("persona"), "");
        String voice = str(roleResult.get("voice"), "");
        String background = str(roleResult.get("background"), "");
        Map<String, Object> persisted = null;
        String finalName = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = attempt == 0 ? baseName : baseName + "_" + (attempt + 1);
            if (hasName(candidate)) continue; // 内存撞名 → 下一序号
            Map<String, Object> trial = new LinkedHashMap<>();
            trial.put("name", candidate);
            trial.put("persona", persona);
            trial.put("voice", voice);
            trial.put("background", background);
            trial.put("player_id", null);
            characters.add(trial);
            try {
                databaseService.saveCharacter(candidate, persona, voice, background);
                persisted = trial;
                finalName = candidate;
                break;
            } catch (DataIntegrityViolationException e) {
                // DB unique 兜底（并发窗口）：回滚内存列表 → 下一序号
                characters.removeIf(c -> candidate.equals(c.get("name")));
            }
        }
        if (persisted == null) {
            log.warn("CharacterController: 场景配套角色「{}」落库失败（10 次尝试均冲突），跳过落库", baseName);
            return null;
        }
        // 五层卡挂载 + 写盘（与 generate 端点同机制，键名对齐契约）
        boolean hasLayer = PersonaCardLoader.LAYER_KEYS.stream().anyMatch(roleResult::containsKey);
        if (hasLayer) {
            Map<String, Object> card = buildCardFromResult(finalName, roleResult);
            personaCards.put(finalName, card);
            persistCardToDisk(finalName, card);
        }
        // 表层响应（对齐 generate 端点形状）：saved + name（最终落库名）+ appearance/summary + layers 键名列表
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("saved", true);
        res.put("name", finalName);
        if (roleResult.get("appearance") != null) res.put("appearance", roleResult.get("appearance"));
        if (roleResult.get("summary") != null) res.put("summary", roleResult.get("summary"));
        if (hasLayer) {
            res.put("layers", PersonaCardLoader.LAYER_KEYS.stream().filter(roleResult::containsKey).toList());
        }
        return res;
    }

    /**
     * P-0811-D：旧角色批量升级（POST /api/characters/upgrade）。
     *
     * <p>异步（虚拟线程）遍历角色表所有<b>无五层卡</b>角色 → 对每个调
     * {@link GeneratorService#generateCharacterForUpgrade}（以角色现有 persona/voice/background
     * 拼接为上下文关键词，保持身份一致；scene 上下文不传）→ 生成成功：五层卡写盘 + personaCards 挂载
     * + <b>表层替换</b>（personaDesc/voice/background/appearance/summary 用生成结果覆盖——升级路径是
     * 显式「替换」动作，与 P-0810-10「不覆盖用户显式内容」的常规挂载规则区分，仅升级路径覆盖）
     * + H2 更新表层 4 字段（{@link DatabaseService#saveCharacter}）。
     *
     * <p>幂等：有卡的跳过；单角色失败跳过继续（log.warn），汇总 {upgraded, skipped, failed, names[]}
     * 经 GET /api/characters/upgrade/status 查询。body 可选 {@code max_roles} 限制升级数（缺省全部；
     * ≤0 视同全部）。响应立即返回 {started:true}。
     */
    @PostMapping("/upgrade")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody(required = false) Map<String, Object> body) {
        if (Boolean.TRUE.equals(upgradeStatus.get("running"))) {
            return ResponseEntity.ok(Map.of("started", false, "message", "已有升级任务运行中"));
        }
        int maxRoles = 0;
        if (body != null && body.get("max_roles") instanceof Number n) {
            maxRoles = n.intValue();
        }
        upgradeStatus.clear();
        upgradeStatus.put("running", true);
        upgradeStatus.put("startedAt", LocalDateTime.now().toString());
        // 虚拟线程异步执行，响应立即返回（不阻塞主线程）
        final int roles = maxRoles;
        Thread.startVirtualThread(() -> runUpgrade(roles));
        return ResponseEntity.ok(Map.of("started", true));
    }

    /** P-0811-D：升级进度查询（内存 Map 拷贝，无升级时返回空态）。 */
    @GetMapping("/upgrade/status")
    public ResponseEntity<Map<String, Object>> upgradeStatus() {
        return ResponseEntity.ok(new LinkedHashMap<>(upgradeStatus));
    }

    /** P-0811-D：升级主循环（虚拟线程内执行）。 */
    private void runUpgrade(int maxRoles) {
        int upgraded = 0;
        int skipped = 0;
        int failed = 0;
        List<String> names = new ArrayList<>();
        try {
            List<Map<String, Object>> snapshot = new ArrayList<>(characters);
            for (Map<String, Object> ch : snapshot) {
                if (maxRoles > 0 && upgraded >= maxRoles) break; // max_roles 限制升级数
                String name = str(ch.get("name"), "");
                if (name.isEmpty()) continue;
                if (hasCardForUpgrade(name)) { // 幂等：有卡跳过
                    skipped++;
                    continue;
                }
                try {
                    Map<String, Object> result = generator.generateCharacterForUpgrade(
                            name, str(ch.get("persona"), ""),
                            str(ch.get("voice"), ""), str(ch.get("background"), ""));
                    applyUpgradeResult(name, result);
                    upgraded++;
                    names.add(name);
                } catch (Exception e) {
                    failed++; // 单角色失败跳过继续
                    log.warn("CharacterController: 升级角色「{}」失败（跳过）: {}", name, e.getMessage());
                }
            }
        } finally {
            // 先写汇总再置 running=false（ConcurrentHashMap put 的 happens-before 保证读到 running=false 时汇总可见）
            upgradeStatus.put("finishedAt", LocalDateTime.now().toString());
            upgradeStatus.put("upgraded", upgraded);
            upgradeStatus.put("skipped", skipped);
            upgradeStatus.put("failed", failed);
            upgradeStatus.put("names", names);
            upgradeStatus.put("running", false);
        }
    }

    /** 升级判卡：内存库已有（导入/生成/本次升级）或加载器已注册（classpath 默认卡 + 外部目录卡）→ 视为有卡。 */
    private boolean hasCardForUpgrade(String name) {
        return personaCards.containsKey(name) || PersonaCardLoader.hasCard(name);
    }

    /** P-0811-D：升级结果落地——表层替换（H2 四字段 + 内存列表）+ 五层卡写盘 + personaCards 挂载。 */
    private void applyUpgradeResult(String name, Map<String, Object> result) {
        String persona = str(result.get("persona"), "");
        String voice = str(result.get("voice"), "");
        String background = str(result.get("background"), "");
        // 表层替换：显式「替换」动作（仅升级路径覆盖，区别于常规 attach 的不覆盖规则）
        databaseService.saveCharacter(name, persona, voice, background);
        for (int i = 0; i < characters.size(); i++) {
            if (name.equals(characters.get(i).get("name"))) {
                Map<String, Object> updated = new LinkedHashMap<>(characters.get(i));
                updated.put("persona", persona);
                updated.put("voice", voice);
                updated.put("background", background);
                characters.set(i, updated);
                break;
            }
        }
        boolean hasLayer = PersonaCardLoader.LAYER_KEYS.stream().anyMatch(result::containsKey);
        if (hasLayer) {
            Map<String, Object> card = buildCardFromResult(name, result);
            personaCards.put(name, card);
            persistCardToDisk(name, card);
        }
    }

    /** P-0811-D：从生成结果构建五层卡（键名对齐 POST /{name}/persona 导入卡契约；name 恒取角色名）。 */
    private Map<String, Object> buildCardFromResult(String name, Map<String, Object> result) {
        Map<String, Object> card = new LinkedHashMap<>();
        for (String k : PersonaCardLoader.LAYER_KEYS) {
            if (result.get(k) != null) card.put(k, result.get(k));
        }
        if (result.get("appearance") != null) card.put("appearance", result.get("appearance"));
        if (result.get("summary") != null) card.put("summary", result.get("summary"));
        Object personaDesc = result.get("personaDesc");
        if (personaDesc == null) personaDesc = result.get("persona");
        if (personaDesc != null) card.put("personaDesc", personaDesc);
        if (result.get("voice") != null) card.put("voice", result.get("voice"));
        if (result.get("background") != null) card.put("background", result.get("background"));
        card.put("name", name);
        return card;
    }

    // ── P-0811-D：五层卡外部目录持久化（data/persona/{角色名}.json，UTF-8 无 BOM） ──

    /** 写盘（目录自动创建；未配置目录/失败 → 静默跳过零破坏）。 */
    private void persistCardToDisk(String name, Map<String, Object> card) {
        if (personaCardsDir == null) return;
        try {
            Files.createDirectories(personaCardsDir);
            Path file = personaCardsDir.resolve(sanitizeFileName(name) + ".json");
            Files.writeString(file, PERSONA_MAPPER.writeValueAsString(card), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("CharacterController: persona 卡落盘失败「{}」: {}", name, e.getMessage());
        }
    }

    /** 删卡文件（角色删除时清理，防重启残留）。 */
    private void deleteCardFileOnDisk(String name) {
        if (personaCardsDir == null) return;
        try {
            Files.deleteIfExists(personaCardsDir.resolve(sanitizeFileName(name) + ".json"));
        } catch (IOException e) {
            log.warn("CharacterController: persona 卡文件删除失败「{}」: {}", name, e.getMessage());
        }
    }

    /** 卡文件改名（角色改名时跟随，防旧名卡残留）。 */
    private void renameCardFileOnDisk(String oldName, String newName) {
        if (personaCardsDir == null) return;
        try {
            Path from = personaCardsDir.resolve(sanitizeFileName(oldName) + ".json");
            if (Files.exists(from)) {
                Files.move(from, personaCardsDir.resolve(sanitizeFileName(newName) + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("CharacterController: persona 卡文件改名失败「{}」→「{}」: {}", oldName, newName, e.getMessage());
        }
    }

    /** 文件名安全化：角色名可能含非法文件名字符（LLM 生成名），替换为下划线。 */
    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @PostMapping("/batch")
    public ResponseEntity<?> batch(@RequestBody List<Map<String, Object>> batch) {
        // 撞名校验 ①：整批预校验——任一撞名（库内已有 或 批内重复）→ 409，整批不落库
        Set<String> seenNames = new HashSet<>();
        Set<String> seenPlayerIds = new HashSet<>();
        for (Map<String, Object> raw : batch) {
            String nm = str(raw.get("name"), "未命名");
            if (hasName(nm) || seenNames.contains(nm)) {
                return conflict("角色名已存在: " + nm);
            }
            seenNames.add(nm);
            String pid = pid(raw.get("player_id"));
            if (pid != null && (hasPlayerId(pid) || seenPlayerIds.contains(pid))) {
                return conflict("该玩家已绑定角色");
            }
            if (pid != null) seenPlayerIds.add(pid);
        }
        for (Map<String, Object> ch : batch) {
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", str(ch.get("name"), "未命名"));
            clean.put("persona", str(ch.get("persona"), ""));
            clean.put("voice", str(ch.get("voice"), ""));
            clean.put("background", str(ch.get("background"), ""));
            clean.put("player_id", pid(ch.get("player_id")));
            characters.add(clean);
            try {
                databaseService.saveCharacter((String) clean.get("name"), (String) clean.get("persona"),
                        (String) clean.get("voice"), (String) clean.get("background"),
                        (String) clean.get("player_id"));
            } catch (DataIntegrityViolationException e) {
                // DB unique 兜底（并发窗口）：回滚本批已加项
                characters.removeIf(c -> seenNames.contains(c.get("name")));
                return conflict("该玩家已绑定角色");
            }
        }
        return ResponseEntity.ok(new ArrayList<>(characters));
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : s;
    }

    /** player_id 规范化：null/空串 → null（未绑定） */
    private static String pid(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    private boolean hasName(String name) {
        return characters.stream().anyMatch(c -> name.equals(c.get("name")));
    }

    private boolean hasPlayerId(String playerId) {
        return characters.stream().anyMatch(c -> playerId.equals(c.get("player_id")));
    }

    /** 撞名 409 响应：{error, detail}（detail 供前端 request() 直接展示） */
    private ResponseEntity<Map<String, Object>> conflict(String msg) {
        return ResponseEntity.status(409).body(Map.of("error", msg, "detail", msg));
    }
}
