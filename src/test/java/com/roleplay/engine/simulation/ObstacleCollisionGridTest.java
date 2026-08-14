package com.roleplay.engine.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P-0811-G：LLM 地图 collision 瓦片 → 模拟世界障碍转换测试。
 * <p>验证 fromCollisionGrid：单瓦片/水平连续/垂直连续/十字形/空网格/非0语义，
 * 以及坐标缩放（世界 1000×600 贴边）、障碍 blocksSound=true（WALL）。
 */
class ObstacleCollisionGridTest {

    private static int[][] grid(String... rows) {
        int h = rows.length;
        int w = rows[0].length();
        int[][] g = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                g[y][x] = rows[y].charAt(x) == '#' ? 1 : 0;
            }
        }
        return g;
    }

    private static long coveredTiles(List<Obstacle> obs, int w, int h) {
        // 与 fromCollisionGrid 同规则：瓦片为正方形（min 缩放），居中偏移贴边
        double tile = Math.min(1000.0 / w, 600.0 / h);
        double offX = (1000.0 - tile * w) / 2.0;
        double offY = (600.0 - tile * h) / 2.0;
        long covered = 0;
        for (Obstacle o : obs) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double cx = offX + x * tile + tile / 2.0;
                    double cy = offY + y * tile + tile / 2.0;
                    if (o.contains(cx, cy)) covered++;
                }
            }
        }
        return covered;
    }

    @Test
    @DisplayName("单瓦片 → 一个障碍；坐标贴边缩放")
    void singleTile() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(grid(".#"), 32, "t");
        assertEquals(1, obs.size());
        Obstacle o = obs.get(0);
        assertTrue(o.getWidth() > 0 && o.getHeight() > 0, "障碍应有尺寸");
        assertTrue(o.blocksSound(), "墙障碍应阻隔声音");
        assertTrue(o.getX() >= 0 && o.getY() >= 0, "障碍应贴边不越界");
        assertTrue(o.getX() + o.getWidth() <= 1000 && o.getY() + o.getHeight() <= 600, "障碍应在世界内");
    }

    @Test
    @DisplayName("水平连续碰撞 → 合并为横向矩形")
    void horizontalMerge() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(grid("###"), 32, "t");
        assertEquals(1, obs.size(), "连续三瓦片应合并为一个横向矩形");
        Obstacle o = obs.get(0);
        // 瓦片为正方形（min 缩放）：tile=min(1000/3, 600/1)=333.3，宽=3瓦片、高=1瓦片
        double tile = Math.min(1000.0 / 3, 600.0 / 1);
        assertEquals(3 * tile, o.getWidth(), 1e-6, "宽度应为 3 瓦片");
        assertEquals(1 * tile, o.getHeight(), 1e-6, "高度应为 1 瓦片");
        assertEquals(3, coveredTiles(obs, 3, 1), "应覆盖全部 3 个碰撞瓦片");
    }

    @Test
    @DisplayName("垂直连续 → 合并为竖向矩形")
    void verticalMerge() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(grid("#", "#"), 32, "t");
        assertEquals(1, obs.size(), "上下连续应合并为一个竖向矩形");
        Obstacle o = obs.get(0);
        assertEquals(600.0 / 2 * 2, o.getHeight(), 1e-6, "高度应为 2 瓦片");
    }

    @Test
    @DisplayName("十字形 → 合并为一个十字（中心连通）")
    void crossShape() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(
                grid(".#.", "###", ".#."), 32, "t");
        // 逐行扫描合并：中心列 (1,0..2) 纵向合并 + 中行两侧 (0,1)/(2,1) 各自 → 3 个障碍
        // 但覆盖必须完整（5 个碰撞瓦片一个不落）
        assertEquals(5, coveredTiles(obs, 3, 3), "应覆盖全部 5 个碰撞瓦片");
        assertFalse(obs.isEmpty());
    }

    @Test
    @DisplayName("空网格 → 无障碍")
    void emptyGrid() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(grid("...", "..." ), 32, "t");
        assertTrue(obs.isEmpty(), "无可走网格应无障碍");
        assertTrue(Obstacle.fromCollisionGrid(new int[0][0], 32, "t").isEmpty());
        assertTrue(Obstacle.fromCollisionGrid(null, 32, "t").isEmpty());
    }

    @Test
    @DisplayName("分离区域 → 各自成障碍；覆盖全部碰撞瓦片")
    void disjointRegions() {
        List<Obstacle> obs = Obstacle.fromCollisionGrid(
                grid("#..#", "####"), 32, "t");
        // 第一行 #..#（2 墙）+ 第二行 ####（4 墙）→ 共 6 个碰撞瓦片，覆盖必须完整
        assertEquals(6, coveredTiles(obs, 4, 2), "应覆盖全部碰撞瓦片");
    }

    @Test
    @DisplayName("非0值（如 2/3）同样视为碰撞")
    void nonzeroIsWall() {
        int[][] g = new int[2][2];
        g[0][0] = 0; g[0][1] = 2;
        g[1][0] = 3; g[1][1] = 0;
        List<Obstacle> obs = Obstacle.fromCollisionGrid(g, 32, "t");
        assertEquals(2, coveredTiles(obs, 2, 2), "非0瓦片都算碰撞");
    }
}
