package com.roleplay.engine.aiimage;

/**
 * P-0810-01（本地 ComfyUI + Pony V6 XL）：单次出图参数（工作流占位符替换输入）。
 *
 * @param positivePrompt 正向提示词（调用方已拼好 score tag + 风格 + 外貌 + 表情 + 构图）
 * @param negativePrompt 负向提示词（非 NSFW 防线：nsfw/nude/暴力/劣质等）
 * @param seed           随机种子（角色级固定：由角色 ID hash 派生，同角色跨图一致）
 * @param width          出图宽（头像/半身 1024×1024，全身 832×1216）
 * @param height         出图高
 * @param loraName       Pony 像素风 LoRA 文件名；null/空白=不使用 LoRA（工作流自动改接）
 * @param prefix         SaveImage filename_prefix（调试定位用）
 */
public record WorkflowSpec(
        String positivePrompt,
        String negativePrompt,
        long seed,
        int width,
        int height,
        String loraName,
        String prefix) {

    public WorkflowSpec {
        if (positivePrompt == null || positivePrompt.isBlank()) {
            throw new IllegalArgumentException("positivePrompt 不能为空");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height 必须为正: " + width + "x" + height);
        }
    }
}
