package com.roleplay.engine.interrupt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 世界事件总线（需求文档第八条 §十一：event/WorldEventBus）。
 *
 * <p>2D 世界事件驱动的发布/订阅中枢：
 *
 * <pre>
 *    EventBus
 *      ↓
 *  InterruptManager
 *      ↓
 *   Agent Task → LLM
 * </pre>
 *
 * <p>发布方（TrackDirector / RouterService / 玩家操作）→ {@link #publish(GameEvent)}
 * → 订阅方（InterruptManager 等）同步收到事件并决定是否取消任务。
 *
 * <p>线程安全：{@link CopyOnWriteArrayList} 监听器 + 同步分发（单监听器异常
 * 不影响其他监听器，避免"一颗老鼠屎坏一锅粥"）。
 */
@Component
public class WorldEventBus {

    private static final Logger log = LoggerFactory.getLogger(WorldEventBus.class);

    /** 全局监听器（收所有事件）。 */
    private final CopyOnWriteArrayList<Consumer<GameEvent>> globalListeners = new CopyOnWriteArrayList<>();
    /** 按事件类型过滤的监听器。 */
    private final Map<String, CopyOnWriteArrayList<Consumer<GameEvent>>> typedListeners = new ConcurrentHashMap<>();

    /** 订阅全部事件。 */
    public void subscribe(Consumer<GameEvent> listener) {
        globalListeners.add(listener);
    }

    /** 订阅指定类型事件（如 TRACK_CHANGED）。 */
    public void subscribe(String type, Consumer<GameEvent> listener) {
        typedListeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void unsubscribe(Consumer<GameEvent> listener) {
        globalListeners.remove(listener);
        typedListeners.values().forEach(l -> l.remove(listener));
    }

    /**
     * 发布事件（同步分发）。单个监听器抛异常只记日志，不影响其余监听器。
     */
    public void publish(GameEvent event) {
        if (event == null) return;
        for (Consumer<GameEvent> l : globalListeners) {
            dispatch(l, event);
        }
        CopyOnWriteArrayList<Consumer<GameEvent>> typed = typedListeners.get(event.getType());
        if (typed != null) {
            for (Consumer<GameEvent> l : typed) {
                dispatch(l, event);
            }
        }
        log.debug("Event published: {}", event);
    }

    private void dispatch(Consumer<GameEvent> listener, GameEvent event) {
        try {
            listener.accept(event);
        } catch (Exception e) {
            log.warn("Event listener failed for {}: {}", event.getType(), e.getMessage());
        }
    }

    public int globalListenerCount() { return globalListeners.size(); }
    public int typedListenerCount() { return typedListeners.values().stream().mapToInt(CopyOnWriteArrayList::size).sum(); }
}
