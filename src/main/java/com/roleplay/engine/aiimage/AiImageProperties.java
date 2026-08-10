package com.roleplay.engine.aiimage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL 角色表情集预生成）：roleplay.ai-image.* 配置绑定。
 *
 * <ul>
 *   <li>comfyui-base-url：本地 ComfyUI API 地址（POST /prompt + GET /history/{id} + GET /view）</li>
 *   <li>output-dir：生成图片落盘目录（默认 {@code src/main/resources/static/ai-images}，
 *       URL 直接 /ai-images/...；AiImageConfig 另注册 file: 资源映射兜底，jar 运行亦可达）</li>
 *   <li>pool-size：生成线程池大小（同一时刻并发提交给 ComfyUI 的任务数）</li>
 *   <li>timeout-seconds：单图生成总超时（提交 + 轮询等待，超时任务标记失败）</li>
 *   <li>poll-interval-ms：/history 轮询间隔</li>
 *   <li>lora-name：Pony 像素风 LoRA 文件名（空=不使用 LoRA，工作流自动改接 UNETLoader/CLIPLoader）</li>
 *   <li>rmbg-model：RMBG-1.4 抠背景 ONNX 模型路径（P-0810-04；缺省/不存在=跳过抠图降级保留原图）</li>
 *   <li>rmbg-enabled：抠背景总开关（P-0810-04；默认 true，false=只存原图不产透明版）</li>
 *   <li>img2img-denoise：表情 img2img 强度（P-0810-05；默认 0.5，0.45 实测脸型/发型/服装 100% 保持）</li>
 *   <li>characters：初始角色注册表（id/name/appearance 外貌描述/style 风格描述——
 *       风格描述必须固定，同一角色所有出图共用同一风格词 + 同一稳定 seed 保证一致）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "roleplay.ai-image")
public class AiImageProperties {

    /** 初始角色条目（yml {@code roleplay.ai-image.characters[]}）。 */
    public static class CharacterSeed {
        private String id;
        private String name;
        private String appearance;
        private String style;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAppearance() { return appearance; }
        public void setAppearance(String appearance) { this.appearance = appearance; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
    }

    private String comfyuiBaseUrl = "http://127.0.0.1:8188";
    private String outputDir = "src/main/resources/static/ai-images";
    private int poolSize = 2;
    private int timeoutSeconds = 300;
    private int pollIntervalMs = 1000;
    private String loraName = "pixel_art_sakuemonq_pony.safetensors";
    /** P-0810-04：RMBG-1.4 抠背景模型路径（默认指向本机 models/rmbg/，不存在则降级跳过）。 */
    private String rmbgModel = "D:\\roleplay-java\\models\\rmbg\\rmbg-1.4.onnx";
    /** P-0810-04：抠背景总开关（默认 true；false=只存原图）。 */
    private boolean rmbgEnabled = true;
    /** P-0810-05：表情 img2img 强度（0-1；默认 0.5，0.45 实测脸型/发型/服装 100% 保持）。 */
    private double img2imgDenoise = 0.5;
    private List<CharacterSeed> characters = new ArrayList<>();

    public String getComfyuiBaseUrl() { return comfyuiBaseUrl; }
    public void setComfyuiBaseUrl(String comfyuiBaseUrl) { this.comfyuiBaseUrl = comfyuiBaseUrl; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public String getLoraName() { return loraName; }
    public void setLoraName(String loraName) { this.loraName = loraName; }

    public String getRmbgModel() { return rmbgModel; }
    public void setRmbgModel(String rmbgModel) { this.rmbgModel = rmbgModel; }

    public boolean isRmbgEnabled() { return rmbgEnabled; }
    public void setRmbgEnabled(boolean rmbgEnabled) { this.rmbgEnabled = rmbgEnabled; }

    public double getImg2imgDenoise() { return img2imgDenoise; }
    public void setImg2imgDenoise(double img2imgDenoise) { this.img2imgDenoise = img2imgDenoise; }

    public List<CharacterSeed> getCharacters() { return characters; }
    public void setCharacters(List<CharacterSeed> characters) { this.characters = characters; }
}
