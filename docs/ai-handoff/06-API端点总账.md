# 06 — API 端点总账（110 个，2026-07-31 逐 Controller 核实）

> 完整契约（请求/响应体）见项目 `docs/测试方案-全功能覆盖-v2.md` 第 5-6 章。写前后端代码时对照此表。

## 分组总览
| Controller | 基路径 | 端点 | 模块 |
|---|---|---|---|
| SessionController | /api | 17 | 会话对话（state/init/send/stop/auto/mode/goals/agents/voice/private_chat 等） |
| RoundController | /api/round | 3 | 回合（start/rollback/status） |
| CharacterController | /api/characters | 6 | 角色（PUT/DELETE/generate/batch） |
| SceneController | /api/scenes | 6 | 场景（PUT/DELETE/{id}/start/generate） |
| HistoryController | /api/history | 4 | 历史（sessions/sessions/{id}/load/{id}） |
| ConfigController | /api/config | 7 | 配置（apikey/language/models/voice） |
| AuthController | /api/auth | 6 | 认证（verify/me/admin/generate/admin/list/admin/deactivate） |
| RoomController | /api/rooms | 5 | 房间（{code}/join/leave/assign） |
| WerewolfController | /api/werewolf | 8 | 狼人杀（init/night_action/hunter_shoot/resolve_night/vote/resolve_vote/start_voting/status） |
| ScriptController | /api/script | 7 | 剧本杀（init/search/start_discussion/start_voting/vote/resolve/status） |
| TrackRequestController | /api/track | 5 | 轨道申请（request/approve/reject/requests/evaluate） |
| ApprovalController | /api/approval | 4 | 审批门（approve/reject/status/status/detail） |
| SSEController | /api/events | 1 | SSE |
| VoiceController | /api/voice | 3 | 语音（status/start/stop；transcribe 缺失=D9） |
| WebSearchController | /api/search | 3 | 搜索（fetch 等） |
| McpController | /api/mcp | 4 | MCP（servers/call/status/reconnect） |
| SimulationController | /api/simulation | 21 | **2D 模拟**（init/load-characters/start/stop/reset/state/send/{agent}/move/{agent}/target/{agent}/emotion/{agent}/config/{agent}/directive/scene/{scene}/scenes/conversation-status/conversations/**track/goal**/**track/secret**/**track/state**） |
| **合计** | | **110** | |

## 剧本杀端点详情（蓝图 07 依赖）
| 端点 | 请求体 | 响应体 |
|---|---|---|
| POST /api/script/init | `{players, theme?}` | toMap（仅第一玩家视角） |
| POST /api/script/search | `{player, location}` | `{found, clues, public_clues, location}` |
| POST /api/script/start_discussion | `{session_id?}` | `{phase:"discussion"}` |
| POST /api/script/start_voting | `{session_id?}` | `{phase:"vote"}` |
| POST /api/script/vote | `{player, suspect}` | `{result}` |
| POST /api/script/resolve | `{session_id?}` | `{votes, most_voted, vote_count, result, correct?, truth}` |
| GET /api/script/status?player= | — | toMap 或 `{phase:"idle"/"not_found"}` |
| POST /api/script/generate | `{theme?, characters}` | ScriptService JSON（**注意：挂在 SessionController，schema 无 secrets/killer**） |

## 2D 轨道端点（Phase 4 新增）
| 端点 | 请求体 | 说明 |
|---|---|---|
| POST /api/simulation/track/goal | `{agent, goal}` | 手动目标注入（WorldDirector） |
| POST /api/simulation/track/secret | `{agents: [names]}` | 秘密任务注入（强制 ISOLATED） |
| GET /api/simulation/track/state | — | `{goals, secret_agents, last_score, assignments}` |
