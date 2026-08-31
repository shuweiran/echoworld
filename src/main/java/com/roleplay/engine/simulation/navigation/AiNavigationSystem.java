package com.roleplay.engine.simulation.navigation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Obstacle;
import com.roleplay.engine.simulation.spatial.ControlAuthority;
import com.roleplay.engine.simulation.spatial.NavLocation;
import com.roleplay.engine.simulation.spatial.Vec3;

import java.util.Collection;
import java.util.List;

/** Plans only autonomous AI targets. Player-controlled entities are a hard no-op. */
public final class AiNavigationSystem {
    private final NavigationService navigation;
    private final double worldWidth;
    private final double worldHeight;
    private volatile List<Obstacle> obstacles = List.of();

    public AiNavigationSystem(NavigationService navigation, double worldWidth, double worldHeight) {
        this.navigation = navigation;
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
    }

    public void setObstacles(List<Obstacle> value) { obstacles = value == null ? List.of() : List.copyOf(value); }

    public void update(Collection<AgentState> agents) {
        for (AgentState agent : agents) {
            if (agent.isPlayerControlled() || agent.isManualTarget() || !agent.isHasTarget()
                    || agent.hasNavigationPlan()) continue;
            Vec3 goal = new Vec3(agent.getTargetX(), agent.getSpatial().transform().position().y(), agent.getTargetY());
            PathRequest request = new PathRequest(agent.getAgentName(), ControlAuthority.AI_AUTONOMOUS,
                    agent.getSpatial().navLocation(), NavLocation.ground(goal), NavProfile.humanoid(),
                    worldWidth, worldHeight, obstacles);
            PathPlan plan = navigation.plan(request);
            if (plan.status() == PathPlan.Status.READY) {
                agent.setNavigationPath(plan.steps().stream()
                        .filter(step -> step.type() == PathStep.Type.WALK)
                        .map(step -> new double[]{step.target().x(), step.target().z()})
                        .toList());
            } else {
                agent.setNavigationPath(List.of());
                agent.setVx(0.0);
                agent.setVy(0.0);
            }
        }
    }
}
