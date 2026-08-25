# Reproducible Evaluations

## SpeechGate fixed workload

`SpeechGateEvaluationTest` 使用固定、无随机的 100 轮工作负载：5 个具有不同健谈度和目标优先级的 Agent；每 3 轮有人类发言，每 10 轮点名 Charlie，每 20 轮由 Diana 冷场破冰。

2026-08-25 本机结果：

| 指标 | 结果 |
|---|---:|
| 模拟轮数 | 100 |
| 候选 Agent | 5 |
| 无门控生成候选 | 500 |
| SpeechGate 后生成候选 | 182 |
| 候选调用削减 | 63.6% |

运行：

```bash
mvn -Dtest=SpeechGateEvaluationTest test
```

这不是生产 token 节省率，也不代表所有对话负载。它只证明在一个公开、可复现的固定输入下，当前门控规则会保留必要发言，同时减少进入语言生成阶段的候选数。真实 token、延迟和质量对比仍需在固定模型、提示和硬件条件下单独评测。

测试源码：[`SpeechGateEvaluationTest`](../src/test/java/com/roleplay/engine/simulation/conversation/SpeechGateEvaluationTest.java)
