# Context Routing: MERGED / WEAK / ISOLATED

Context Track 是 EchoWorld 最核心的领域模型：空间关系不仅决定“看见谁”，还决定 Agent 在生成下一次行为时能获得什么信息。

## 输入与输出

`SpatialTrackResolver` 的主要输入是：

- Agent 二维坐标；
- `conversationDistance`，默认 70 px，可配置；
- 每个监听者自己的 `hearRange`；
- 私密房间成员边界。

`HearingSystem` 进一步使用：

- 声源情绪对应的音量；
- 距离平方衰减；
- 说话者与监听者的听觉范围；
- `blocksSound` 障碍的线段相交结果。

输出是每个 Agent 对应的 `TrackAssignment`：Track 类型、可见 Agent 和上下文说明。

## 当前状态转换规则

对监听者 `self` 与其他 Agent `other`：

```text
private-room boundary crossed
    → ISOLATED

distance(self, other) < conversationDistance
    → MERGED

conversationDistance <= distance < self.hearRange
    → WEAK

otherwise
    → ISOLATED
```

同一 Agent 同时满足多种关系时优先级为：

```text
MERGED > WEAK > ISOLATED
```

因此，一个 Agent 只要有近距离直接对话对象，就处于 `MERGED`；远处可听对象不会把它降为 `WEAK`。

## 三态的上下文语义

### MERGED

- 获得对话组完整历史；
- 可以成为当前发言者；
- 发言可以写回组历史。

### WEAK

- 不获得原始发言行；
- 只获得 `EavesdropSummarizer` 生成的模糊观察；
- 无 LLM 时使用规则摘要降级；
- 摘要按会话缓存，避免每轮重复生成。

### ISOLATED

- 不获得任何组对话内容；
- 只生成独白或独立行为上下文；
- 输出不能写入该对话组历史。

## 为什么不是二态

“可听见”与“参与对话”不是同一件事。如果只有 visible/hidden 两态：

- 把旁听者算作 visible，会泄漏完整秘密；
- 把旁听者算作 hidden，又无法表达空间接近带来的渐进感知。

`WEAK` 保留“知道有人在谈话，但不知道完整内容”的中间状态，使空间移动能连续改变认知边界。

## Hearing 与 Track 的边界

`HearingSystem` 回答物理问题：“声音是否能到达监听者？”

`SpatialTrackResolver` 回答信息问题：“监听者获得完整、摘要还是零上下文？”

`ConversationManager` 和广播系统应复用 `HearingSystem`，而不是各自硬编码距离。Track 策略只消费分配结果，不重新计算空间关系。

## 可验证不变量

1. 私密房间内外永远 `ISOLATED`；
2. `WEAK` 上下文不含原始秘密短语；
3. `ISOLATED` 上下文不含对话主题或原文；
4. `ISOLATED` 输出不写回群聊历史；
5. 隔音障碍存在时，听觉候选关系消失；
6. 距离从远到近时，状态按 `ISOLATED → WEAK → MERGED` 变化。

代码与测试：

- [`SpatialTrackResolver`](../../src/main/java/com/roleplay/engine/simulation/track/SpatialTrackResolver.java)
- [`HearingSystem`](../../src/main/java/com/roleplay/engine/simulation/HearingSystem.java)
- [`TrackStrategy`](../../src/main/java/com/roleplay/engine/simulation/conversation/TrackStrategy.java)
- [`SpatialTrackResolverTest`](../../src/test/java/com/roleplay/engine/simulation/track/SpatialTrackResolverTest.java)
- [`TrackStrategyTest`](../../src/test/java/com/roleplay/engine/simulation/conversation/TrackStrategyTest.java)
- [`HearingSystemObstacleTest`](../../src/test/java/com/roleplay/engine/simulation/HearingSystemObstacleTest.java)
