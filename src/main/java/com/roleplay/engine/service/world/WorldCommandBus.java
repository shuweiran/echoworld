package com.roleplay.engine.service.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界、按会话隔离、按命令 id 幂等的内存命令总线。
 *
 * <p>总线不执行命令；消费者必须再次检查 preconditions、权限和资源预算。</p>
 */
public final class WorldCommandBus {
    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();
    private final int capacityPerSession;
    private final int rememberedIds;

    public WorldCommandBus() {
        this(256, 2048);
    }

    public WorldCommandBus(int capacityPerSession, int rememberedIds) {
        if (capacityPerSession < 1 || rememberedIds < capacityPerSession) {
            throw new IllegalArgumentException("rememberedIds must be >= capacityPerSession >= 1");
        }
        this.capacityPerSession = capacityPerSession;
        this.rememberedIds = rememberedIds;
    }

    /** @return 首次成功入队为 true；重复 id 或队列已满为 false。 */
    public boolean offer(WorldCommand command) {
        if (command == null) return false;
        return channels.computeIfAbsent(command.sessionId(), ignored -> new Channel()).offer(command);
    }

    public Optional<WorldCommand> poll(String sessionId) {
        Channel channel = channels.get(sessionId);
        return channel == null ? Optional.empty() : channel.poll();
    }

    public List<WorldCommand> drain(String sessionId, int limit) {
        if (limit <= 0) return List.of();
        Channel channel = channels.get(sessionId);
        return channel == null ? List.of() : channel.drain(limit);
    }

    public int size(String sessionId) {
        Channel channel = channels.get(sessionId);
        return channel == null ? 0 : channel.size();
    }

    public Set<String> sessions() {
        return Set.copyOf(channels.keySet());
    }

    public void clearSession(String sessionId) {
        if (sessionId != null) channels.remove(sessionId);
    }

    private final class Channel {
        private final ArrayDeque<WorldCommand> queue = new ArrayDeque<>();
        private final LinkedHashSet<String> recentIds = new LinkedHashSet<>();

        synchronized boolean offer(WorldCommand command) {
            if (recentIds.contains(command.id()) || queue.size() >= capacityPerSession) return false;
            queue.addLast(command);
            recentIds.add(command.id());
            while (recentIds.size() > rememberedIds) {
                recentIds.remove(recentIds.iterator().next());
            }
            return true;
        }

        synchronized Optional<WorldCommand> poll() {
            return Optional.ofNullable(queue.pollFirst());
        }

        synchronized List<WorldCommand> drain(int limit) {
            List<WorldCommand> result = new ArrayList<>(Math.min(limit, queue.size()));
            while (result.size() < limit && !queue.isEmpty()) result.add(queue.removeFirst());
            return List.copyOf(result);
        }

        synchronized int size() {
            return queue.size();
        }
    }
}
