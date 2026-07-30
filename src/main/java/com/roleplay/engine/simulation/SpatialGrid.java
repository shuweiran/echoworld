package com.roleplay.engine.simulation;

import java.util.*;

public class SpatialGrid {

    private final double worldWidth;
    private final double worldHeight;
    private final double cellSize;
    private final int cols;
    private final int rows;
    private final List<AgentState>[] cells;

    @SuppressWarnings("unchecked")
    public SpatialGrid(double worldWidth, double worldHeight, double cellSize) {
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.cellSize = cellSize;
        this.cols = (int) Math.ceil(worldWidth / cellSize) + 1;
        this.rows = (int) Math.ceil(worldHeight / cellSize) + 1;
        this.cells = new List[cols * rows];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new ArrayList<>();
        }
    }

    public void clear() {
        for (List<AgentState> cell : cells) {
            cell.clear();
        }
    }

    public void insert(AgentState state) {
        int cx = clampCol((int) (state.getX() / cellSize));
        int cy = clampRow((int) (state.getY() / cellSize));
        cells[cy * cols + cx].add(state);
    }

    public void rebuild(Collection<AgentState> states) {
        clear();
        for (AgentState s : states) {
            insert(s);
        }
    }

    public List<AgentState> queryNearby(double x, double y, double radius) {
        List<AgentState> result = new ArrayList<>();
        int minCx = clampCol((int) ((x - radius) / cellSize));
        int maxCx = clampCol((int) ((x + radius) / cellSize));
        int minCy = clampRow((int) ((y - radius) / cellSize));
        int maxCy = clampRow((int) ((y + radius) / cellSize));
        double r2 = radius * radius;

        for (int cy = minCy; cy <= maxCy; cy++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (AgentState s : cells[cy * cols + cx]) {
                    double dx = s.getX() - x;
                    double dy = s.getY() - y;
                    if (dx * dx + dy * dy <= r2) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }

    public List<AgentState> queryNearby(AgentState self, double radius) {
        List<AgentState> result = queryNearby(self.getX(), self.getY(), radius);
        result.remove(self);
        return result;
    }

    private int clampCol(int c) { return Math.max(0, Math.min(cols - 1, c)); }
    private int clampRow(int r) { return Math.max(0, Math.min(rows - 1, r)); }

    public int getCellCount() { return cells.length; }
    public int getOccupiedCells() {
        int count = 0;
        for (List<AgentState> cell : cells) {
            if (!cell.isEmpty()) count++;
        }
        return count;
    }
}
