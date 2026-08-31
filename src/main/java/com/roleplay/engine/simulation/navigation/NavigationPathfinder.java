package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.Obstacle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic grid navigation used by the authoritative simulation.
 *
 * <p>The current MapContract is a ground-plane contract, so this is the first
 * 2.5D navigation layer: X/Z in Babylon correspond to X/Y here, while floor
 * remains an explicit future extension point. The renderer may smooth a path,
 * but it never owns the path or the collision decision.</p>
 */
public final class NavigationPathfinder {

    public static final double DEFAULT_CELL_SIZE = 32.0;
    private static final double DEFAULT_AGENT_RADIUS = 12.0;
    private static final int MAX_EXPANDED_NODES = 20_000;

    public record Point(double x, double y) { }

    private record Cell(int x, int y) { }
    private record OpenCell(Cell cell, double score) { }

    public List<Point> findPath(double startX, double startY,
                                double targetX, double targetY,
                                double worldWidth, double worldHeight,
                                List<Obstacle> obstacles) {
        return findPath(startX, startY, targetX, targetY, worldWidth, worldHeight,
                obstacles, DEFAULT_AGENT_RADIUS);
    }

    public List<Point> findPath(double startX, double startY,
                                double targetX, double targetY,
                                double worldWidth, double worldHeight,
                                List<Obstacle> obstacles,
                                double agentRadius) {
        if (worldWidth <= 0 || worldHeight <= 0) return List.of();
        if (!Double.isFinite(agentRadius) || agentRadius < 0) return List.of();
        double cellSize = DEFAULT_CELL_SIZE;
        int columns = Math.max(1, (int) Math.ceil(worldWidth / cellSize));
        int rows = Math.max(1, (int) Math.ceil(worldHeight / cellSize));
        boolean[][] blocked = buildBlocked(columns, rows, cellSize, worldWidth, worldHeight,
                obstacles, agentRadius);
        Cell start = nearestWalkable(toCell(startX, startY, cellSize), blocked);
        Cell goal = nearestWalkable(toCell(targetX, targetY, cellSize), blocked);
        if (start == null || goal == null) return List.of();
        if (start.equals(goal)) return List.of(new Point(targetX, targetY));

        Map<Cell, Cell> cameFrom = new HashMap<>();
        Map<Cell, Double> cost = new HashMap<>();
        PriorityQueue<OpenCell> open = new PriorityQueue<>(Comparator.comparingDouble(OpenCell::score));
        cost.put(start, 0.0);
        open.add(new OpenCell(start, heuristic(start, goal)));
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < MAX_EXPANDED_NODES) {
            Cell current = open.poll().cell();
            if (current.equals(goal)) return reconstruct(cameFrom, current, targetX, targetY, cellSize);
            for (int[] direction : DIRECTIONS) {
                Cell next = new Cell(current.x() + direction[0], current.y() + direction[1]);
                if (!inside(next, columns, rows) || blocked[next.y()][next.x()]) continue;
                double step = direction[0] != 0 && direction[1] != 0 ? Math.sqrt(2) : 1.0;
                double nextCost = cost.get(current) + step;
                if (nextCost < cost.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(next, current);
                    cost.put(next, nextCost);
                    open.add(new OpenCell(next, nextCost + heuristic(next, goal)));
                }
            }
        }
        return List.of();
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private boolean[][] buildBlocked(int columns, int rows, double cellSize,
                                     double worldWidth, double worldHeight,
                                     List<Obstacle> obstacles,
                                     double agentRadius) {
        boolean[][] blocked = new boolean[rows][columns];
        List<Obstacle> safeObstacles = obstacles == null ? List.of() : obstacles;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                double cx = Math.min(worldWidth - 1, (x + 0.5) * cellSize);
                double cy = Math.min(worldHeight - 1, (y + 0.5) * cellSize);
                for (Obstacle obstacle : safeObstacles) {
                    if (cx >= obstacle.getX() - agentRadius
                            && cx <= obstacle.getX() + obstacle.getWidth() + agentRadius
                            && cy >= obstacle.getY() - agentRadius
                            && cy <= obstacle.getY() + obstacle.getHeight() + agentRadius) {
                        blocked[y][x] = true;
                        break;
                    }
                }
            }
        }
        return blocked;
    }

    private Cell nearestWalkable(Cell origin, boolean[][] blocked) {
        if (inside(origin, blocked[0].length, blocked.length) && !blocked[origin.y()][origin.x()]) return origin;
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        boolean[][] seen = new boolean[blocked.length][blocked[0].length];
        queue.add(origin);
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (!inside(current, blocked[0].length, blocked.length) || seen[current.y()][current.x()]) continue;
            seen[current.y()][current.x()] = true;
            if (!blocked[current.y()][current.x()]) return current;
            for (int[] direction : DIRECTIONS) queue.addLast(new Cell(current.x() + direction[0], current.y() + direction[1]));
        }
        return null;
    }

    private List<Point> reconstruct(Map<Cell, Cell> cameFrom, Cell current,
                                     double targetX, double targetY, double cellSize) {
        List<Cell> cells = new ArrayList<>();
        cells.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            cells.add(current);
        }
        Collections.reverse(cells);
        List<Point> points = new ArrayList<>();
        for (Cell cell : cells) points.add(new Point((cell.x() + 0.5) * cellSize, (cell.y() + 0.5) * cellSize));
        if (!points.isEmpty()) points.set(points.size() - 1, new Point(targetX, targetY));
        return points;
    }

    private Cell toCell(double x, double y, double cellSize) {
        return new Cell((int) Math.floor(x / cellSize), (int) Math.floor(y / cellSize));
    }

    private boolean inside(Cell cell, int columns, int rows) {
        return cell.x() >= 0 && cell.x() < columns && cell.y() >= 0 && cell.y() < rows;
    }

    private double heuristic(Cell a, Cell b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
