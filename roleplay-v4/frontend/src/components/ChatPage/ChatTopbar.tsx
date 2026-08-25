/**
 * ChatTopbar.tsx — 对局顶栏（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：品牌区 + 运行状态 pill + 轮次 + 模式抽屉开关（导演/设置/主持人/私聊/美术/历史）
 * + 「🧭 逻辑链」入口（阶段② 后端 API 可视化逻辑链的占位入口，调研报告 ui-api-survey §5 方案 C）
 * + ⚙️ 聊天设置 popover（自由对话/导演模式切换、公告栏开关）。
 * 暗色游戏风：状态用色块 pill（运行中=绿/空闲=灰），按钮按状态高亮。
 */
import { useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { useDemoStore } from '../../demo2/store';
// P-0816-H（UI 重设计阶段一）：顶栏 🎯 当前目标徽章
// 阶段 D（P-0817-E）：GoalBadge 已抽至共享层 components/ui/，改引新路径
import { UI_PROTO_V2_ENABLED } from '../../uiProtoV2';
import { GoalBadge } from '../ui/GoalBadge';
// P-0817-I：全局静音按钮 + 角色声音控制（mimoTts 单例）
import { TtsMuteButton } from '../TtsMuteButton';
import { isCharacterMuted, toggleCharacterMuted, subscribeTtsStatus } from '../../services/mimoTts';

export interface TopbarDrawers {
  showDirector: boolean;
  setShowDirector: (v: boolean) => void;
  showHistory: boolean;
  setShowHistory: (v: boolean) => void;
  showDm: boolean;
  setShowDm: (v: boolean) => void;
  showPrivate: boolean;
  setShowPrivate: (v: boolean) => void;
  showImages: boolean;
  setShowImages: (v: boolean) => void;
  showChain: boolean;
  setShowChain: (v: boolean) => void;
}

export function ChatTopbar({ drawers }: { drawers: TopbarDrawers }) {
  const store = useAppStore();
  const [showChatSettings, setShowChatSettings] = useState(false);
  // P-0817-I：角色静音开关状态刷新（mimoTts emit 时重渲染）
  const [, setTtsTick] = useState(0);
  useEffect(() => subscribeTtsStatus(() => setTtsTick(t => t + 1)), []);
  // P-0817-I：对局角色列表（一般模式 agents + 剧本杀 roles 合并去重）——「角色声音」区数据源
  const charNames = useMemo(() => {
    const s = new Set<string>();
    (store.agents || []).forEach(a => { if (a) s.add(a); });
    if (Array.isArray(store.scriptState?.roles)) {
      (store.scriptState.roles as string[]).forEach(r => { if (r) s.add(r); });
    }
    return [...s];
  }, [store.agents, store.scriptState?.roles]);
  const [annEnabled, setAnnEnabled] = useState(() => {
    try { return localStorage.getItem('roleplay_ann_show') !== '0'; } catch { return true; }
  });
  const toggleAnnEnabled = () => {
    setAnnEnabled(v => {
      const next = !v;
      try { localStorage.setItem('roleplay_ann_show', next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };
  /** P1-8：聊天模式切换（自由对话 / 导演模式；狼人杀/剧本杀需在「场景」页开局，此处不提供。*/
  const switchChatMode = async (m: string) => {
    if (m === store.mode) { setShowChatSettings(false); return; }
    setShowChatSettings(false);
    try {
      if (store.isRunning) await store.stop();
      if (m === 'director') {
        const dc = (store.directorCharacter && store.directorCharacter !== '系统') ? store.directorCharacter : store.currentPlayer;
        await store.setMode('director', '', dc);
      } else {
        await store.setMode('free', '', '');
      }
    } catch { /* ignore */ }
  };

  return (
    <header className="topbar game-topbar">
      <div className="brand">
        <div className="brand-mark">E</div>
        <div style={{ minWidth: 0 }}>
          <div className="brand-title">EchoWorld</div>
          <div className="brand-subtitle">{store.sceneDescription || '未加载场景描述'}</div>
        </div>
      </div>
      <div className="topbar-spacer" />
      {/* P-0816-H（UI 重设计阶段一，决策 U4/U14）：剧本杀顶栏 🎯 当前目标徽章
          （GET /api/script/goal + SSE script_goal + 3s 轮询兜底；数据由 ChatPage 写入 store.scriptGoal） */}
      {store.mode === 'script' && UI_PROTO_V2_ENABLED && (
        <GoalBadge goal={store.scriptGoal} />
      )}
      <span className={`status-pill ${store.isRunning ? 'good' : ''}`}>
        <span className={`pill-dot ${store.isRunning ? 'on' : ''}`} />
        {store.isRunning ? '⏳ 运行中' : '⏸ 空闲'}
      </span>
      <span className="status-pill">第 {store.currentRound} 轮</span>
      <button className={`btn ${drawers.showDirector ? 'btn-primary' : ''}`} onClick={() => drawers.setShowDirector(!drawers.showDirector)}>🎬 导演</button>
      <button className={`btn ${showChatSettings ? 'btn-primary' : ''}`} onClick={() => setShowChatSettings(!showChatSettings)}>⚙️ 设置</button>
      {store.mode === 'script' && (
        <button className={`btn ${drawers.showDm ? 'btn-primary' : ''}`} onClick={() => drawers.setShowDm(!drawers.showDm)}>🎛 主持人</button>
      )}
      {store.mode === 'script' && (
        <button className={`btn ${drawers.showPrivate ? 'btn-primary' : ''}`} onClick={() => drawers.setShowPrivate(!drawers.showPrivate)}>💬 私聊</button>
      )}
      {store.mode === 'script' && (
        <button className={`btn ${drawers.showImages ? 'btn-primary' : ''}`} onClick={() => drawers.setShowImages(!drawers.showImages)}>🎨 美术</button>
      )}
      {/* 阶段① 占位入口：后端 API 可视化逻辑链（阶段② 实现，调研报告 §5 方案 C）*/}
      <button
        className={`btn game-chain-btn ${drawers.showChain ? 'btn-primary' : ''}`}
        onClick={() => drawers.setShowChain(!drawers.showChain)}
        title="查看前端操作 → API → 后端逻辑 → SSE 回推的完整链路（阶段② 接入后端追踪）"
      >🧭 逻辑链</button>
      <button
        className="btn game-chain-btn"
        onClick={() => useDemoStore.getState().go('scripts')}
        title="回到剧本选择页"
      >场景</button>
      <button
        className="btn"
        onClick={() => useDemoStore.getState().go('roles-lib')}
        title="打开角色库"
      >角色库</button>
      <button className={`btn ${drawers.showHistory ? 'btn-primary' : ''}`} onClick={() => drawers.setShowHistory(!drawers.showHistory)}>📋 历史</button>
      {/* P-0817-I：全局语音开关（静音/恢复）——对所有模式生效，静音时消息 🎙 按钮置 🔇 */}
      <TtsMuteButton />

      {/* ⚙️ 聊天设置 popover（点外部关闭） */}
      {showChatSettings && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 98 }} onClick={() => setShowChatSettings(false)} />
      )}
      {showChatSettings && (
        <div className="game-popover" style={{
          position: 'fixed', right: 16, top: 56, zIndex: 99, width: 280,
          background: 'var(--bg-2, var(--panel-2))', border: '1px solid var(--border, var(--panel-3))',
          borderRadius: 10, padding: 12, boxShadow: '0 8px 30px rgba(0,0,0,0.4)',
        }}>
          <div className="label" style={{ marginBottom: 6 }}>⚙️ 聊天设置</div>
          <div className="label" style={{ fontSize: 12, color: 'var(--text-3)', margin: '8px 0 4px' }}>聊天模式</div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <button
              className={`chip ${store.mode === 'free' ? 'active' : ''}`}
              onClick={() => switchChatMode('free')}
              title="自由对话：角色自动互动">自由对话</button>
            <button
              className={`chip ${store.mode === 'director' ? 'active' : ''}`}
              onClick={() => switchChatMode('director')}
              title="导演模式：以某个角色身份引导剧情"
            >导演模式</button>
            <button className="chip" disabled style={{ opacity: 0.45, cursor: 'not-allowed' }} title="狼人杀/剧本杀请在「场景」页开局">狼人杀</button>
            <button className="chip" disabled style={{ opacity: 0.45, cursor: 'not-allowed' }} title="狼人杀/剧本杀请在「场景」页开局">剧本杀</button>
          </div>
          <div className="label" style={{ fontSize: 12, color: 'var(--text-3)', margin: '10px 0 4px' }}>公告栏（2D 游戏内）</div>
          <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 13, cursor: 'pointer' }}>
            <input type="checkbox" checked={annEnabled} onChange={toggleAnnEnabled} />
            显示演讲/广播公告（横幅 + 公告栏）
          </label>
          <div style={{ fontSize: 11, color: 'var(--text-3)', marginTop: 6, lineHeight: 1.5 }}>
            公告仅在 2D 模拟视图内出现（场景页「进入 2D 模拟」入口，或剧本杀「查看 2D 模拟（内嵌）」按钮）。
          </div>
          {/* P-0817-I：角色声音控制 —— 可单独静音某个角色的语音（不影响其他角色播放） */}
          <div className="label" style={{ fontSize: 12, color: 'var(--text-3)', margin: '10px 0 4px' }}>角色声音</div>
          {charNames.length === 0 ? (
            <div style={{ fontSize: 11, color: 'var(--text-3)', lineHeight: 1.5 }}>
              当前对局暂无角色列表，进入对局后可在此单独静音某个角色。
            </div>
          ) : (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {charNames.map(name => {
                const m = isCharacterMuted(name);
                return (
                  <button
                    key={name}
                    className={`chip ${m ? 'active' : ''}`}
                    onClick={() => toggleCharacterMuted(name)}
                    title={m ? `恢复「${name}」的语音` : `静音「${name}」的语音`}
                    style={m ? { color: '#ff9b9b', borderColor: 'rgba(255,107,107,0.45)' } : undefined}
                  >{m ? '🔇' : '🔊'} {name}</button>
                );
              })}
            </div>
          )}
          <div style={{ fontSize: 11, color: 'var(--text-3)', marginTop: 6, lineHeight: 1.5 }}>
            静音角色消息旁的 🎙 按钮将变灰不可播放；顶栏 🔊 可全局静音/恢复。
          </div>
        </div>
      )}
    </header>
  );
}
