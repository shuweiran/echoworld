/**
 * actionUtils.ts — 搜证页/讨论页纯函数工具（P-0816-I，ui-proto-v2）
 *
 * 纯函数（零 React 依赖，可 esbuild 打包 Node 冒烟）：
 *   - deriveRoleLocks    心锁本地规则推导（决策 U1 过渡口径：线索 content 提及角色名 → 该角色 1 锁；
 *                        二期前规则推导过渡，阶段二切 LLM 标注 clues[].unlock_role + API-3/4）
 *   - clueCountAtLocation 某地点对请求者可见的线索数（status.clues = 公开 + 本人持有）
 *   - deriveRelations    人物×线索关系矩阵推导（决策 U2 MVP，阶段二切 API-8）
 *   - mergeTranscript    讨论对话流合并去重（转录 ∪ SSE 实时）
 *   - applyUnlockToLocks / isTurnPressed / playBindState（阶段二心锁/质询/扮演纯函数）
 *
 * 阶段 D（P-0817-E）：evidenceTags/filterEvidence → utils/ui/evidenceFilter.ts；
 * buildVnLines → utils/ui/vnText.ts；ScriptClueLike 类型上移至 evidenceFilter.ts——
 * 本文件 re-export 兼容旧消费点（ScriptProtoRightPanel 等），新消费点直接引共享层。
 */
/* re-export：证据检索/chips + VN 文本拼装（阶段 D 已抽至共享层 utils/ui/） */
export { evidenceTags, filterEvidence, type ScriptClueLike } from '../../utils/ui/evidenceFilter';
export { buildVnLines } from '../../utils/ui/vnText';
/* 本文件内部仍使用 ScriptClueLike 类型（deriveRoleLocks/clueCountAtLocation/deriveRelations） */
import type { ScriptClueLike } from '../../utils/ui/evidenceFilter';

/**
 * U1 过渡口径（本地规则推导）：
 * 线索 content（含 title）提及角色名 → 该角色 1 锁（同一条线索多次提及同一角色仍计 1 锁）。
 * 输入 status.clues（公开 + 本人持有，二期切后端 API-3 后以服务端为准）。
 * 返回按 roles 顺序、仅含锁数 > 0 的角色。
 */
export function deriveRoleLocks(
  clues: ScriptClueLike[] | null | undefined,
  roles: string[] | null | undefined,
): Array<{ role: string; count: number }> {
  const clueList = Array.isArray(clues) ? clues : [];
  const roleList = Array.isArray(roles) ? roles : [];
  const counts = new Map<string, number>();
  for (const c of clueList) {
    const text = `${String(c?.content ?? '')} ${String(c?.title ?? '')}`.trim();
    if (!text) continue;
    for (const r of roleList) {
      if (!r) continue;
      if (text.includes(r)) counts.set(r, (counts.get(r) || 0) + 1);
    }
  }
  return roleList.filter(r => counts.has(r)).map(r => ({ role: r, count: counts.get(r) || 0 }));
}

/** 某地点对请求者可见的线索数（status.clues 口径：公开 + 本人持有；无匹配返回 0）。 */
export function clueCountAtLocation(
  clues: ScriptClueLike[] | null | undefined,
  location: string,
): number {
  if (!location) return 0;
  const clueList = Array.isArray(clues) ? clues : [];
  return clueList.filter(c => c?.location === location).length;
}

/** 行动类型 → 图标（行动条渲染；未知类型兜底 🎯） */
export function actionEmoji(type?: string): string {
  if (type === 'ask') return '🤝';
  if (type === 'research') return '📚';
  if (type === 'present') return '🃏';
  return '🎯';
}

/* ═══════════════ P-0816-M（对局页按原型重构）：逻辑链矩阵 / 讨论流合并 ═══════════════ */

/**
 * 人物×线索关系矩阵推导（决策 U2 MVP 内容推导：线索 content/title 提及角色名 → ★直接关联；
 * 其余 – 无关联）。阶段二切后端 API-8（GET /api/script/relations 服务端推导 + LLM 标注
 * clues[].related_roles[]），本函数为纯前端过渡实现（零新端点，数据源 status.clues）。
 * 返回 {matrix: 行角色→列线索的标记, lines: 关系连线文案}。
 */
export interface RelationMark {
  role: string;
  clue: string;
  mark: '★' | '–';
}

export function deriveRelations(
  clues: ScriptClueLike[] | null | undefined,
  roles: string[] | null | undefined,
): { matrix: RelationMark[]; lines: Array<{ role: string; clueId: string; text: string }> } {
  const clueList = Array.isArray(clues) ? clues : [];
  const roleList = Array.isArray(roles) ? roles : [];
  const matrix: RelationMark[] = [];
  const lines: Array<{ role: string; clueId: string; text: string }> = [];
  for (const r of roleList) {
    if (!r) continue;
    for (const c of clueList) {
      if (!c?.id) continue;
      const text = `${String(c.content ?? '')} ${String(c.title ?? '')}`;
      const direct = text.includes(r);
      matrix.push({ role: r, clue: String(c.id), mark: direct ? '★' : '–' });
      if (direct) {
        const short = (String(c.content ?? c.title ?? '') || '').slice(0, 26);
        lines.push({ role: r, clueId: String(c.id), text: short });
      }
    }
  }
  return { matrix, lines };
}

/**
 * 讨论对话流合并（去重）：status.discussion 转录（轮询权威）∪ script_speech SSE 实时发言。
 * 实时 turn 若 (speaker,message) 与转录任一重复则跳过（对齐 galSseAdapter 去重模式，防双播）；
 * 转录缺失 speaker/message 的脏行忽略。返回按转录顺序 + 尾部追加实时新言的合并列表。
 * P-0816-R：pressed/pressed_by 键随转录透传（API-5 质询标记 → 矛盾点角标数据源，不再纯本地）。
 */
export interface TranscriptTurnLike {
  speaker?: string;
  message?: string;
  pressed?: string;
  pressed_by?: string;
}

export function mergeTranscript(
  transcript: Array<TranscriptTurnLike> | null | undefined,
  live: Array<TranscriptTurnLike> | null | undefined,
): Array<{ speaker: string; message: string; pressed?: string; pressed_by?: string }> {
  const trans = Array.isArray(transcript) ? transcript : [];
  const liveList = Array.isArray(live) ? live : [];
  const out: Array<{ speaker: string; message: string; pressed?: string; pressed_by?: string }> = [];
  const seen = new Set<string>();
  for (const t of trans) {
    const s = String(t?.speaker ?? '');
    const m = String(t?.message ?? '');
    if (!s || !m) continue;
    const key = `${s}|${m}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      speaker: s,
      message: m,
      ...(t?.pressed !== undefined ? { pressed: String(t.pressed) } : {}),
      ...(t?.pressed_by !== undefined ? { pressed_by: String(t.pressed_by) } : {}),
    });
  }
  for (const l of liveList) {
    const s = String(l?.speaker ?? '');
    const m = String(l?.message ?? '');
    if (!s || !m) continue;
    const key = `${s}|${m}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ speaker: s, message: m });
  }
  return out;
}

/* ═══════════════ P-0816-R（阶段二）：心锁/质询/扮演纯函数（esbuild 冒烟覆盖） ═══════════════ */

/** 心锁条目（API-3 locks[] 契约）。 */
export interface LockLike {
  role: string;
  lock_count: number;
  unlock_clue_ids?: string[];
  unlocked?: boolean;
}

/**
 * 解锁状态更新（API-4 破锁成功后本地合并）：目标角色锁数归零 + unlocked=true。
 * 未在列表中的角色不产生新条目（与后端 locksPayload 口径一致）；返回新数组（不可变）。
 */
export function applyUnlockToLocks(locks: LockLike[] | null | undefined, role: string): LockLike[] {
  const list = Array.isArray(locks) ? locks : [];
  return list.map(l =>
    l?.role === role
      ? { ...l, lock_count: 0, unlocked: true }
      : l,
  );
}

/**
 * 质询标记判定（API-5 服务端 pressed 标记驱动）：转录条目 pressed=true/pressed_by 非空
 * （轮询权威）∪ SSE script_press 事件命中（message_id=msg_<idx>）→ 显示「矛盾点？」角标。
 */
export function isTurnPressed(
  turn: TranscriptTurnLike | null | undefined,
  pressEvents: Array<{ message_id?: string }> | null | undefined,
  idx: number,
): boolean {
  if (turn) {
    if (String(turn.pressed ?? '') === 'true') return true;
    if (String(turn.pressed_by ?? '').trim() !== '') return true;
  }
  const events = Array.isArray(pressEvents) ? pressEvents : [];
  const targetId = `msg_${idx}`;
  return events.some(e => e?.message_id === targetId);
}

/**
 * 扮演表单状态（内联化替代 window.prompt）：确认时绑定角色名 + 可选角色令牌；
 * 取消（confirm=false）保持原状。返回 {boundName, roleKey}（不可变）。
 */
export function playBindState(
  currentBound: string,
  currentRoleKey: string,
  roleName: string,
  inputKey: string,
  confirm: boolean,
): { boundName: string; roleKey: string } {
  if (!confirm) return { boundName: currentBound, roleKey: currentRoleKey };
  return {
    boundName: roleName,
    roleKey: inputKey.trim() ? inputKey.trim() : currentRoleKey,
  };
}
