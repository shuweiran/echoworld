package com.roleplay.engine.aiimage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P-0810-04（RMBG-1.4 立绘自动抠背景）：RmbgRemover 验收。
 *
 * <p>① 降级：模型缺失 / 图片不可读 → 返回 false 不抛异常（调用方保留原图）
 * ② 后处理单测：mask 双线性缩放 + 1px 高斯羽化（确定性纯函数）
 * ③ 合成：RGBA 保留原 RGB，alpha 由 mask 决定
 * ④ 真实模型冒烟（模型存在才跑，Assumptions 跳过式）：合成测试图（白底红圆）→
 *    输出 RGBA 且 alpha 同时存在透明区（背景）与不透明区（前景）
 */
class RmbgRemoverTest {

    private static final Path REAL_MODEL = Path.of(new AiImageProperties().getRmbgModel());

    // ── ① 降级路径 ────────────────────────────────────────────

    @Test
    @DisplayName("R-1 模型缺失降级：返回 false 不抛异常，不产透明文件")
    void degradeWhenModelMissing() throws Exception {
        Path dir = Files.createTempDirectory("rmbg-missing");
        RmbgRemover remover = new RmbgRemover(dir.resolve("no-such-model.onnx").toString());
        Path rgb = dir.resolve("in.png");
        Path t = dir.resolve("in_t.png");
        // 合法 PNG 原图
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ImageIO.write(img, "png", rgb.toFile());

        assertFalse(remover.modelAvailable());
        assertFalse(remover.removeBackground(rgb, t), "模型缺失应降级返回 false");
        assertFalse(Files.exists(t), "模型缺失不应产出透明文件");
        remover.close();
    }

    @Test
    @DisplayName("R-2 图片不可读降级：垃圾字节不触发模型加载，返回 false")
    void degradeWhenImageUnreadable() throws Exception {
        Path dir = Files.createTempDirectory("rmbg-badimg");
        Path rgb = dir.resolve("garbage.png");
        Path t = dir.resolve("garbage_t.png");
        Files.write(rgb, new byte[]{1, 2, 3});
        RmbgRemover remover = new RmbgRemover(REAL_MODEL.toString());
        assertFalse(remover.removeBackground(rgb, t));
        assertFalse(Files.exists(t));
        remover.close();
    }

    @Test
    @DisplayName("R-3 原图不存在降级")
    void degradeWhenSourceMissing() throws Exception {
        Path dir = Files.createTempDirectory("rmbg-nosrc");
        RmbgRemover remover = new RmbgRemover(REAL_MODEL.toString());
        assertFalse(remover.removeBackground(dir.resolve("nope.png"), dir.resolve("nope_t.png")));
        remover.close();
    }

    // ── ② 后处理纯函数单测 ──────────────────────────────────────

    @Test
    @DisplayName("R-4 mask 缩放：常量 mask 缩放后仍常量；1024→小尺寸形状正确")
    void resizeMaskKeepsConstantAndShape() {
        // 常量 0.5 mask → 任意尺寸仍 0.5
        float[] constMask = new float[RmbgRemover.MODEL_INPUT * RmbgRemover.MODEL_INPUT];
        Arrays.fill(constMask, 0.5f);
        float[] small = RmbgRemover.resizeMask(constMask, 17, 11);
        assertEquals(17 * 11, small.length);
        for (float v : small) assertEquals(0.5f, v, 1e-6);

        // 中心亮区（256~768 填充 1.0）→ 缩放后中心仍亮、角落仍暗
        float[] spot = new float[RmbgRemover.MODEL_INPUT * RmbgRemover.MODEL_INPUT];
        for (int y = RmbgRemover.MODEL_INPUT / 4; y < 3 * RmbgRemover.MODEL_INPUT / 4; y++) {
            for (int x = RmbgRemover.MODEL_INPUT / 4; x < 3 * RmbgRemover.MODEL_INPUT / 4; x++) {
                spot[y * RmbgRemover.MODEL_INPUT + x] = 1f;
            }
        }
        float[] out = RmbgRemover.resizeMask(spot, 8, 8);
        assertTrue(out[4 * 8 + 4] > 0.9f, "中心应保持高值，实际=" + out[4 * 8 + 4]);
        assertEquals(0f, out[0], 1e-6, "角落应保持 0");
    }

    @Test
    @DisplayName("R-5 1px 高斯羽化：硬边缘被软化，内部保持、外部保持")
    void gaussianBlurFeathersEdge() {
        int w = 32, h = 32;
        float[] mask = new float[w * h];
        // 左半 1.0，右半 0.0（硬边缘）
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask[y * w + x] = x < w / 2 ? 1f : 0f;
            }
        }
        float[] blurred = RmbgRemover.gaussianBlur1px(mask, w, h);
        assertEquals(1f, blurred[5 * w + 5], 1e-4, "远离边缘的内部保持 1");
        assertEquals(0f, blurred[5 * w + 26], 1e-4, "远离边缘的外部保持 0");
        // 边缘像素被软化（0 < v < 1）
        float edge = blurred[h / 2 * w + w / 2];
        assertTrue(edge > 0.05f && edge < 0.95f, "边缘应被羽化，实际=" + edge);
        // 边缘两侧对称过渡
        float leftOfEdge = blurred[h / 2 * w + w / 2 - 1];
        float rightOfEdge = blurred[h / 2 * w + w / 2 + 1];
        assertTrue(leftOfEdge > rightOfEdge, "靠近前景侧应更高");
    }

    // ── ③ 合成 ─────────────────────────────────────────────────

    @Test
    @DisplayName("R-6b min-max 归一化：拉伸对比度，常量 mask 原样")
    void normalizeMaskStretchesContrast() {
        float[] flat = new float[RmbgRemover.MODEL_INPUT * RmbgRemover.MODEL_INPUT];
        Arrays.fill(flat, 0.7f);
        assertSame(flat, RmbgRemover.normalizeMask(flat), "常量 mask 应原样返回");

        float[] soft = new float[RmbgRemover.MODEL_INPUT * RmbgRemover.MODEL_INPUT];
        // 背景 0.3 / 前景 0.8（模拟未归一化 sigmoid 输出的灰背景）
        for (int i = 0; i < soft.length; i++) soft[i] = i % 2 == 0 ? 0.3f : 0.8f;
        float[] norm = RmbgRemover.normalizeMask(soft);
        assertEquals(0f, norm[0], 1e-6, "最小值应归一到 0（背景透明）");
        assertEquals(1f, norm[1], 1e-6, "最大值应归一到 1（前景不透明）");
    }

    @Test
    @DisplayName("R-6 合成：RGBA 保留原 RGB，alpha 由 mask 决定")
    void compositePreservesRgbAndAppliesAlpha() {
        BufferedImage rgb = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                rgb.setRGB(x, y, 0xFF0000 | (x << 8) | y); // 红 + 少量绿/蓝区分
            }
        }
        float[] alpha = new float[16];
        Arrays.fill(alpha, 0.5f);
        BufferedImage out = RmbgRemover.composite(rgb, alpha);
        assertEquals(BufferedImage.TYPE_INT_ARGB, out.getType());
        int argb = out.getRGB(1, 2);
        assertEquals(128, (argb >>> 24) & 0xFF, "alpha=0.5 → 128");
        assertEquals(0xFF, (argb >> 16) & 0xFF, "RGB 原样保留");
        assertEquals(1, (argb >> 8) & 0xFF);
        assertEquals(2, argb & 0xFF);
    }

    // ── ④ 真实模型冒烟（模型存在才执行） ──────────────────────────

    @Test
    @DisplayName("R-7 真实模型冒烟：合成测试图（白底红圆）→ 输出 RGBA 且 alpha 有透明区与不透明区")
    void realModelSmoke() throws IOException {
        assumeTrue(Files.isRegularFile(REAL_MODEL), "RMBG 模型不存在，跳过真实推理冒烟（降级路径由 R-1~R-3 覆盖）");

        Path dir = Files.createTempDirectory("rmbg-smoke");
        Path rgb = dir.resolve("test.png");
        Path t = dir.resolve("test_t.png");
        // 256×256 白底 + 红色实心圆（半径 80）
        BufferedImage src = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 256, 256);
        g.setColor(new Color(220, 30, 30));
        g.fillOval(256 / 2 - 80, 256 / 2 - 80, 160, 160);
        g.dispose();
        ImageIO.write(src, "png", rgb.toFile());

        RmbgRemover remover = new RmbgRemover(REAL_MODEL.toString());
        try {
            long start = System.currentTimeMillis();
            assertTrue(remover.removeBackground(rgb, t), "真实模型应抠图成功");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(Files.exists(t));

            BufferedImage out = ImageIO.read(t.toFile());
            assertNotNull(out);
            assertEquals(256, out.getWidth());
            assertEquals(256, out.getHeight());
            assertTrue(out.getColorModel().hasAlpha(), "输出应为含 alpha 的 RGBA");

            int transparent = 0, opaque = 0;
            int centerAlpha = 0;
            for (int y = 0; y < out.getHeight(); y++) {
                for (int x = 0; x < out.getWidth(); x++) {
                    int a = (out.getRGB(x, y) >>> 24) & 0xFF;
                    if (a == 0) transparent++;
                    else if (a > 200) opaque++;
                    if (x == 128 && y == 128) centerAlpha = a;
                }
            }
            // 背景（角落）应透明、前景（圆内）应不透明
            assertTrue(transparent > 1000, "背景应存在透明区，透明像素=" + transparent);
            assertTrue(opaque > 1000, "前景应存在不透明区，不透明像素=" + opaque);
            assertTrue(centerAlpha > 150, "圆心应基本不透明，alpha=" + centerAlpha);
            // 原 RGB 保留：圆心仍偏红
            int centerRgb = out.getRGB(128, 128) & 0x00FFFFFF;
            assertTrue(((centerRgb >> 16) & 0xFF) > 150, "圆心应保留红色，rgb=" + Integer.toHexString(centerRgb));
            System.out.println("[RMBG 冒烟] " + t + " 耗时=" + elapsed + "ms 透明=" + transparent + " 不透明=" + opaque);
        } finally {
            remover.close();
        }
    }
}
