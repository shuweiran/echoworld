# LONG-01 首跑失败根因 - 证据链记录

> ⚠️ 历史测试证据（2026-08-24）：文中的 `SessionManager` 已作为死代码删除；当前结果见根目录 `TEST_STATUS.md`。

## 时间线（关键证据）
| 时间 | 事件 |
|---|---|
| 14:27:56 | 首跑结束 **失败**：`LongTextStabilityTest.java:127` 锚点词断言 AssertionFailedError |
| **14:28:48** | **LongTextStabilityTest.java 文件被修改**（mtime） |
| 14:29:16 | 复跑结束 **通过**，且其日志含 `[INFO] Recompiling the module because of changed source code`（Maven 检测到源码变更强制重编译） |

## 证据 1：断言行号位移 = callSimple mock 改动
- 失败日志（long01-run.log）报错行：`LongTextStabilityTest.java:127`
- 当前文件锚点词断言行：`LongTextStabilityTest.java:138`
- 差值 **+11 行**，恰好等于：
  - 当前 `callSimple` mock 为 12 行 `thenAnswer` 块（L61-L72，回显"用户输入："文本）
  - 旧版应为 1 行 `thenReturn` 固定回复（12 - 1 = +11 ✓）

## 证据 2：代码注释自证（当前文件 L62-L63）
```
// 真实 LLM 会把用户输入转换为旁白（保留语义）。mock 回显"用户输入："之后的文本，
// 使第 1 轮锚点词能进入持久化消息（贴近真实行为，用于内容不丢失验证）。
```
"使第 1 轮锚点词能进入持久化消息" —— 即旧版 mock 回显固定 50 字回复，**锚点词根本不会进入 USER 消息**，断言必然失败（确定性失败，非偶发）。

## 证据 3：锚点词进入路径（代码跟踪）
1. round 1 请求体含锚点词（失败日志 L655 已确认请求侧正确）
2. `RouterService.runRound` → `arbiter.processUserInput` → `llmClient.callSimple(prompt,120)`
3. 旧版 mock 返回固定回复"（模拟回复）…50字…" → **无锚点词** → USER 消息内容不含锚点词
4. 断言 `session.getMessages().stream().anyMatch(m -> m.getContent().contains("锚点词XYZ-0001"))` → false → 失败
5. 新版 mock 回显"用户输入："后文本（≤120 字）→ 锚点词在开头 → USER 消息含锚点词 → 通过

## 证据 4：引擎侧无消息删除路径（排除引擎缺陷）
- `MemoryStore`/`Session`：messages 只增不减（`addMessage` 仅 append）
- `SessionManager.prune()` 为死代码：全仓库无任何调用点（grep `\.prune\(` 仅定义处）
- `rollbackToRound` 才调 `setMessages`，测试未调用
- 压缩链只 `add` 到 `compressedChunks`，不触碰 messages
- 500 轮全 200 OK（失败日志 status 统计：无 error），无异常

## 结论
**首跑失败 = 测试自身 mock 缺陷（确定性失败），并在两次运行之间（14:28:48）被静默修改修复；复跑通过 ≠ 引擎稳定，而是测试已被改动。** 分类：测试脆弱/测试缺陷（非引擎确定性缺陷，非偶发竞态）。
