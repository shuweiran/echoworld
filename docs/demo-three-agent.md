# Three-Agent Spatial Context Demo

这个演示用于在 20–30 秒内验证 EchoWorld 的三个核心主张：二维空间参与规则计算、听觉会改变上下文、不同 Agent 获得的信息不同。

## 固定场景

- Alice 与 Bob 在房间内近距离交谈；
- 对话秘密短语：`闸门密钥在北侧控制柜`；
- Charlie 初始位于听觉范围外；
- 地图中显示 Agent 坐标、听觉范围和当前 Track。

## 演示时间线

| 时间 | Charlie 位置 | Track | 可获得信息 |
|---:|---|---|---|
| 0–5s | 听觉范围外 | `ISOLATED` | 无对话信息 |
| 5–12s | 进入听觉范围 | `WEAK` | 只知道两人在讨论某个设施，不出现秘密短语 |
| 12–20s | 进入会话距离 | `MERGED` | 获得后续完整对话并可加入会话 |
| 20–25s | 隔音墙闭合 | `ISOLATED` | 即使几何距离近，也被声学障碍隔离 |

## 画面必须同时显示

```text
Alice   MERGED
Bob     MERGED
Charlie ISOLATED → WEAK → MERGED → ISOLATED
```

右侧上下文检查器分别展示：

- Alice/Bob：完整发言；
- Charlie/WEAK：`听见两人在讨论某个控制设施`；
- Charlie/ISOLATED：不出现对话主题；
- Charlie/MERGED：只从加入时刻开始获得允许的完整上下文。

## 验收条件

1. Track 变化来自真实后端 `SpatialTrackResolver` 结果，不由前端动画伪造；
2. `WEAK` 面板不得包含 `闸门密钥`、`北侧`、`控制柜`；
3. `ISOLATED` 面板不得包含会话主题；
4. 隔音墙使用 `HearingSystem` 的 `blocksSound` 判断；
5. 关闭真实 LLM 后仍能以固定文本完成演示；
6. 录制为 1080p、20–30 秒、无需旁白也能理解的 GIF/MP4。

## 当前状态

核心算法和泄漏断言已经存在；公开录屏与专用上下文检查器尚未完成。README 明确把它列为 Roadmap，而不把设计稿冒充成已完成 Demo。
