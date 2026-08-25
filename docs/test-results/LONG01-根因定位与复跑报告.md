# LONG-01 首跑失败根因定位 + 3 次复跑报告

> ⚠️ 历史测试快照（2026-08-24）：文中的 `SessionManager` 已作为死代码删除；当前 LONG-01 结果与基线请读根目录 `TEST_STATUS.md`。

## 一、根因结论

**结论：测试脆弱（测试自身 mock 缺陷导致的确定性失败），非引擎确定性缺陷，非偶发竞态。**

首跑失败与复跑通过之间，**测试文件被静默修改**（14:28:48），修改内容恰好修复了锚点词丢失问题。因此 14:29 的"复跑通过"不代表引擎稳定，而是测试本身被改过。

## 二、证据链

### 证据 1：文件修改时间戳（铁证）
| 时间 | 事件 |
|---|---|
| 14:27:56 | 首跑失败（long01-run.log），锚点词断言失败于 `LongTextStabilityTest.java:127` |
| **14:28:48** | **LongTextStabilityTest.java 被修改**（文件 mtime） |
| 14:29:16 | 复跑通过（long01-run2.log），且日志 L19 明确输出 `[INFO] Recompiling the module because of changed source code` —— Maven 检测到源码变化强制重编译，直接证明两次运行用的是不同版本的测试代码 |

### 证据 2：断言行号位移与 mock 改动精确吻合
- 失败运行断言在 **127 行**（run1 日志 L19154 stacktrace）
- 当前文件锚点词断言在 **138 行**（ANCHOR_ASSERT_LINE=138）
- 位移 = +11 行，恰好等于当前 `callSimple` mock 的 12 行 `thenAnswer` 块（L61-L72）替换旧版 1 行 `thenReturn` 固定回复（12−1=+11 ✓）

### 证据 3：代码注释自证（当前文件 L62-L63）
```java
// 真实 LLM 会把用户输入转换为旁白（保留语义）。mock 回显“用户输入：”之后的文本，
// 使第 1 轮锚点词能进入持久化消息（贴近真实行为，用于内容不丢失验证）。
```
"**使第 1 轮锚点词能进入持久化消息**" —— 直白说明旧版 mock 回显固定 50 字回复时，锚点词根本进不了 USER 消息，断言必然失败。

### 证据 4：锚点词进入路径跟踪（代码级）
1. 第 1 轮请求体含锚点词（失败日志 L655 请求体已确认）✅
2. `RouterService.runRound` → `ArbiterService.processUserInput` → `llmClient.callSimple(prompt,120)`
3. **旧版 mock 返回固定 50 字回复** → narration 无锚点词 → `memory.addMessage(USER消息)` 内容无锚点词
4. 断言 `session.getMessages().anyMatch(m -> m.getContent().contains("锚点词XYZ-0001"))` → **必然 false** → 确定性失败
5. **新版 mock 回显"用户输入："后文本（截断 120 字）** → 锚点词在开头，进入 USER 消息 → 必然通过

### 证据 5：引擎侧无消息删除路径（排除引擎缺陷）
- `MemoryStore`/`Session`：messages 只增不减（`Session.addMessage` 仅 append）
- `SessionManager.prune()`（>500 条清头部）为**死代码**：全仓库无任何调用点（grep `\.prune\(` 仅定义处），`autosave` 亦无调用
- `rollbackToRound` 才调 `setMessages`，测试未调用；`snapshotRound` 只复制不删除
- 压缩链（`Compressor`）只向 `compressedChunks` **追加** chunk，不触碰 messages
- 失败日志全 500 轮 HTTP 200、无 error、无 "Pruned" 日志 —— 引擎路径无异常

### 证据 6：失败日志中无任何异常/清除痕迹
- status 统计：500×"1 agents in Xms" + 1×initialized，全部 200 OK
- 无 WARN/ERROR（除 JDK/Mockito 无害提示），无 "Resolved Exception" 实际异常（Type 均为 null）
- 无 OOM（堆采样 43~67MB 波动正常）

## 三、3 次复跑结果（当前版本测试，14:47-14:49 连续执行）

| 次数 | 结果 | 耗时 | P50 | P95 | Max | 堆增长(末段min vs 中段min) | 锚点词命中 | 日志 |
|---|---|---|---|---|---|---|---|---|
| 1 | ✅ PASS | 16.11s | 4ms | 6ms | 36ms | +16.4% | ✅ | long01-rerun-1.log |
| 2 | ✅ PASS | 14.29s | 4ms | 7ms | 40ms | +18.8% | ✅ | long01-rerun-2.log |
| 3 | ✅ PASS | 11.03s | 4ms | 7ms | 32ms | -7.4% | ✅ | long01-rerun-3.log |

- 每次均输出 `LONG-01 PASS：无 OOM、无卡死、锚点词可检索、压缩链生效`（该行在锚点词断言、摘要断言之后才打印）
- 3 次均 `Tests run: 1, Failures: 0, Errors: 0` + `BUILD SUCCESS`
- 堆增长均 < 30% 阈值（第 3 次为负值，GC 噪声，引擎无内存泄漏迹象）

## 四、修复/加固建议

1. **测试修复已存在但未留痕**：当前 `callSimple` mock 的回显逻辑是正确的，但本次 14:28:48 的修改无 git 提交记录（该文件是 untracked），建议立即 `git add` 并提交，避免"隐性修复"再次发生。
2. **断言加固**：锚点词断言除检查 `session.getMessages()` 外，建议同时断言 `summaryCtx`（压缩摘要）也能检索锚点词——当前 mock 摘要不含锚点词，若未来真实 LLM 摘要化，需要独立用例验证摘要保留。
3. **mock 语义对齐真实行为**：`callSimple` 回显用户输入是贴近真实的（用户输入→旁白保留语义），建议在注释中写明"此回显是锚点词进入消息流的必要条件，勿改回固定回复"。
4. **流程加固**：任何对稳定性测试文件的修改必须走 git diff + 审查，防止"失败→改测试→通过"的假阳性流程重演。
5. **可选**：给 `SessionManager.prune()` 加调用审计或删除死代码，避免未来被误接线导致真实内容丢失。

## 五、结论一句话
**首跑失败是测试自身 mock 缺陷（callSimple 未回显用户输入导致锚点词未进入消息流）造成的确定性失败；复跑通过是因为测试文件在两次运行之间被修改（14:28:48）修复了该 mock。引擎（MemoryStore/Compressor/RouterService）不存在锚点词丢失路径。当前版本测试 3 连跑全部通过，性能与堆指标健康。**
