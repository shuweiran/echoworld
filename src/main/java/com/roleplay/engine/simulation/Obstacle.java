package com.roleplay.engine.simulation;

import java.util.*;

public class Obstacle {

    public enum Type { TREE, BUILDING, ROCK, TABLE, WALL, WATER, BENCH, BUSH, FOUNTAIN, LAMP }

    private final Type type;
    private final double x, y, width, height;
    private final boolean blocksSound;
    private final String label;

    public Obstacle(Type type, double x, double y, double width, double height,
                    boolean blocksSound, String label) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.blocksSound = blocksSound;
        this.label = label != null ? label : "";
    }

    public Type getType() { return type; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public boolean blocksSound() { return blocksSound; }
    public String getLabel() { return label; }

    public double getCenterX() { return x + width / 2; }
    public double getCenterY() { return y + height / 2; }

    public boolean contains(double px, double py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    public boolean intersectsCircle(double cx, double cy, double radius) {
        double closestX = Math.max(x, Math.min(cx, x + width));
        double closestY = Math.max(y, Math.min(cy, y + height));
        double dx = cx - closestX;
        double dy = cy - closestY;
        return (dx * dx + dy * dy) < (radius * radius);
    }

    public boolean intersectsLine(double x1, double y1, double x2, double y2) {
        return lineRectIntersect(x1, y1, x2, y2, x, y, width, height);
    }

    private static boolean lineRectIntersect(double x1, double y1, double x2, double y2,
                                              double rx, double ry, double rw, double rh) {
        if (lineSegIntersect(x1, y1, x2, y2, rx, ry, rx + rw, ry)) return true;
        if (lineSegIntersect(x1, y1, x2, y2, rx + rw, ry, rx + rw, ry + rh)) return true;
        if (lineSegIntersect(x1, y1, x2, y2, rx + rw, ry + rh, rx, ry + rh)) return true;
        if (lineSegIntersect(x1, y1, x2, y2, rx, ry + rh, rx, ry)) return true;
        return false;
    }

    private static boolean lineSegIntersect(double x1, double y1, double x2, double y2,
                                             double x3, double y3, double x4, double y4) {
        double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(d) < 1e-10) return false;
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d;
        double u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / d;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        m.put("x", x);
        m.put("y", y);
        m.put("width", width);
        m.put("height", height);
        m.put("blocksSound", blocksSound);
        m.put("label", label);
        return m;
    }

    // ── Scene presets ─────────────────────────────────────────

    /**
     * P-0811-G：把 LLM 地图 collision 瓦片网格（0=可走/非0=碰撞，height×width）转换为模拟世界
     * 的矩形 Obstacle。瓦片合并策略：逐行合并连续碰撞瓦片为横向矩形（水平扫描线），
     * 再对相邻行同列区间做竖向合并（降低 Obstacle 数量、减少寻路开销）。
     *
     * <p>坐标缩放：模拟世界 1000×600，地图瓦片网格 width×height —— 缩放系数 = min(1000/width, 600/height)，
     * 每个瓦片 = tileSizePx 像素（模拟世界坐标），障碍矩形居中偏移保证整体贴边。
     *
     * @param collision 碰撞网格（int[height][width]，0=空/非0=墙）
     * @param tileSizePx 瓦片像素边长（契约 tile_size，用于换算；世界 1000×600 内放大）
     * @param label 障碍标签（场景名）
     * @return 合并后的 Obstacle 列表（最多 MAX_MERGE 个，超出仅保留大块）
     */
    public static List<Obstacle> fromCollisionGrid(int[][] collision, int tileSizePx, String label) {
        return fromCollisionGrid(collision, tileSizePx, label, WORLD_W, WORLD_H);
    }

    /** 地图坐标就是世界坐标：MapContract 宽×高×tileSize 决定物理边界。 */
    public static List<Obstacle> fromCollisionGrid(int[][] collision, int tileSizePx, String label,
                                                   double worldWidth, double worldHeight) {
        List<Obstacle> out = new ArrayList<>();
        if (collision == null || collision.length == 0) return out;
        int h = collision.length;
        int w = collision[0].length;
        if (w == 0 || h == 0) return out;
        // 世界坐标缩放：模拟世界 1000×600 铺满整个地图（x/y 独立等分，无留白）——
        // P-0811-G 修复：此前 min 缩放 + 居中留白，角色出生/移动落在 offset 留白区 → 「角色挤出地图外」。
        // 铺满后地图边界 = 世界边界，角色始终在地图内。
        double tileW = worldWidth / (double) w;
        double tileH = worldHeight / (double) h;
        double offsetX = 0.0;
        double offsetY = 0.0;

        // 标记已并入障碍的瓦片
        boolean[][] used = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (collision[y][x] == 0 || used[y][x]) continue;
                // 横向扩展
                int xEnd = x;
                while (xEnd + 1 < w && collision[y][xEnd + 1] != 0 && !used[y][xEnd + 1]) xEnd++;
                // 纵向扩展（对齐 x..xEnd 整段）
                int yEnd = y;
                outer:
                while (yEnd + 1 < h) {
                    for (int cx = x; cx <= xEnd; cx++) {
                        if (collision[yEnd + 1][cx] == 0 || used[yEnd + 1][cx]) break outer;
                    }
                    yEnd++;
                }
                // 标记
                for (int ry = y; ry <= yEnd; ry++) {
                    for (int cx = x; cx <= xEnd; cx++) used[ry][cx] = true;
                }
                double ox = offsetX + x * tileW;
                double oy = offsetY + y * tileH;
                double ow = (xEnd - x + 1) * tileW;
                double oh = (yEnd - y + 1) * tileH;
                out.add(new Obstacle(Type.WALL, ox, oy, ow, oh, true,
                        label == null || label.isBlank() ? "墙" : label));
            }
        }
        return out;
    }

    private static final double WORLD_W = 1000.0;
    private static final double WORLD_H = 600.0;

    public static List<Obstacle> createScene(String sceneName, double worldW, double worldH) {
        return switch (sceneName.toLowerCase()) {
            case "park" -> parkScene(worldW, worldH);
            case "city" -> cityScene(worldW, worldH);
            case "cafe" -> cafeScene(worldW, worldH);
            case "forest" -> forestScene(worldW, worldH);
            case "classroom" -> classroomScene(worldW, worldH);
            case "beach" -> beachScene(worldW, worldH);
            default -> parkScene(worldW, worldH);
        };
    }

    public static List<String> availableScenes() {
        return List.of("park", "city", "cafe", "forest", "classroom", "beach");
    }

    private static List<Obstacle> parkScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        obs.add(new Obstacle(Type.TREE, 300, 150, 40, 40, true, "大树"));
        obs.add(new Obstacle(Type.TREE, 700, 400, 35, 35, true, "银杏树"));
        obs.add(new Obstacle(Type.FOUNTAIN, 480, 280, 60, 60, false, "喷泉"));
        obs.add(new Obstacle(Type.BENCH, 200, 450, 80, 20, false, "长椅"));
        obs.add(new Obstacle(Type.BENCH, 750, 150, 80, 20, false, "长椅"));
        obs.add(new Obstacle(Type.BUSH, 550, 100, 100, 30, true, "灌木丛"));
        obs.add(new Obstacle(Type.LAMP, 100, 300, 10, 10, false, "路灯"));
        obs.add(new Obstacle(Type.LAMP, 900, 300, 10, 10, false, "路灯"));
        obs.add(new Obstacle(Type.TREE, 150, 530, 30, 30, true, "柳树"));
        return obs;
    }

    private static List<Obstacle> cityScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        obs.add(new Obstacle(Type.BUILDING, 50, 50, 120, 200, true, "咖啡店"));
        obs.add(new Obstacle(Type.BUILDING, 250, 50, 120, 250, true, "书店"));
        obs.add(new Obstacle(Type.BUILDING, 450, 50, 150, 180, true, "商场"));
        obs.add(new Obstacle(Type.BUILDING, 680, 50, 120, 220, true, "办公楼"));
        obs.add(new Obstacle(Type.BUILDING, 860, 50, 100, 200, true, "餐厅"));
        obs.add(new Obstacle(Type.BUILDING, 50, 350, 150, 200, true, "公寓"));
        obs.add(new Obstacle(Type.BUILDING, 280, 380, 140, 180, true, "电影院"));
        obs.add(new Obstacle(Type.BUILDING, 520, 340, 160, 220, true, "医院"));
        obs.add(new Obstacle(Type.BUILDING, 780, 380, 180, 180, true, "学校"));
        obs.add(new Obstacle(Type.LAMP, 220, 260, 8, 8, false, ""));
        obs.add(new Obstacle(Type.LAMP, 620, 260, 8, 8, false, ""));
        return obs;
    }

    private static List<Obstacle> cafeScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        obs.add(new Obstacle(Type.WALL, 0, 0, w, 15, true, ""));
        obs.add(new Obstacle(Type.WALL, 0, h - 15, w, 15, true, ""));
        obs.add(new Obstacle(Type.WALL, 0, 0, 15, h, true, ""));
        obs.add(new Obstacle(Type.WALL, w - 15, 0, 15, h, true, ""));
        obs.add(new Obstacle(Type.TABLE, 250, 200, 120, 80, false, "大桌"));
        obs.add(new Obstacle(Type.TABLE, 600, 200, 100, 80, false, "小桌"));
        obs.add(new Obstacle(Type.TABLE, 420, 380, 100, 80, false, "圆桌"));
        obs.add(new Obstacle(Type.WALL, 490, 50, 20, 550, true, "吧台"));
        return obs;
    }

    private static List<Obstacle> forestScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        Random rng = new Random(42);
        for (int i = 0; i < 30; i++) {
            double x = 40 + rng.nextDouble() * (w - 80);
            double y = 40 + rng.nextDouble() * (h - 80);
            double s = 20 + rng.nextDouble() * 35;
            obs.add(new Obstacle(Type.TREE, x, y, s, s, true, ""));
        }
        obs.add(new Obstacle(Type.WATER, 350, 250, 300, 100, true, "小溪"));
        obs.add(new Obstacle(Type.ROCK, 150, 150, 50, 30, true, "大石头"));
        obs.add(new Obstacle(Type.ROCK, 800, 450, 60, 35, true, "巨石"));
        return obs;
    }

    private static List<Obstacle> classroomScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        obs.add(new Obstacle(Type.WALL, 0, 0, w, 10, true, ""));
        obs.add(new Obstacle(Type.WALL, 0, h - 10, w, 10, true, ""));
        obs.add(new Obstacle(Type.WALL, 0, 0, 10, h, true, ""));
        obs.add(new Obstacle(Type.WALL, w - 10, 0, 10, h, true, ""));
        obs.add(new Obstacle(Type.TABLE, 150, 150, 200, 80, false, "讲台"));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                obs.add(new Obstacle(Type.TABLE, 120 + col * 200, 280 + row * 100, 100, 70, false, "课桌"));
            }
        }
        return obs;
    }

    private static List<Obstacle> beachScene(double w, double h) {
        List<Obstacle> obs = new ArrayList<>();
        obs.add(new Obstacle(Type.WATER, 0, h - 120, w, 120, false, "海"));
        obs.add(new Obstacle(Type.ROCK, 200, 200, 60, 40, true, "礁石"));
        obs.add(new Obstacle(Type.ROCK, 750, 180, 50, 35, true, "礁石"));
        obs.add(new Obstacle(Type.TREE, 500, 250, 30, 50, true, "棕榈树"));
        obs.add(new Obstacle(Type.TREE, 150, 400, 30, 45, true, "棕榈树"));
        obs.add(new Obstacle(Type.TREE, 850, 350, 30, 45, true, "棕榈树"));
        obs.add(new Obstacle(Type.BENCH, 400, 450, 100, 20, false, "沙滩椅"));
        obs.add(new Obstacle(Type.BENCH, 650, 430, 100, 20, false, "沙滩椅"));
        return obs;
    }
}
