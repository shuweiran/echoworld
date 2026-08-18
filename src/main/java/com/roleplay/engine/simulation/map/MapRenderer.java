package com.roleplay.engine.simulation.map;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约 v1 地图 → PNG 渲染器（P-0818-E 视觉审核闭环）。
 *
 * <p>纯 Java（BufferedImage/ImageIO，零浏览器依赖）：ground 瓦片色块 + 碰撞墙加深 +
 * rooms 白框 + decor 家具色块（对齐前端 decorData 色板）+ zones 黄点 + exits 门青色 +
 * warps 紫色传送点。输出 PNG bytes（自动缩放：最长边 ≤ 640px，控制多模态 image token）。
 */
public final class MapRenderer {

    /** 最大渲染边长（px）——控制多模态 image tokens（MiMo 实测小图 image_tokens 个位数）。 */
    public static final int MAX_EDGE = 640;

    /** 瓦片色板（对齐前端 decorData.ts / template_proto 配色）。 */
    private static final Map<Integer, Color> GROUND_COLORS = Map.of(
            MapContract.TILE_FLOOR, new Color(0x8b5e3c),
            MapContract.TILE_WALL, new Color(0x64748b),
            MapContract.TILE_GRASS, new Color(0x3f9e4d),
            MapContract.TILE_CARPET, new Color(0x9c3d3d),
            MapContract.TILE_STONE, new Color(0x94a3b8));

    /** 家具类型色（对齐 decorData FURNITURE 配色子集；未知 → 灰色）。 */
    private static final Map<String, Color> DECOR_COLORS = Map.ofEntries(
            Map.entry("roof", new Color(0x9c3d3d)),
            Map.entry("door", new Color(0x6b4423)),
            Map.entry("chest", new Color(0x7c4a21)),
            Map.entry("note", new Color(0xf1f5f9)),
            Map.entry("lamp", new Color(0xffd166)),
            Map.entry("pillar", new Color(0x9aa5b1)),
            Map.entry("bench", new Color(0x8a5a2b)),
            Map.entry("table_rect", new Color(0xb07a4f)),
            Map.entry("table_round", new Color(0xb07a4f)),
            Map.entry("chair", new Color(0x6b4423)),
            Map.entry("bookshelf", new Color(0x6b4423)),
            Map.entry("sofa", new Color(0xb3564f)),
            Map.entry("bed", new Color(0xf1f5f9)),
            Map.entry("desk", new Color(0xb07a4f)),
            Map.entry("stove", new Color(0x374151)),
            Map.entry("sink", new Color(0xcbd5e1)),
            Map.entry("cabinet", new Color(0x6b4423)),
            Map.entry("shelf", new Color(0x6b4423)),
            Map.entry("plant", new Color(0x2e7d32)),
            Map.entry("tree", new Color(0x1e5631)),
            Map.entry("flower_bed", new Color(0xe63946)),
            Map.entry("fountain", new Color(0x38bdf8)),
            Map.entry("rock", new Color(0x808a93)),
            Map.entry("wood_stack", new Color(0xb07a4f)),
            Map.entry("rug", new Color(0x9c3d3d)),
            Map.entry("window", new Color(0x38bdf8)),
            Map.entry("screen", new Color(0xa0522d)),
            Map.entry("tea_table", new Color(0x6b4423)),
            Map.entry("wardrobe", new Color(0x6b4423)),
            Map.entry("dressing_table", new Color(0xb07a4f)),
            Map.entry("incense", new Color(0xd4a017)),
            Map.entry("scroll", new Color(0xf5e6c8)),
            Map.entry("hay", new Color(0xe0c068)),
            Map.entry("cart", new Color(0x6b4423)),
            Map.entry("counter", new Color(0xb07a4f)),
            Map.entry("counter_4", new Color(0xb07a4f)),
            Map.entry("stool", new Color(0xb07a4f)));

    private static final Color UNKNOWN_DECOR = new Color(0x6b7280);
    private static final Color WALL_DARKEN = new Color(0, 0, 0, 90);
    private static final Color ROOM_BORDER = Color.WHITE;
    private static final Color ZONE_DOT = new Color(0xffd166);
    private static final Color EXIT_DOT = new Color(0x22d3ee);
    private static final Color WARP_DOT = new Color(0x7c4dff);

    private MapRenderer() {
    }

    /** 渲染地图 → PNG bytes（渲染失败返回 null）。 */
    public static byte[] renderPng(Map<String, Object> map) {
        try {
            int W = MapContract.intOf(map.get("width"), 0);
            int H = MapContract.intOf(map.get("height"), 0);
            if (W <= 0 || H <= 0) return null;
            int[][] ground = MapContract.intGrid(map.get("layers") instanceof Map<?, ?> lm
                    ? lm.get("ground") : null);
            int[][] collision = MapContract.intGrid(map.get("layers") instanceof Map<?, ?> lm2
                    ? lm2.get("collision") : null);

            int base = 8; // 每格基础像素
            BufferedImage img = new BufferedImage(W * base, H * base, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 背景
            g.setColor(new Color(0x0c1322));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            // ground 色块
            if (ground != null) {
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        int t = y < ground.length && x < ground[y].length ? ground[y][x] : 0;
                        g.setColor(GROUND_COLORS.getOrDefault(t, new Color(0x6b7280)));
                        g.fillRect(x * base, y * base, base, base);
                    }
                }
            }
            // 碰撞加深（墙/家具/建筑块）
            if (collision != null) {
                g.setColor(WALL_DARKEN);
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        if (y < collision.length && x < collision[y].length && collision[y][x] != 0) {
                            g.fillRect(x * base, y * base, base, base);
                        }
                    }
                }
            }
            // decor 家具色块
            if (map.get("decor") instanceof List<?> decor) {
                for (Object o : decor) {
                    if (!(o instanceof Map<?, ?> d)) continue;
                    Object tile = d.get("tile");
                    if (!(tile instanceof List<?> t) || t.size() != 2
                            || !(t.get(0) instanceof Number) || !(t.get(1) instanceof Number)) continue;
                    int x = ((Number) t.get(0)).intValue();
                    int y = ((Number) t.get(1)).intValue();
                    if (x < 0 || y < 0 || x >= W || y >= H) continue;
                    Color c = DECOR_COLORS.getOrDefault(MapContract.str(d.get("type"), ""), UNKNOWN_DECOR);
                    g.setColor(c);
                    g.fillRect(x * base + 1, y * base + 1, base - 2, base - 2);
                }
            }
            // rooms 白框
            if (map.get("rooms") instanceof List<?> rooms) {
                g.setColor(ROOM_BORDER);
                for (Object o : rooms) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    int x = MapContract.intOf(r.get("x"), 0);
                    int y = MapContract.intOf(r.get("y"), 0);
                    int w = MapContract.intOf(r.get("w"), 0);
                    int h = MapContract.intOf(r.get("h"), 0);
                    g.drawRect(x * base, y * base, w * base, h * base);
                }
            }
            // zones 黄点 / exits 门青色 / warps 紫色
            if (map.get("zones") instanceof List<?> zones) {
                for (Object o : zones) {
                    if (!(o instanceof Map<?, ?> z)) continue;
                    int x = MapContract.intOf(z.get("x"), -1);
                    int y = MapContract.intOf(z.get("y"), -1);
                    if (x >= 0 && y >= 0 && x < W && y < H) {
                        g.setColor(ZONE_DOT);
                        g.fillOval(x * base + 1, y * base + 1, base - 2, base - 2);
                    }
                }
            }
            if (map.get("exits") instanceof List<?> exits) {
                for (Object o : exits) {
                    if (!(o instanceof Map<?, ?> e)) continue;
                    Object door = e.get("door");
                    if (!(door instanceof List<?> dl) || dl.size() != 2
                            || !(dl.get(0) instanceof Number) || !(dl.get(1) instanceof Number)) continue;
                    int x = ((Number) dl.get(0)).intValue();
                    int y = ((Number) dl.get(1)).intValue();
                    if (x >= 0 && y >= 0 && x < W && y < H) {
                        g.setColor(EXIT_DOT);
                        g.fillRect(x * base + 2, y * base + 2, base - 4, base - 4);
                    }
                }
            }
            if (map.get("warps") instanceof List<?> warps) {
                for (Object o : warps) {
                    if (!(o instanceof Map<?, ?> w)) continue;
                    Object from = w.get("from");
                    if (!(from instanceof List<?> fl) || fl.size() != 2
                            || !(fl.get(0) instanceof Number) || !(fl.get(1) instanceof Number)) continue;
                    int x = ((Number) fl.get(0)).intValue();
                    int y = ((Number) fl.get(1)).intValue();
                    if (x >= 0 && y >= 0 && x < W && y < H) {
                        g.setColor(WARP_DOT);
                        g.fillOval(x * base + 2, y * base + 2, base - 4, base - 4);
                    }
                }
            }
            g.dispose();
            // 缩放（最长边 ≤ MAX_EDGE）
            BufferedImage out = img;
            int maxEdge = Math.max(img.getWidth(), img.getHeight());
            if (maxEdge > MAX_EDGE) {
                double scale = MAX_EDGE / (double) maxEdge;
                int nw = Math.max(1, (int) Math.round(img.getWidth() * scale));
                int nh = Math.max(1, (int) Math.round(img.getHeight() * scale));
                out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = out.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, nw, nh, null);
                g2.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染 → data URL（base64 PNG；失败返回 null）。 */
    public static String renderDataUrl(Map<String, Object> map) {
        byte[] png = renderPng(map);
        if (png == null) return null;
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
    }
}
