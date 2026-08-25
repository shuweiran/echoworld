/**
 * GalChatStage.tsx — 公共 gal 聊天布局核心（P-0815-F 批3 方向5）
 *
 * 三模式（ScriptGalChatPanel 剧本杀 / SimGalChatPanel 2D / GalGeneralView 一般模式）
 * 共用的 stage/dialog/input 组合，消除各自内联重复的舞台标记：
 *  - variant='stage'：分层立绘舞台（背景层 → 立绘层 → 可选点击推进层 → 前景
 *    [候选区 + 对话框 + 输入区]），GalGeneralStage（layered）与 ScriptGalChatPanel 共用；
 *  - variant='compact'：紧凑侧栏布局（顶部信息条 + 滚动对话框 + 候选/输入区），
 *    SimGalChatPanel 共用。
 *
 * 数据源：useGalStore 上下文感知 hook（宿主注入实例；无 Provider 回落默认单例）。
 * 输入区可用 inputSlot 覆盖（剧本杀按阶段门控输入区 / 锁定提示）。
 */
import type { CSSProperties, ReactNode } from 'react';
import { useState } from 'react';
import { useGalStore } from './GalStore';
import { liveSay } from './galSseAdapter';
import { GalDialogBox } from './GalDialogBox';
import { GalChoicesArea, GalInputArea } from './GalChoiceBar';
import { GalCharacter } from './GalCharacter';
import type { GalSpeaker } from './galDemoData';
import { portraitUrlFor } from './GalStage';
import { sceneGradient } from './galBackground';
import './gal.css';
import './galGeneral.css';

export interface GalChatStageProps {
  /** 布局变体：stage=分层立绘舞台（一般/剧本杀）/ compact=紧凑侧栏（2D） */
  variant?: 'stage' | 'compact';
  /** 场景名（背景渐变占位源；stage 变体用） */
  scene?: string;
  /** 已生成背景 URL（stage 变体：真实背景优先，无则渐变占位） */
  backgroundImage?: string;
  /** 是否有玩家（stage 变体：显示候选区+输入区；导演模式无玩家仅对话框） */
  hasPlayer: boolean;
  /** stage 变体：是否显示整屏点击推进层（一般模式 true；剧本杀 false——靠按钮/输入驱动） */
  showClickCatcher?: boolean;
  /** compact 变体：顶部信息条（2D 群头 + tip）；stage 变体由调用方自行放在舞台上/下方 */
  header?: ReactNode;
  /** 自定义输入区内容（剧本杀阶段门控输入 / 锁定提示；缺省 GalInputArea） */
  inputSlot?: ReactNode;
  /** stage 变体：背景生成中 tag（一般模式） */
  bgTag?: ReactNode;
  /** stage 变体：前景 gap（剧本杀 6） */
  foregroundGap?: number;
  /** 容器额外 className（stage 变体挂在 .galg-stage 上） */
  className?: string;
  /** 容器 style（stage 变体：flex:1/minHeight/borderRadius/overflow 等） */
  style?: CSSProperties;
}

/** NPC 槽位分配（纯函数；原 GalGeneralStage 导出，批3 方向5 迁至公共核心）：
 *  单角色居中 / 双角色左右 / 3-4 左中右摊开（说话者居中）/ >4 只显说话人 */
export function allocateSlots(
  npcs: GalSpeaker[],
  activeId: string | null,
): { left?: GalSpeaker; center?: GalSpeaker; right?: GalSpeaker } {
  if (npcs.length === 0) return {};
  if (npcs.length === 1) return { center: npcs[0] };
  if (npcs.length === 2) return { left: npcs[0], right: npcs[1] };
  if (npcs.length > 4) {
    const active = activeId ? npcs.find(n => n.id === activeId) : undefined;
    return active ? { center: active } : {};
  }
  const active = activeId ? npcs.find(n => n.id === activeId) : undefined;
  const rest = active ? npcs.filter(n => n.id !== active.id) : npcs;
  return {
    center: active || npcs[0],
    left: rest[0],
    right: active ? rest[1] : rest[2],
  };
}

/** 槽位渲染（立绘 + 说话高亮；无 _t 透明图 → 底部渐隐兜底 mask） */
function renderSlot(sp: GalSpeaker | undefined, slotCls: string, activeId: string | null, portraits: any) {
  if (!sp) return null;
  const url = portraitUrlFor(sp, portraits);
  const hasTransparent = !!url && url.includes('_t');
  return (
    <div
      key={`${slotCls}-${sp.id}`}
      className={`galg-sprite-slot ${slotCls}${activeId === sp.id ? ' galg-slot-active' : ''}`}
    >
      <GalCharacter
        speaker={sp}
        active={activeId === sp.id}
        size={5}
        fill
        masked={!hasTransparent}
        imageUrl={url}
      />
    </div>
  );
}

/** 导演模式输入框（无玩家时显示，与主控对话） */
function DirectorInput() {
  const [draft, setDraft] = useState('');
  const override = useGalStore(s => s.liveSayOverride);
  const send = () => {
    const text = draft.trim();
    if (!text) return;
    if (override) override(text); else liveSay(text);
    setDraft('');
  };
  return (
    <div className="galg-input-slot">
      <div className="galg-input-wrap">
        <input
          className="galg-input"
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder="与主控对话…"
        />
        <button className="galg-send" onClick={send} disabled={!draft.trim()}>发送</button>
      </div>
    </div>
  );
}

export function GalChatStage({
  variant = 'stage',
  scene = '',
  backgroundImage,
  hasPlayer,
  showClickCatcher = false,
  header,
  inputSlot,
  bgTag,
  foregroundGap,
  className = '',
  style,
}: GalChatStageProps) {
  const speakers = useGalStore(s => s.speakers);
  const activeId = useGalStore(s => s.activeSpeakerId);
  const portraits = useGalStore(s => s.portraits);
  const advance = useGalStore(s => s.advance);

  /* ══ compact：2D 侧栏布局（群头/tip + 滚动对话框 + 候选/输入区） ══ */
  if (variant === 'compact') {
    return (
      <div
        className={`sim-gal-chat ${className}`.trim()}
        style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1, ...style }}
      >
        {header}
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          <GalDialogBox />
        </div>
        <div style={{ flexShrink: 0, paddingTop: 6 }}>
          <GalChoicesArea />
          {inputSlot ?? <GalInputArea />}
        </div>
      </div>
    );
  }

  /* ══ stage：分层立绘舞台（bg → sprites → 点击推进 → 前景） ══ */
  const npcs = speakers.filter((s: GalSpeaker) => !s.isPlayer);
  const slots = allocateSlots(npcs, activeId);
  return (
    <div className={`galg-stage galg-stage-layered ${className}`.trim()} style={style}>
      {/* 背景层（真实背景 url 或场景色渐变占位） */}
      <div
        className="galg-bg"
        style={{ backgroundImage: backgroundImage ? `url("${backgroundImage}")` : sceneGradient(scene) }}
        aria-hidden
      >
        <div className="galg-bg-veil" />
        {bgTag}
      </div>

      {/* 立绘层（absolute 全屏，pointer-events 穿透；槽位底部对齐，被对话框遮下半身） */}
      <div className="galg-sprites" aria-hidden>
        {npcs.length === 0 ? (
          <div className="galg-sprites-idle">
            <span>✦</span>
            <span>角色登场中…</span>
          </div>
        ) : (
          <>
            {renderSlot(slots.left, 'galg-slot-left', activeId, portraits)}
            {renderSlot(slots.center, 'galg-slot-center', activeId, portraits)}
            {renderSlot(slots.right, 'galg-slot-right', activeId, portraits)}
          </>
        )}
      </div>

      {/* 点击推进层（z1.5；一般模式开启：点空白=跳过或下一句） */}
      {showClickCatcher && (
        <div className="galg-click-catcher" onClick={advance} title="点击推进" aria-hidden />
      )}

      {/* 前景层（z2 底部居中）：候选区(上方) + 对话框 + 输入区 */}
      <div className="galg-foreground" style={foregroundGap !== undefined ? { gap: foregroundGap } : undefined}>
        {hasPlayer && (
          <div className="galg-choices-slot">
            <GalChoicesArea />
          </div>
        )}
        <GalDialogBox />
        {hasPlayer && <div className="galg-input-slot">{inputSlot ?? <GalInputArea />}</div>}
        {!hasPlayer && <DirectorInput />}
      </div>
    </div>
  );
}
