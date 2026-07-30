package com.roleplay.engine.simulation;

import java.util.Collection;
import java.util.List;

public class MovementSystem {

    private final double worldWidth;
    private final double worldHeight;
    private final double margin;
    private final SpatialGrid spatialGrid;

    public static final double SEPARATION_WEIGHT = 80.0;
    public static final double COHESION_WEIGHT = 30.0;
    public static final double ALIGNMENT_WEIGHT = 20.0;
    public static final double TARGET_WEIGHT = 50.0;
    public static final double WANDER_STRENGTH = 15.0;
    public static final double MIN_SEPARATION = 35.0;
    public static final double OBSTACLE_REPULSION = 200.0;
    public static final double OBSTACLE_RANGE = 80.0;

    private volatile List<Obstacle> obstacles = List.of();

    public MovementSystem(double worldWidth, double worldHeight, double margin, SpatialGrid spatialGrid) {
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.margin = margin;
        this.spatialGrid = spatialGrid;
    }

    public void setObstacles(List<Obstacle> obs) { this.obstacles = obs; }

    public void update(Collection<AgentState> agents, double dt) {
        spatialGrid.rebuild(agents);

        for (AgentState self : agents) {
            if (self.isInConversation()) {
                self.setVx(0);
                self.setVy(0);
                if (self.isHasTarget()) {
                    self.clearTarget();
                }
                continue;
            }

            double[] force = computeForce(self);
            applyForce(self, force, dt);
            clampToWorld(self);
        }
    }

    private double[] computeForce(AgentState self) {
        double perception = Math.max(self.getHearRange() * 2.5, 200);
        List<AgentState> neighbors = spatialGrid.queryNearby(self, perception);

        double sepX = 0, sepY = 0;
        double cohX = 0, cohY = 0;
        double aliX = 0, aliY = 0;
        int sepCount = 0, cohCount = 0, aliCount = 0;

        for (AgentState other : neighbors) {
            double dist = self.distanceTo(other);
            if (dist < 0.01) continue;

            double dx = other.getX() - self.getX();
            double dy = other.getY() - self.getY();

            if (dist < MIN_SEPARATION) {
                double strength = (MIN_SEPARATION - dist) / MIN_SEPARATION;
                sepX -= (dx / dist) * strength;
                sepY -= (dy / dist) * strength;
                sepCount++;
            }

            if (dist < perception * 0.6) {
                cohX += other.getX();
                cohY += other.getY();
                cohCount++;
            }

            if (dist < perception * 0.4) {
                aliX += other.getVx();
                aliY += other.getVy();
                aliCount++;
            }
        }

        double forceX = 0, forceY = 0;

        if (sepCount > 0) {
            forceX += sepX * SEPARATION_WEIGHT / sepCount;
            forceY += sepY * SEPARATION_WEIGHT / sepCount;
        }
        if (cohCount > 0) {
            forceX += ((cohX / cohCount) - self.getX()) * COHESION_WEIGHT / perception;
            forceY += ((cohY / cohCount) - self.getY()) * COHESION_WEIGHT / perception;
        }
        if (aliCount > 0) {
            forceX += (aliX / aliCount - self.getVx()) * ALIGNMENT_WEIGHT / perception;
            forceY += (aliY / aliCount - self.getVy()) * ALIGNMENT_WEIGHT / perception;
        }

        if (self.isHasTarget()) {
            double tdx = self.getTargetX() - self.getX();
            double tdy = self.getTargetY() - self.getY();
            double tdist = Math.sqrt(tdx * tdx + tdy * tdy);
            if (tdist < 5) {
                self.clearTarget();
            } else {
                double nx = tdx / tdist;
                double ny = tdy / tdist;
                boolean blocked = false;
                for (Obstacle obs : obstacles) {
                    if (obs.intersectsLine(self.getX(), self.getY(), self.getTargetX(), self.getTargetY())) {
                        blocked = true;
                        forceX += ny * 100;
                        forceY += -nx * 100;
                        break;
                    }
                }
                if (!blocked) {
                    forceX += nx * TARGET_WEIGHT;
                    forceY += ny * TARGET_WEIGHT;
                }
            }
        }

        if (Math.abs(forceX) < 0.5 && Math.abs(forceY) < 0.5 && !self.isHasTarget()) {
            forceX += (Math.random() - 0.5) * WANDER_STRENGTH;
            forceY += (Math.random() - 0.5) * WANDER_STRENGTH;
        }

        for (Obstacle obs : obstacles) {
            double ox = obs.getCenterX();
            double oy = obs.getCenterY();
            double dx = self.getX() - ox;
            double dy = self.getY() - oy;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double r = OBSTACLE_RANGE + Math.max(obs.getWidth(), obs.getHeight()) / 2;
            if (dist < r && dist > 0.01) {
                double s = (r - dist) / r;
                forceX += (dx / dist) * s * OBSTACLE_REPULSION;
                forceY += (dy / dist) * s * OBSTACLE_REPULSION;
            }
        }

        return new double[]{forceX, forceY};
    }

    private void applyForce(AgentState self, double[] force, double dt) {
        double speed = self.getMoveSpeed();

        double damping = switch (self.getEmotion()) {
            case EXCITED -> 0.96;
            case ANGRY -> 0.92;
            case SAD, BORED -> 0.82;
            case SHY -> 0.86;
            case THOUGHTFUL -> 0.84;
            default -> 0.90;
        };

        double targetVx = Math.max(-speed, Math.min(speed, self.getVx() + force[0] * dt));
        double targetVy = Math.max(-speed, Math.min(speed, self.getVy() + force[1] * dt));

        self.setVx(targetVx * damping);
        self.setVy(targetVy * damping);

        self.setX(self.getX() + self.getVx() * dt);
        self.setY(self.getY() + self.getVy() * dt);
    }

    private void clampToWorld(AgentState self) {
        double m = margin + 5;
        if (self.getX() < m) { self.setX(m); self.setVx(Math.abs(self.getVx()) * 0.3); }
        if (self.getX() > worldWidth - m) { self.setX(worldWidth - m); self.setVx(-Math.abs(self.getVx()) * 0.3); }
        if (self.getY() < m) { self.setY(m); self.setVy(Math.abs(self.getVy()) * 0.3); }
        if (self.getY() > worldHeight - m) { self.setY(worldHeight - m); self.setVy(-Math.abs(self.getVy()) * 0.3); }

        for (Obstacle obs : obstacles) {
            if (!obs.intersectsCircle(self.getX(), self.getY(), 12)) continue;
            double ox = obs.getCenterX();
            double oy = obs.getCenterY();
            double dx = self.getX() - ox;
            double dy = self.getY() - oy;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 0.01) { dx = 1; dy = 0; dist = 1; }
            double pushDist = Math.max(obs.getWidth(), obs.getHeight()) / 2 + 15;
            self.setX(ox + (dx / dist) * pushDist);
            self.setY(oy + (dy / dist) * pushDist);
        }
    }
}
