# Glossary

| 术语 | 含义 |
|---|---|
| Agent | 具有身份、状态、位置、目标和对话能力的运行时角色 |
| Spatial World | 后端权威维护的二维世界状态 |
| AgentState | Agent 在空间仿真中的坐标、速度、情绪、听觉等状态 |
| SpatialGrid | 用于邻域查询的空间网格 |
| Hearing | 声音经距离衰减和障碍判断后的可达关系 |
| Context Track | 空间关系映射成的信息可见性轨道 |
| MERGED | 近距离参与者共享完整会话上下文 |
| WEAK | 旁听者只接收模糊摘要 |
| ISOLATED | 隔离者不接收该会话信息 |
| Conversation Group | 一组正在共享会话生命周期的 Agent |
| SpeechGate | 在调用 LLM 前判断 Agent 是否应该发言的确定性门控 |
| TrackStrategy | 按 Track 为每个 Agent 构造不同上下文的会话策略 |
| Eavesdrop Summary | 给 `WEAK` 监听者的降精度旁听摘要 |
| World Director | 生成或维护高层目标，不直接改写物理世界规则 |
| Track Director | 基于空间、互动和秘密条件协调 Track 分配 |
| Orchestrator | 只协调 tick 调用顺序，不持有具体领域算法 |
| Authoritative backend | 坐标、碰撞、权限和状态以服务端为事实源 |
| Map Contract | 地图分层、碰撞、区域、出生点等 JSON 结构约定 |
| SSE | 服务端向前端单向推送实时事件的通道 |
| Scenario | 用于验证核心能力的应用场景，如自由互动、狼人杀、剧本杀 |

公开材料统一使用 **Context Track / Spatial Track**，中文说明使用“上下文轨道”或“空间轨道”，不使用容易误解为铁路的翻译。
