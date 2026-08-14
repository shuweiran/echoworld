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
    /** P-0814-I：目标力 50→220（> 障碍斥力 OBSTACLE_REPULSION=200）——
     *  角色在墙前不再被 4 倍斥力推回抖动，能压过单个障碍的径向斥力走向目标；
     *  多障碍叠加仍可能卡位，由 blocked 沿墙绕行（TANGENT_SLIDE_WEIGHT）兜底。 */
    public static final double TARGET_WEIGHT = 220.0;
    /** P-0814-I：直线被障碍打断（blocked）时的切向滑行力——原硬编码 100，提升至 300，
     *  沿墙滑行而非原路弹回；配合目标力 30% 残余牵引，滑过墙角后目标力直接拉到目标。 */
    public static final double TANGENT_SLIDE_WEIGHT = 300.0;
    /** P-0814-I：blocked 时保留的目标力比例（0.3 = 30%），防止切向滑行时被斥力钉死原地。 */
    public static final double BLOCKED_TARGET_KEEP = 0.3;
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
                // P-0813-E：对话中的角色 —— AI 自主目标清掉并冻结速度（避免对话中乱跑）；
                // 玩家手动指定目标（/target 端点 manualTarget，如 2D 点击）保留且不冻结：
                // 玩家指令优先于对话站位，角色继续走向目标（对话结束/离开后同样继续执行）。
                if (self.isHasTarget() && !self.isManualTarget()) {
                    self.clearTarget();
                }
                if (self.isManualTarget()) {
                    double[] force = computeForce(self);
                    applyForce(self, force, dt);
                    clampToWorld(self);
                } else {
                    self.setVx(0);
                    self.setVy(0);
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
                        // P-0814-I：沿墙绕行——直线被障碍打断时改为「切向滑行」而非原路弹回：
                        // 切向力（300）主导滑行方向，保留 30% 目标力防止被径向斥力（200）钉死，
                        // 滑过墙角（视线不再被挡）后目标力（220）直接牵引到目标。
                        forceX += ny * TANGENT_SLIDE_WEIGHT;
                        forceY += -nx * TANGENT_SLIDE_WEIGHT;
                        forceX += nx * TARGET_WEIGHT * BLOCKED_TARGET_KEEP;
                        forceY += ny * TARGET_WEIGHT * BLOCKED_TARGET_KEEP;
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

    /** P-0813-E：角色碰撞半径（与 intersectsCircle 判定一致，避免半径/推挤两处口径漂移）。 */
    private static final double COLLISION_RADIUS = 12.0;
    /** P-0813-E：推挤后与障碍的净距（半径 + 2px 余量，防贴边抖动与重复推挤）。 */
    private static final double PUSH_CLEARANCE = COLLISION_RADIUS + 2.0;

    /**
     * P-0813-E：把角色约束在世界内并解决障碍穿透。
     *
     * <p>旧实现沿障碍中心径向推挤 pushDist=max(w,h)/2+15 —— LLM 地图贴边墙（如顶墙
     * (0,0,1000,30)）会把角色弹射到世界外（实测苏婉_2 停 (-14.9, 25.8)、林杰/林浩
     * (962/973, 614) y&gt;600）且下一 tick 被 clamp 回来再推出 → 永久卡死。新实现：
     * <ol>
     *   <li>角色中心在障碍内部 → 沿「最小穿透边」推出（顶墙 → 向下推出到墙底边外）；</li>
     *   <li>角色圆与障碍相交 → 沿最近点法线推出（余量 PUSH_CLEARANCE），不再径向弹射；</li>
     *   <li>推挤结束后无条件二次 clamp —— 任何情况下坐标 ∈ [0, worldW]×[0, worldH]（NPC 同享）。</li>
     * </ol>
     */
    private void clampToWorld(AgentState self) {
        double m = margin + 5;
        if (self.getX() < m) { self.setX(m); self.setVx(Math.abs(self.getVx()) * 0.3); }
        if (self.getX() > worldWidth - m) { self.setX(worldWidth - m); self.setVx(-Math.abs(self.getVx()) * 0.3); }
        if (self.getY() < m) { self.setY(m); self.setVy(Math.abs(self.getVy()) * 0.3); }
        if (self.getY() > worldHeight - m) { self.setY(worldHeight - m); self.setVy(-Math.abs(self.getVy()) * 0.3); }

        for (Obstacle obs : obstacles) {
            if (!obs.intersectsCircle(self.getX(), self.getY(), COLLISION_RADIUS)) continue;
            double cx = self.getX();
            double cy = self.getY();
            double ox = obs.getX();
            double oy = obs.getY();
            double ow = obs.getWidth();
            double oh = obs.getHeight();
            // 矩形上离角色中心最近的点
            double closestX = Math.max(ox, Math.min(cx, ox + ow));
            double closestY = Math.max(oy, Math.min(cy, oy + oh));
            double dx = cx - closestX;
            double dy = cy - closestY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 1e-6) {
                // 角色中心在障碍内部：沿最小穿透边推出（防贴边墙径向弹射到世界外）
                double left = cx - ox;
                double right = ox + ow - cx;
                double top = cy - oy;
                double bottom = oy + oh - cy;
                double minSide = Math.min(Math.min(left, right), Math.min(top, bottom));
                if (minSide == left) self.setX(ox - PUSH_CLEARANCE);
                else if (minSide == right) self.setX(ox + ow + PUSH_CLEARANCE);
                else if (minSide == top) self.setY(oy - PUSH_CLEARANCE);
                else self.setY(oy + oh + PUSH_CLEARANCE);
            } else if (dist < PUSH_CLEARANCE) {
                // 部分相交：沿最近点法线推出（余量 PUSH_CLEARANCE）
                double nx = dx / dist;
                double ny = dy / dist;
                self.setX(closestX + nx * PUSH_CLEARANCE);
                self.setY(closestY + ny * PUSH_CLEARANCE);
            }
        }

        // P-0813-E：推挤后无条件二次 clamp —— 任何情况下角色坐标都在世界边界内
        if (self.getX() < m) { self.setX(m); }
        if (self.getX() > worldWidth - m) { self.setX(worldWidth - m); }
        if (self.getY() < m) { self.setY(m); }
        if (self.getY() > worldHeight - m) { self.setY(worldHeight - m); }
    }
}
