package com.roleplay.engine.aiimage;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

/**
 * P-0810-04（RMBG-1.4 立绘自动抠背景）：BRIA RMBG-1.4 ONNX 模型推理，输出透明 PNG。
 *
 * <p>流水线（对齐已实测的 python_embeded 推理脚本）：
 * <ol>
 *   <li>读 RGB 图 → 双线性缩放 1024×1024 → RGB float32/255 → NHWC→NCHW（1×3×1024×1024）</li>
 *   <li>{@code session.run} → 输出 sigmoid mask（1×1×1024×1024 float）</li>
 *   <li>mask 双线性缩回原图尺寸 → 1px 高斯羽化（3×3 kernel σ=1.0）→ 与 RGB 合成 RGBA 透明 PNG</li>
 * </ol>
 *
 * <p><b>降级纪律</b>：模型文件不存在 / 图片不可读 / 推理失败 —— 一律返回 {@code false}
 * 且不抛异常，由调用方（ImageGenService.genOne）降级保留原图，log.warn 不阻塞主流程。
 * 模型路径外置（默认 {@code D:\echoworld\models\rmbg\rmbg-1.4.onnx}），不打包进 jar。
 *
 * <p>非 Spring 强依赖类：由 ImageGenService 直构（{@link #RmbgRemover(AiImageProperties)}），
 * 懒加载 OrtSession（首次 removeBackground 才初始化，模型缺失零成本跳过）。
 * 本机无 CUDA provider，走 CPU EP 推理（实测约 1.5s/张）。
 */
public class RmbgRemover {

    private static final Logger log = LoggerFactory.getLogger(RmbgRemover.class);

    /** RMBG-1.4 固定输入尺寸（模型训练规格，不可变）。 */
    public static final int MODEL_INPUT = 1024;

    private final String modelPath;
    private final Object initLock = new Object();
    private volatile OrtSession session;
    private volatile boolean initTried;

    public RmbgRemover(String modelPath) {
        this.modelPath = modelPath == null ? "" : modelPath.trim();
    }

    public RmbgRemover(AiImageProperties props) {
        this(props == null ? null : props.getRmbgModel());
    }

    public String modelPath() {
        return modelPath;
    }

    /** 模型文件是否存在（懒加载前判断，不存在则全程跳过抠图）。 */
    public boolean modelAvailable() {
        return !modelPath.isEmpty() && Files.isRegularFile(Path.of(modelPath));
    }

    /**
     * 抠背景主入口：{@code rgbPng} 存在且模型可用时产出透明 PNG {@code transparentPng}。
     *
     * @return true=抠图成功产出透明版；false=降级（模型缺失/图片不可读/推理失败），原图保留
     */
    public boolean removeBackground(Path rgbPng, Path transparentPng) {
        if (rgbPng == null || transparentPng == null) return false;
        if (!Files.isRegularFile(rgbPng)) {
            log.warn("RMBG 跳过：原图不存在 {}", rgbPng);
            return false;
        }
        try {
            // 先读图再取 session：图不可读时（如测试假字节）不触发模型加载
            BufferedImage image = ImageIO.read(rgbPng.toFile());
            if (image == null) {
                log.warn("RMBG 跳过：图片不可读（非 PNG 或损坏）: {}", rgbPng);
                return false;
            }
            OrtSession s = session();
            if (s == null) {
                log.info("RMBG 跳过：模型不可用 {}（保留原图）", modelPath);
                return false;
            }
            float[] input = preprocess(image);
            float[] mask = runInference(s, input);
            // 官方 RMBG-1.4 后处理：min-max 归一化拉伸对比度（背景→0 前景→1），否则原始 sigmoid 输出
            // 背景带灰值，合成后产生大面积半透明薄雾（冒烟实测 46%+ 半透明像素）
            mask = normalizeMask(mask);
            float[] maskResized = resizeMask(mask, image.getWidth(), image.getHeight());
            float[] feathered = gaussianBlur1px(maskResized, image.getWidth(), image.getHeight());
            BufferedImage out = composite(image, feathered);
            Files.createDirectories(transparentPng.toAbsolutePath().getParent());
            ImageIO.write(out, "png", transparentPng.toFile());
            return true;
        } catch (Throwable t) {
            log.warn("RMBG 抠图失败（降级保留原图）: {} err={}", rgbPng, t);
            return false;
        }
    }

    /** 测试/关闭用：释放 OrtSession 原生资源。 */
    public void close() {
        OrtSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                log.debug("RMBG session close 忽略: {}", e.toString());
            }
        }
    }

    // ── 模型会话（懒加载） ───────────────────────────────────────

    private OrtSession session() {
        OrtSession s = session;
        if (s != null) return s;
        if (initTried) return null;
        synchronized (initLock) {
            if (session != null) return session;
            initTried = true;
            if (!modelAvailable()) return null;
            try {
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                session = env.createSession(modelPath, new OrtSession.SessionOptions());
                log.info("RMBG 模型加载成功: {} 输入={} 输出={}",
                        modelPath, session.getInputNames(), session.getOutputNames());
            } catch (Exception e) {
                log.warn("RMBG 模型加载失败（降级保留原图）: {} err={}", modelPath, e);
            }
            return session;
        }
    }

    // ── 预处理：RGB → 1×3×1024×1024 NCHW float32/255（双线性缩放） ──

    static float[] preprocess(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[] out = new float[3 * MODEL_INPUT * MODEL_INPUT];
        int[] row = new int[w];
        for (int y = 0; y < MODEL_INPUT; y++) {
            float fy = sampleCoord(y, h);
            int y0 = Math.min((int) fy, h - 1);
            int y1 = Math.min(y0 + 1, h - 1);
            float wy = fy - y0;
            image.getRGB(0, y0, w, 1, row, 0, w);
            int[] row1 = y1 == y0 ? row : new int[w];
            if (y1 != y0) image.getRGB(0, y1, w, 1, row1, 0, w);
            for (int x = 0; x < MODEL_INPUT; x++) {
                float fx = sampleCoord(x, w);
                int x0 = Math.min((int) fx, w - 1);
                int x1 = Math.min(x0 + 1, w - 1);
                float wx = fx - x0;
                int c00 = row[x0], c01 = row[x1], c10 = row1[x0], c11 = row1[x1];
                int r = lerp(lerp((c00 >> 16) & 0xFF, (c01 >> 16) & 0xFF, wx),
                        lerp((c10 >> 16) & 0xFF, (c11 >> 16) & 0xFF, wx), wy);
                int g = lerp(lerp((c00 >> 8) & 0xFF, (c01 >> 8) & 0xFF, wx),
                        lerp((c10 >> 8) & 0xFF, (c11 >> 8) & 0xFF, wx), wy);
                int b = lerp(lerp(c00 & 0xFF, c01 & 0xFF, wx),
                        lerp(c10 & 0xFF, c11 & 0xFF, wx), wy);
                int idx = y * MODEL_INPUT + x;
                out[idx] = r / 255f;
                out[MODEL_INPUT * MODEL_INPUT + idx] = g / 255f;
                out[2 * MODEL_INPUT * MODEL_INPUT + idx] = b / 255f;
            }
        }
        return out;
    }

    private static float sampleCoord(int i, int len) {
        return len <= 1 ? 0 : i * (len - 1) / (float) (MODEL_INPUT - 1);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    // ── 推理 ────────────────────────────────────────────────────

    private static float[] runInference(OrtSession s, float[] input) throws OrtException, IOException {
        String inputName = firstOf(s.getInputNames(), "input");
        String outputName = firstOf(s.getOutputNames(), "output");
        long[] shape = {1, 3, MODEL_INPUT, MODEL_INPUT};
        try (OnnxTensor tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), shape);
             OrtSession.Result result = s.run(Collections.singletonMap(inputName, tensor))) {
            OnnxValue value = result.get(0);
            if (!(value instanceof OnnxTensor)) {
                throw new IOException("RMBG 输出类型异常: " + (value == null ? "null" : value.getType()));
            }
            FloatBuffer fb = ((OnnxTensor) value).getFloatBuffer();
            if (fb.remaining() < MODEL_INPUT * MODEL_INPUT) {
                throw new IOException("RMBG 输出尺寸异常: " + fb.remaining());
            }
            float[] mask = new float[MODEL_INPUT * MODEL_INPUT];
            fb.get(mask);
            return mask;
        }
    }

    private static String firstOf(Set<String> names, String fallback) {
        return names == null || names.isEmpty() ? fallback : names.iterator().next();
    }

    // ── 后处理：mask 缩回原尺寸 + 1px 高斯羽化 + RGBA 合成 ──

    /** 官方 RMBG-1.4 后处理：min-max 归一化到 [0,1]（常量 mask 原样返回）。 */
    static float[] normalizeMask(float[] mask) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : mask) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = max - min;
        if (range < 1e-6f) return mask;
        float[] out = new float[mask.length];
        for (int i = 0; i < mask.length; i++) {
            out[i] = (mask[i] - min) / range;
        }
        return out;
    }

    /** mask（1024×1024，sigmoid 值 [0,1]）双线性缩放到 w×h。 */
    static float[] resizeMask(float[] mask1024, int w, int h) {
        float[] out = new float[w * h];
        if (w <= 0 || h <= 0) return out;
        for (int y = 0; y < h; y++) {
            float fy = sampleFrom(y, h);
            int y0 = Math.min((int) fy, MODEL_INPUT - 1);
            int y1 = Math.min(y0 + 1, MODEL_INPUT - 1);
            float wy = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = sampleFrom(x, w);
                int x0 = Math.min((int) fx, MODEL_INPUT - 1);
                int x1 = Math.min(x0 + 1, MODEL_INPUT - 1);
                float wx = fx - x0;
                float v00 = mask1024[y0 * MODEL_INPUT + x0];
                float v01 = mask1024[y0 * MODEL_INPUT + x1];
                float v10 = mask1024[y1 * MODEL_INPUT + x0];
                float v11 = mask1024[y1 * MODEL_INPUT + x1];
                out[y * w + x] = (v00 * (1 - wx) + v01 * wx) * (1 - wy) + (v10 * (1 - wx) + v11 * wx) * wy;
            }
        }
        return out;
    }

    private static float sampleFrom(int i, int len) {
        return len <= 1 ? 0 : i * (MODEL_INPUT - 1) / (float) (len - 1);
    }

    /** 1px 高斯羽化（3×3 kernel，σ=1.0，边缘 clamp）。 */
    static float[] gaussianBlur1px(float[] mask, int w, int h) {
        float[] out = new float[w * h];
        float[][] k = new float[3][3];
        float sum = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float v = (float) Math.exp(-(dx * dx + dy * dy) / 2.0);
                k[dy + 1][dx + 1] = v;
                sum += v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.max(0, Math.min(h - 1, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.max(0, Math.min(w - 1, x + dx));
                        acc += mask[yy * w + xx] * k[dy + 1][dx + 1];
                    }
                }
                out[y * w + x] = acc / sum;
            }
        }
        return out;
    }

    /** alpha（[0,1] 羽化后 mask）与 RGB 合成 RGBA：颜色原样保留，透明度由 mask 决定。 */
    static BufferedImage composite(BufferedImage rgb, float[] alpha) {
        int w = rgb.getWidth();
        int h = rgb.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = rgb.getRGB(x, y);
                int a = Math.round(Math.max(0f, Math.min(1f, alpha[y * w + x])) * 255);
                out.setRGB(x, y, (a << 24) | (argb & 0x00FFFFFF));
            }
        }
        return out;
    }
}
