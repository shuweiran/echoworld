/**
 * interactData.ts — 地图交互判定与结果映射纯函数（P-0814-H 热点/搜证点交互系统）
 *
 * 与后端 MapInteractService（src/main/java/com/roleplay/engine/simulation/map/interact/）契约对齐：
 *   - 半径判定：Chebyshev |dx|≤r 且 |dy|≤r，默认 r=1（后端 DEFAULT_RADIUS），decor.radius 可覆盖（≥1）
 *   - 交互目标：decor_id 显式 > tile 坐标（decor 实体 > tileProps.action > 环境占位）
 *   - once 幂等：decor.once=true 交互后后端标记 processed；重复交互返回「已处理」语义
 *   - 响应键：ok/handled/processed/blocked/dialog/items/flags/sounds/anims/menu/state/result/error
 *
 * 本文件只含纯数据/纯函数（Node 冒烟可测，不依赖 Phaser/React）：
 *   decorInRange          靠近判定（ScriptMapScene 点击/高亮共用，单一事实源）
 *   decorStateKey         实例状态键（"mapId|decorId"，对齐后端 decorStates 键）
 *   formatInteractResult  后端交互响应 → 前端展示结构（ok/text/dialog/clues/processed）
 */
import type { MapDecorItem } from './mapData';

/** decor 实例状态键：后端 decorStates 的键 = "mapId|decorId"（对局内多图注册表隔离）。 */
export function decorStateKey(mapId: string, decorId: string): string {
  return `${mapId}|${decorId}`;
}

/**
 * decor 交互物靠近判定（Chebyshev |dx|≤r 且 |dy|≤r）——与后端 MapInteractService 半径语义逐字对齐：
 *   r = decor.radius（≥1）覆盖，缺省 defaultRadius=1（后端 DEFAULT_RADIUS）。
 * tile 缺失/非法 → 不可交互（false）。
 */
export function decorInRange(d: MapDecorItem, gx: number, gy: number, defaultRadius = 1): boolean {
  const tile = d && Array.isArray(d.tile) && d.tile.length >= 2 ? d.tile : null;
  if (!tile) return false;
  const dx = Number(tile[0]);
  const dy = Number(tile[1]);
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return false;
  const r = Number.isFinite(Number(d.radius)) && Number(d.radius) >= 1 ? Number(d.radius) : defaultRadius;
  return Math.abs(gx - dx) <= r && Math.abs(gy - dy) <= r;
}

/** 后端交互响应 → 前端展示结构（{ok, text, dialog, clues, processed}）。 */
export interface InteractResultView {
  ok: boolean;
  /** 结果卡主文本（成功/已处理/失败原因） */
  text: string;
  /** dialog 动作文本列表（可空） */
  dialog: string[];
  /** addItem 授予的线索（{id, title} 列表，可空） */
  clues: { id: string; title: string }[];
  /** once 交互本次/此前已处理（用于前端灰显） */
  processed: boolean;
}

/**
 * 交互结果映射：后端 POST /api/script/interact 响应 → 前端结果卡。
 * 契约键宽容读取（结果缺失时回退构建可读文本，保证前端零异常）。
 */
export function formatInteractResult(resp: unknown): InteractResultView {
  const r = (resp && typeof resp === 'object' ? resp : {}) as Record<string, unknown>;
  // ok 判定：显式 ok=true，或（缺省 ok 时）handled=true 且无 error（宽容契约，保证前端零异常）
  const noError = r.error === undefined || r.error === null;
  const ok = (r.ok === true || (r.ok === undefined && r.handled === true)) && noError;
  // 已处理（once 幂等：本次 marked 或此前已处理过）
  const processed = r.processed === true;
  // 对话框文本（string | string[] 宽容）
  const dialogRaw = r.dialog;
  const dialog = Array.isArray(dialogRaw)
    ? dialogRaw.map(String)
    : dialogRaw !== undefined && dialogRaw !== null
      ? [String(dialogRaw)]
      : [];
  // addItem 授予的线索
  const itemsRaw = Array.isArray(r.items) ? r.items : [];
  const clues = itemsRaw
    .filter((it): it is Record<string, unknown> => !!it && typeof it === 'object')
    .map(it => ({ id: String(it.id ?? ''), title: String(it.title ?? it.id ?? '') }))
    .filter(c => c.id);
  // 主文本：优先 result（后端汇总），其次 error / dialog 首条 / 已处理文案
  let text = '';
  if (typeof r.result === 'string' && r.result) text = r.result;
  else if (typeof r.error === 'string' && r.error) text = r.error;
  else if (dialog.length > 0) text = dialog[0];
  else if (processed) text = '该处已处理过';
  else if (ok) text = '交互完成';
  else text = '交互失败';
  // handled=false 且无 error（环境占位/无效果）→ 直接透传 result 文本
  if (!text && r.handled === false) text = '这里没有什么特别的。';
  return { ok, text, dialog, clues, processed };
}
