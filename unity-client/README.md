# EchoWorld Unity 6 Client

EchoWorld V2 的 Unity 6 正式客户端最小骨架。它只消费 Java 权威世界的复制投影，并发送 `/ws/world` 当前明确支持的复制控制消息。客户端不运行服务器规则，不把 Unity Transform/NavMesh/Animator 结果写回世界，也不包含私有模型或二进制资产。

## 工程版本

- Unity `6000.3.23f1`
- Addressables `2.9.1`
- AI Navigation `2.0.14`，仅用于 authoring/预览/契约验证，不是权威导航
- Newtonsoft JSON `3.2.2`
- Unity Test Framework `1.6.0`

## 运行

1. 用 Unity Hub 的 Unity 6000.3.23f1 打开本目录。
2. 等待 Package Manager 恢复依赖；不要提交 `Library/`、`Temp/`、`Obj/`、`Logs/` 或 IDE 生成文件。
3. 打开 `Assets/Scenes/Bootstrap.unity` 并进入 Play Mode。运行时会自动建立客户端根对象、相机、灯光和 primitive 资产降级。
4. 默认不自动连接。需要连接已运行的 Java 服务时设置：

```text
ECHOWORLD_WS_URL=ws://127.0.0.1:8000/ws/world
ECHOWORLD_WS_AUTO_CONNECT=1
ECHOWORLD_CLIENT_ID=<本客户端稳定 ID；未设置则本次运行随机生成>
```

连接打开后 `WorldReplicationCommandSender` 先发送 `hello`，服务器随后发送 sequence 0 的 `full_snapshot`，后续发送连续的 `replication_frame`。

当前 `/ws/world` handler 只接受 `hello`、`interest`、`ack`、`replay`。本骨架不会在这个 socket 上发送移动或交互消息，因为它们会被当前 Java handler 以 `UNKNOWN_MESSAGE` 拒绝。将来接入玩家 Gameplay Command adapter 时，应使用服务端明确公开的命令端点/DTO，并继续遵守“只发意图，不发坐标或执行结果”。

## 结构

```text
Bootstrap
├─ Networking
│  ├─ IWebSocketTransport / ClientWebSocketTransport
│  ├─ EchoWorldClient
│  └─ ProtocolCodec + Java 对齐 DTO
├─ Commands
│  └─ WorldReplicationCommandSender (hello/interest/ack/replay)
├─ Replica
│  ├─ WorldReplica
│  └─ WorldReplicaHost
├─ Assets
│  ├─ IAssetPresentationResolver
│  └─ AddressablesAssetResolver
└─ Presentation
   ├─ ReplicaPresentationProjection
   ├─ WorldPresentationController / EntityView
   ├─ TransformPresentationAdapter
   └─ LocomotionPresenter
```

## 当前 Java WebSocket 契约

WebSocket endpoint：`/ws/world`。

所有消息外层只有 `type` 和 `payload`：

```json
{
  "type": "replication_frame",
  "payload": {}
}
```

`protocolVersion` 不在 envelope 上，而在 `hello`、`FullSnapshot` 和 `ReplicationFrame` payload 中。当前 `ReplicationProtocol.CURRENT_VERSION` 为 `1`。

### 客户端到服务器

`hello` 必须是第一条消息：

```json
{
  "type": "hello",
  "payload": {
    "clientId": "unity-client-01",
    "protocolVersion": 1,
    "focusCell": { "zoneId": "world", "floorId": "ground", "x": 0, "z": 0 },
    "radiusCells": 2,
    "narrativeSubscriptions": []
  }
}
```

- `interest` payload：`focusCell`（可空）、`radiusCells`（最小 0）、`narrativeSubscriptions[]`。
- `ack` payload：`{ "sequence": N }`。服务器回复 `ack_result`，payload 为 `status/highestAcknowledgedSequence/latestSequence`。
- `replay` payload：`{ "sequence": N }`，含义是“重放 N 之后的连续帧”。历史已淘汰时服务器发送最近的 `full_snapshot`。
- 未知 type 的错误是 `{ "type": "error", "code": "UNKNOWN_MESSAGE" }`，没有 payload；Codec 明确允许这一形状。

### 服务器到客户端

`full_snapshot` payload：

- `protocolVersion`
- `sequence`
- `serverTick`
- `serverTimeEpochMillis`
- `entities[]`
- `events[]`

`replication_frame` payload：

- `protocolVersion`
- `sequence`
- `serverTick`
- `serverTimeEpochMillis`
- `creates[]`，每项形如 `{ "entity": ReplicaEntity }`
- `updates[]`，每项形如 `{ "entity": ReplicaEntity }`
- `removes[]`，每项为 `entityId/revision/reason`
- `events[]`

`ReplicaEntity` 精确字段为 `entityId/entityType/revision/cell/ownerClientId/perceptionScope/narrativeTags/state`。`ReplicationEvent` 精确字段为 `eventId/eventType/serverTick/cell/globalInterest/perceptionScope/narrativeTags/payload`。

`WorldReplica` 首次只接受 `full_snapshot`。之后仅原子应用 `LastSequence + 1`：旧帧幂等忽略；缺帧或未知实体 update 不会部分修改副本，并通过 `replay` 请求恢复。成功应用一帧后发送 `ack`。协议不兼容时不自动 replay，因为 handler 只支持版本 1，必须更新客户端或服务器。

## Replica 与表现

`WorldReplica` 保存 `ReplicaEntity` 的防御性副本，只是网络投影，不是存档或权威状态。`ReplicaPresentationProjection` 把当前 Java `state` 映射为 Unity 表现：

- 优先读取 `state.transform.position/rotation` 的 V2 3D 结构；
- 兼容当前 `state.x/state.y`，映射为 Unity X/Z 地面坐标；
- `vx/vy` 映射为 X/Z 表现速度；
- `locomotionState/stance/actionType/actionPhase` 只驱动 Animator 参数。

`TransformPresentationAdapter` 只平滑复制目标。`LocomotionPresenter` 强制 `Animator.applyRootMotion = false`，可写 `Speed/MoveX/MoveZ/Locomotion/ActionCode/ActionPhase`。动画完成、root motion、碰撞和本地 NavMeshAgent 均不能提交世界结果。

## assetId 与 Addressables

服务端静态世界定义将来只保存稳定 `assetId`，不得保存 Unity Scene、Prefab 路径、GUID 或 Addressables address。当前 Java `ReplicaEntity` 没有顶层 `assetId`；客户端仅在 `state.assetId` 存在时解析它。

默认把 `assetId` 本身作为 Addressables address（例如 address=`chair.wood.03`）；也可在 `AddressablesAssetResolver` 的可序列化 catalog 中显式映射到 `AssetReferenceGameObject`。Prefab 可位于本地或远程 catalog。未提供 `assetId`、地址未登记、加载失败或私有资产缺失时，客户端使用 Capsule（Agent/Player）或 Cube（其他实体）降级，因此公开工程不依赖仓库外资产。

Prefab 只能定义 Mesh、材质、Animator、音效和表现组件。门能否打开、椅子能否坐、物品归属等事实仍由 Java WorldObject/Affordance/Action 决定。

## 权威边界

Unity 不得：

- 提交坐标、门状态、库存、关系值、任务阶段或 Action 成功结果；
- 把 root motion、碰撞、NavMeshAgent、动画完成或相机位置写回世界；
- 从未复制的信息推断私密实体或事件；
- 把 `WorldReplica` 当成断线后的权威状态；
- 在 Prefab/Animator 中编码 Affordance 最终判定。

Unity 可以：

- 插值、LOD、剔除、对象池、Addressables streaming 和动画混合；
- 更新 interest 提示，但该提示不能授予感知权限；
- 对成功帧 ACK，对 sequence 缺口请求 replay；
- 在资产缺失时使用纯表现 fallback。

## 测试与静态核查

本机 Unity 6000.3.23f1 路径如下；Unity Test Framework 会在测试结束后自行退出，命令中不要额外传 `-quit`：

```powershell
& 'D:\Unity\Editors\6000.3.23f1\Editor\Unity.exe' -batchmode -nographics -projectPath 'D:\echoworld\unity-client' `
  -runTests -testPlatform EditMode `
  -testResults 'D:\echoworld\unity-client\EditModeResults.xml'

& 'D:\Unity\Editors\6000.3.23f1\Editor\Unity.exe' -batchmode -nographics -projectPath 'D:\echoworld\unity-client' `
  -runTests -testPlatform PlayMode `
  -testResults 'D:\echoworld\unity-client\PlayModeResults.xml'
```

未具备 Editor 或许可证时，可先运行静态核查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Tools\verify-static.ps1
```

静态脚本验证工程文件和 manifest、Unity 6/必需包、`/ws/world`、协议版本 1、Java 字段/包装形状、`hello/interest/ack/replay`、root motion 关闭、命令层不写 Transform、无旧 endpoint/自拟字段，以及生成目录/二进制资产未被 Git 跟踪。

截至 2026-08-31，工程已在 Unity 6000.3.23f1 完成导入和脚本编译；EchoWorld 自有 EditMode **13/13** 通过（XML 总计 14/14，含 Addressables 包测试桩 1 项），PlayMode **1/1** 通过。PlayMode 使用真实 `ClientWebSocketTransport` 和最小 RFC 6455 环回服务器，覆盖 `hello → full_snapshot → WorldReplica → ACK`。

Java `/ws/world` 已由 JDK WebSocket 客户端在 Spring RANDOM_PORT 环境验证 `hello/full_snapshot/ack_result/UNKNOWN_MESSAGE`。后续仍应增加 Unity 客户端直连该 Java 测试实例、interest 信息泄漏、replay/断线重连、场景切换、Addressables 远程 catalog 和 50/100/200 View 性能基线。
