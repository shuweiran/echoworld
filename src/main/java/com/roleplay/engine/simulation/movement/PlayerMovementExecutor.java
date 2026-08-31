package com.roleplay.engine.simulation.movement;

import com.roleplay.engine.simulation.AgentState;

/**
 * Executes explicit player input only. It has no dependency on AI goals,
 * navigation, flocking, schedules or conversation orchestration.
 */
public final class PlayerMovementExecutor {
    public void update(AgentState player, double dt) {
        if (!player.isPlayerControlled()) {
            throw new IllegalArgumentException("PlayerMovementExecutor requires PLAYER_INPUT authority");
        }
        if (!player.isManualTarget()) {
            if (player.isHasTarget()) player.clearTarget();
            stop(player);
            return;
        }
        if (player.hasManualDirection()) {
            applyDirection(player, player.getManualDirectionX(), player.getManualDirectionY(), dt);
            return;
        }
        if (!player.isHasTarget()) {
            stop(player);
            return;
        }
        double dx = player.getTargetX() - player.getX();
        double dy = player.getTargetY() - player.getY();
        double distance = Math.hypot(dx, dy);
        if (distance < 5.0) {
            player.clearTarget();
            stop(player);
            return;
        }
        applyDirection(player, dx / distance, dy / distance, dt);
    }

    private void applyDirection(AgentState player, double dx, double dy, double dt) {
        double speed = Math.max(0.0, player.getMoveSpeed());
        player.setVx(dx * speed);
        player.setVy(dy * speed);
        player.setX(player.getX() + player.getVx() * dt);
        player.setY(player.getY() + player.getVy() * dt);
    }

    private void stop(AgentState player) {
        player.setVx(0.0);
        player.setVy(0.0);
    }
}
