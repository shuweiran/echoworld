/**
 * simGroupFilter.ts —— P-0815-H 群聊面板消息按群过滤（方案 A，前端最小修复）
 *
 * 根因（docs/群聊成员vs轨道成员不一致调研.md）：后端 recentConversations 为全局单列表
 * （ConversationManager.executeRound 把每个组的每轮发言全量写入 SimulationWorld），
 * 前端 worldMsgs 拍平全世界消息且 SKIP_CONV_KEYS 丢弃 group 键 → SimGalChatPanel 全量入队
 * → 群聊面板混入其他群的消息（群头只显示玩家当前群成员）。
 *
 * 本模块提供群聊过滤判定（纯函数，可单测）：
 *   - 玩家在群中（currentGroupId 非空）→ 只放行属于当前群的消息（所见即所得）；
 *   - 未进入群聊 → 不把全世界的发言塞进当前面板；附近提示由独立的局部感知 UI 提供。
 *
 * 后端条目结构（已实证，ConversationManager L776-786）：
 *   { group: 群id, mode: 模式名, tick, round, <发言者名>: 文本, ... }
 *   convEntry.group 与 conversation-status 的 groups[].id / currentTrack 同源（group.getGroupId()），
 *   因此前端消息携带的 group 可直接与 groupInfo.id 精确匹配。
 */
export function shouldShowWorldMsg(msgGroup: string | undefined, currentGroupId: string | undefined): boolean {
  // 未进入群聊时，不能把未知空间/轨道中的完整对话当作玩家已知信息。
  if (!currentGroupId) return false;
  // 群聊模式：只入队属于玩家当前群的消息；无群归属的消息无法验证归属 → 不放行（防混入）
  return !!msgGroup && msgGroup === currentGroupId;
}
