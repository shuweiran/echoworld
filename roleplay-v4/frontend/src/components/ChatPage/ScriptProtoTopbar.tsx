/**
 * ScriptProtoTopbar.tsx — 剧本杀对局页·顶栏（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 对齐原型三页（investigation/discussion/vote.html）顶栏：
 *   🕵️ 剧本名 · 第 N 轮徽章 · 阶段彩色徽章 · 🎯 目标 HUD（GoalBadge）· ⏱ 倒计时（讨论/投票）· ⚙️ 设置 · ❓ 帮助
 * 9 个旧顶栏按钮全部移除出顶栏，收纳去向（决策记录 B 表逐项核对）：
 *   ⚙️ 设置菜单：🎬 导演（L3 目录外·保留）/ 🛎️ 主持人 DM（L6）/ 💬 私聊（L3）/
 *     🎨 对局美术 / 🧭 逻辑链（API 追踪 TraceDrawer）/ 📜 状态面板（ScriptStatePanel 兜底，U5）/
 *     🚪 退出对局（L5）/ 🔄 恢复对局（L7）/ 📋 回到剧本选择 / 公告开关
 *   右栏 Tab：角色库 / 历史（L11）/ 逻辑链矩阵（U2）
 * 阶段色：.proto-topbar 随 workspace.phase-<phase> CSS 变量切换（青蓝/暖橙/红紫）。
 */
import { useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { GoalBadge } from '../ui/GoalBadge';
import { useLocalCountdown } from './useLocalCountdown';
import { ScriptResumePanel } from './script/ScriptResumePanel';
import { SCRIPT_GUIDES } from './chatUtils';
// 阶段 D（P-0817-E）：阶段徽章已抽至共享层 components/ui/PhaseBadge.tsx
import { PhaseBadge, scriptPhaseThemeClass } from '../ui/PhaseBadge';

export interface ScriptProtoTopbarProps {
  /** 抽屉开关（导演/主持人/私聊/美术/逻辑链，由 ChatPage 持有） */
  drawers: {
    showDirector: boolean;
    setShowDirector: (v: boolean) => void;
    showDm: boolean;
    setShowDm: (v: boolean) => void;
    showPrivate: boolean;
    setShowPrivate: (v: boolean) => void;
    showImages: boolean;
    setShowImages: (v: boolean) => void;
    showChain: boolean;
    setShowChain: (v: boolean) => void;
  };
  scriptState: any;
  /** 退出对局（角色转 AI 托管） */
  onLeave: () => void;
  /** 打开兜底状态面板抽屉（ScriptStatePanel，决策 U5 揭晓区保留） */
  onOpenStatePanel: () => void;
  /** 查看 2D 模拟 / 对局地图（Phaser；原 ScriptStatePanel「查看 2D 模拟（内嵌）」入口，L1） */
  onOpen2D: () => void;
  /** 回到剧本选择页 */
  onBackToScene: () => void;
}

export function ScriptProtoTopbar({ drawers, scriptState, onLeave, onOpenStatePanel, onOpen2D, onBackToScene }: ScriptProtoTopbarProps) {
  const store = useAppStore();
  const [menuOpen, setMenuOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [annEnabled, setAnnEnabled] = useState(() => {
    try { return localStorage.getItem('roleplay_ann_show') !== '0'; } catch { return true; }
  });

  const phase = String(scriptState?.phase || store.scriptPhase || 'setup');
  const title = scriptState?.name || scriptState?.theme || '剧本杀对局';
  const round = scriptState?.round || store.currentRound || 1;
  const themeClass = scriptPhaseThemeClass(phase);

  // 倒计时（决策 U9：status.phase_elapsed_ms/phase_timeout_ms 本地计时，不走 SSE 事件）
  const { remainSec } = useLocalCountdown(
    scriptState?.phase_elapsed_ms,
    scriptState?.phase_timeout_ms,
  );
  const showCountdown = (phase === 'discussion' || phase === 'vote') && remainSec != null;

  const toggleAnnEnabled = () => {
    setAnnEnabled(v => {
      const next = !v;
      try { localStorage.setItem('roleplay_ann_show', next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };

  const closeMenu = () => setMenuOpen(false);
  const menuItem = (icon: string, label: string, onClick: () => void, hint?: string) => (
    <button key={label} className="proto-menu-item" onClick={() => { closeMenu(); onClick(); }}>
      <span className="proto-menu-ico">{icon}</span>
      {label}
      {hint ? <small>{hint}</small> : null}
    </button>
  );

  return (
    <header className={`topbar game-topbar proto-topbar theme-${themeClass}`}>
      {/* 🕵️ 剧本名（原型 logo：标题 + 相位色 accent 点） */}
      <div className="proto-top-logo" title={scriptState?.background || title}>
        <span className="proto-top-title">🕵️ {title}</span>
        <span className="proto-top-accent">.</span>
      </div>
      {/* 第 N 轮徽章（原型 badge-round） */}
      <span className="proto-badge proto-badge-round">第 {round} 轮</span>
      {/* 阶段彩色徽章（原型 badge-phase：渐变 + 发光；共享组件 PhaseBadge，阶段 D P-0817-E） */}
      <PhaseBadge phase={phase} />
      {/* 🎯 当前目标 HUD（决策 U4/U14：GET /api/script/goal + SSE script_goal + 3s 轮询兜底） */}
      <GoalBadge goal={store.scriptGoal} />
      {/* 倒计时徽章（讨论/投票阶段；本地计时 U9） */}
      {showCountdown && (
        <span className={`proto-badge proto-badge-countdown${remainSec != null && remainSec <= 10 ? ' urgent' : ''}`} title="阶段倒计时（本地计时，status.phase_elapsed_ms/phase_timeout_ms）">
          ⏱ {remainSec}s
        </span>
      )}
      <div className="topbar-spacer" />

      {/* ⚙️ 设置菜单（收纳旧顶栏 9 按钮：导演/主持人/私聊/美术/逻辑链/兜底面板/退出/恢复/回剧本选择） */}
      <div className={`proto-menu${menuOpen ? ' open' : ''}`}>
        <button className="proto-icon-btn" title="设置" onClick={() => { setMenuOpen(v => !v); setHelpOpen(false); }}>⚙️</button>
        {menuOpen && (
          <div className="proto-menu-panel">
            <div className="proto-menu-cap">高级功能</div>
            {menuItem('🎬', '导演面板', () => drawers.setShowDirector(!drawers.showDirector))}
            {menuItem('🛎️', '主持人', () => drawers.setShowDm(!drawers.showDm), 'DM')}
            {menuItem('💬', '私聊', () => drawers.setShowPrivate(!drawers.showPrivate))}
            {menuItem('🎨', '对局美术', () => drawers.setShowImages(!drawers.showImages))}
            {menuItem('🧭', '逻辑链（API 追踪）', () => drawers.setShowChain(!drawers.showChain))}
            {menuItem('📜', '状态面板（兜底）', onOpenStatePanel)}
            {menuItem('🗺️', '查看 2D 模拟 / 对局地图', onOpen2D)}
            <div className="proto-menu-sep" />
            {menuItem('🚪', '退出对局（AI 代管）', onLeave)}
            {menuItem('📋', '回到剧本选择', onBackToScene)}
            <div className="proto-menu-sep" />
            <div className="proto-menu-cap">公告栏（2D 游戏内）</div>
            <label className="proto-menu-toggle" style={{ display: 'flex', gap: 6, alignItems: 'center', padding: '6px 12px', fontSize: 12, color: '#c9d4ea', cursor: 'pointer' }}>
              <input type="checkbox" checked={annEnabled} onChange={toggleAnnEnabled} />
              显示演讲/广播公告（横幅 + 公告栏）
            </label>
            <div className="proto-menu-cap">恢复对局（L7）</div>
            {/* 恢复对局折叠区（L7：ScriptResumePanel 内嵌，自包含 resume 流程） */}
            <div className="proto-menu-resume">
              <ScriptResumePanel />
            </div>
          </div>
        )}
      </div>

      {/* ❓ 帮助（当前阶段引导 + 布局提示） */}
      <div className="proto-menu">
        <button className="proto-icon-btn" title="帮助" onClick={() => { setHelpOpen(v => !v); setMenuOpen(false); }}>❓</button>
        {helpOpen && (
          <div className="proto-menu-panel proto-help-panel">
            <div className="proto-menu-cap">当前阶段引导</div>
            <div className="proto-help-text">{SCRIPT_GUIDES[phase] || '进入对局，按阶段推进：搜证 → 讨论 → 投票 → 揭晓。'}</div>
            <div className="proto-menu-cap">布局提示</div>
            <div className="proto-help-text">
              ◀ 左栏可收窄为图标条（56px）；右栏 » 收起后点右下角 📋 唤出（覆盖式抽屉）；折叠状态已记忆（localStorage）。
              <br />🎯 目标徽章来自 GET /api/script/goal；阶段色随阶段切换（搜证青蓝 / 讨论暖橙 / 投票红紫）。
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
