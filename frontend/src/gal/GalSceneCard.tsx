/**
 * GalSceneCard.tsx — 场景卡（P-0810-08 占位 → P-0810-16 真实目标渲染）
 *
 * 显示：场景名 + 场景描述 + 目标列表。
 *  - 场景名/描述 = 父组件从 GET /api/state?session_id= 的 scene 字段解析传入；
 *    无场景名 → 组件返回 null（隐藏卡片）。
 *  - 目标数据（P-0810-16）：
 *    a) props.goals（结构化，GalGeneralView 从起局响应 goals / /api/state scene_goals /
 *       scene_target_update SSE 合并而来）——玩家目标明文 + AI 目标 ?? 占位（含状态）+ revealed 揭示全文；
 *    b) props.targets（旧字符串列表，兼容）；
 *  - 优雅降级：无 goals 也无 targets（旧会话）→ 只显示场景名+描述，不显示「目标系统开发中」占位。
 */
import type { ReactNode } from 'react';
import { GOAL_STATUS_LABEL, GOAL_MASK, type GalSceneGoals } from './GalStore';

export interface GalSceneInfo {
  name?: string;
  description?: string;
}

interface GalSceneCardProps {
  /** 场景信息（无 name → 隐藏卡片） */
  scene?: GalSceneInfo;
  /** 目标列表（旧字符串列表，兼容保留；goals 优先） */
  targets?: string[];
  /** P-0810-16：结构化场景目标（后端 goals 视图 + scene_target_update 增量） */
  goals?: GalSceneGoals;
  /** 右上角关闭按钮（父组件控制） */
  onClose?: () => void;
}

/** 目标状态小标签（颜色按状态区分） */
function StatusChip({ status }: { status?: string }) {
  if (!status) return null;
  const label = GOAL_STATUS_LABEL[status] || status;
  return <span className={`galg-goal-status galg-goal-status-${String(status).toLowerCase()}`}>{label}</span>;
}

export function GalSceneCard({ scene, targets, goals, onClose }: GalSceneCardProps) {
  // 场景名缺失 → 隐藏整卡（需求：有就显示，无则隐藏）
  if (!scene?.name) return null;

  // ── P-0810-16：结构化目标渲染（goals.enabled 且有内容） ──
  const structured = goals?.enabled && (goals.player_goal || goals.global_goal || goals.role_goals || (goals.ai_goal_count ?? 0) > 0);

  let goalList: ReactNode = null;
  if (structured) {
    const roleEntries = goals.role_goals ? Object.entries(goals.role_goals) : [];
    const items: ReactNode[] = [];

    // 玩家目标：明文 + 状态
    if (goals.player_goal?.desc) {
      items.push(
        <li key="player" className="galg-target-item galg-goal-player">
          <span className="galg-goal-label">🎯 你的目标：</span>
          {goals.player_goal.desc}
          <StatusChip status={goals.player_goal.status} />
        </li>,
      );
    }

    // AI 隐藏目标：?? 占位 + 状态（desc 隐藏；揭示后由 revealed 区展示全文）
    if (roleEntries.length > 0) {
      items.push(
        roleEntries.map(([name, g]) => (
          <li key={`role-${name}`} className="galg-target-item galg-goal-ai">
            <span className="galg-goal-label">🤖 {name}：</span>
            <span className="galg-goal-mask">{GOAL_MASK}</span>
            <StatusChip status={g.status} />
          </li>
        )),
      );
    } else if ((goals.ai_goal_count ?? 0) > 0) {
      items.push(
        <li key="ai-count" className="galg-target-item galg-goal-ai">
          <span className="galg-goal-label">🤖 AI 隐藏目标：</span>
          <span className="galg-goal-mask">{GOAL_MASK} ×{goals.ai_goal_count}</span>
        </li>,
      );
    }

    // 全局目标：?? 占位 + 状态
    if (goals.global_goal?.desc) {
      items.push(
        <li key="global" className="galg-target-item galg-goal-ai">
          <span className="galg-goal-label">🌐 全局目标：</span>
          <span className="galg-goal-mask">{GOAL_MASK}</span>
          <StatusChip status={goals.global_goal.status} />
        </li>,
      );
    }

    // revealed：完成/失败的 AI 目标揭示全文（scene_target_update 累积）
    if (Array.isArray(goals.revealed) && goals.revealed.length > 0) {
      items.push(
        goals.revealed.map((text, i) => (
          <li key={`revealed-${i}`} className="galg-target-item galg-goal-revealed">
            🔓 {text}
          </li>
        )),
      );
    }

    goalList = items.length > 0 ? <ul className="galg-targets">{items}</ul> : null;
  } else if (Array.isArray(targets) && targets.length > 0) {
    // 旧字符串列表（兼容路径）
    goalList = (
      <ul className="galg-targets">
        {targets.map((t, i) => (
          <li key={i} className="galg-target-item">◆ {t}</li>
        ))}
      </ul>
    );
  }
  // 无 goals 无 targets → goalList=null → 不渲染目标区（优雅降级：场景名+描述即可）

  return (
    <div className="galg-scene-card">
      <div className="galg-scene-head">
        <span className="galg-scene-title">🗺️ 场景卡</span>
        {onClose && (
          <button className="galg-scene-close" onClick={onClose} title="关闭">✕</button>
        )}
      </div>
      <div className="galg-scene-name">{scene.name}</div>
      {scene.description && <div className="galg-scene-desc">{scene.description}</div>}
      {goalList !== null && (
        <>
          <div className="galg-targets-title">🎯 目标</div>
          {goalList}
        </>
      )}
    </div>
  );
}
