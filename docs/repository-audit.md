# Public Repository Audit — 2026-08-25

## 结论

EchoWorld 的真实工程能力明显超过普通 API Demo，但公开仓库此前被功能数量、内部台账和历史迭代痕迹稀释。公开展示应只围绕三条技术故事：

1. 空间如何形成 Agent 的认知边界；
2. SpeechGate 如何决定谁需要说话；
3. 确定性规则如何约束 LLM 的不确定性。

## 已确认的真实证据

- `SpatialTrackResolver`：三态空间上下文路由；
- `HearingSystem`：距离衰减、情绪音量、听觉范围和障碍遮挡；
- `SpeechGate`：不依赖 LLM 的确定性发言门控；
- `TrackStrategyTest`：完整/摘要/隔离上下文的泄漏断言；
- `MapContract` / `MapValidator`：地图结构与合法性边界；
- React/Phaser + REST/SSE：实时地图与后端权威状态接线；
- Maven/JUnit/Spring 测试与 GitHub Actions。

## 主要风险

- `ConversationManager`、`SimulationService`、`SimulationController` 体积过大；
- 公开与内部文档尚未物理分离，内部台账体量很大；
- 前端 `demo2` 等目录名暴露历史原型痕迹；
- 配置和源码中仍有大量批次编号注释；
- 缺少固定三角色公开 Demo、SpeechGate benchmark 和容量基线；
- 当前空间模型不是专业 GIS/遥感系统，不能在面试中夸大。

## 本轮处理边界

完成公开 README、架构、核心概念、测试、术语、Demo 规格和面试讲解；不移动历史文件、不重命名稳定包/配置、不一次性拆大类。

## 后续优先级

1. 三角色真实 Demo；
2. 信息泄漏 killer test；
3. SpeechGate benchmark；
4. 大类职责图与特征测试；
5. 小步拆分；
6. 前端主体验与 Developer Tools 隔离；
7. 公共/内部文档物理迁移。
