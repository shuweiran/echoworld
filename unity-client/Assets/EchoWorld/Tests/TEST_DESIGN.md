# Unity 客户端纯逻辑测试设计

本目录的 EditMode 测试不需要运行 EchoWorld 服务器，也不依赖私有 Prefab。可在 Unity Test Runner 中直接执行。

## 已实现

- `WorldReplicaTests`：sequence 0 full snapshot、Java wrapper 形状的 create/update/remove、乱序/丢帧、未知实体更新、重复帧幂等、返回值防御性复制。
- `ProtocolCodecTests`：外层 `{type,payload}`、hello、Java replication fixture、无 payload error、非法 JSON。
- `TransformPresentationAdapterTests`：首帧建立基线、后续平滑、DTO 不被表现层回写。

## 接入服务端协议后的契约用例

1. 用 Java 端固定 JSON fixture 驱动 C# 解码，逐字段比较 `protocolVersion/sequence/serverTick/serverTimeEpochMillis`。
2. 模拟丢失 N+1、先收到 N+2，确认客户端不部分应用 N+2，且按最后成功 sequence 发送 replay。
3. full snapshot 恢复后再接 N+1 delta，确认旧实体清空、sequence 重新连续。
4. 未进入 interest 的私密实体/事件 fixture 不应出现在客户端帧中；该项以服务端 fixture 为主，Unity 只验证未创建 View。
5. `assetId` 未登记时必须生成 primitive fallback；登记时实例化 Addressable，但二者均不得改变 replica。
6. Animator fixture 验证 `applyRootMotion == false`，Locomotion/ActionPhase 只写表现参数。

PlayMode/端到端测试应增加：`/ws/world` hello、interest、ACK/replay、断线重连、场景切换、Addressables 远程 catalog，以及 50/100/200 个 View 的帧时间和 Animator culling 基线。Gameplay movement/interaction 必须等服务端公开对应 handler 契约后再接。
