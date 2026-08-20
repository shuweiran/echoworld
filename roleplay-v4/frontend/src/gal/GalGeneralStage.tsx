/**
 * GalGeneralStage.tsx — 一般模式 Gal 舞台（P-0810-08 + P-0810-15 + P-0810-18 分层改造 + P-0811-G）
 *
 * P-0810-18（主人需求：立绘移到对话框后方——Galgame 分层布局，调研文档 §5/§6）：
 *  - 默认 `.galg-stage-layered` 分层 DOM（从底到顶）：
 *      ① `.galg-bg`（z0 背景层，P-0810-15 零改动：真实背景 url 或场景色渐变占位）
 *      ② `.galg-sprites`（z1 立绘层：absolute 全屏 pointer-events:none；槽位 bottom:0 height:78%，
 *        立绘底部对齐、被底部对话框遮住下半身=标准半身像效果）
 *      ③ `.galg-click-catcher`（z1.5 全屏点击推进：点空白处=打字中跳过/完成=下一句）
 *      ④ `.galg-foreground`（z2 底部居中：候选区(上方) + 对话框 + 输入框，半透明深底盖住立绘下部）
 *  - 立绘槽位规则（纯渲染函数 allocateSlots，数据层零改动）：
 *      单角色居中 / 双角色左右分列 / 三+左中右摊开（说话者居中、其余按顺序补左右，只显 3 槽=auto-hide 其余）；
 *      P-0811-G（A-7）：>4 角色只显当前说话人立绘（居中单槽），其余不显示——取消 NPC≥4 回落 side。
 *  - 说话者高亮（scale 1.04 + 发光/亮度）、非说话者弱化（opacity ~.45 + grayscale），切换 0.35s 平滑过渡；
 *  - 左立绘布局 `.galg-stage-side`（P-0810-15）保留为显式变体（layout='side' 才启用）。
 *
 * P-0811-G（用户 UI 反馈 A-3）：候选/选项区移到对话框上方（GalChoicesArea 在 GalDialogBox 之前），
 * 仅玩家回合显示（isPlayerTurn 门控在 GalChoicesArea 内）；输入区（GalInputArea）仍在对话框下方。
 *
 * P-0810-15 背景槽位（配合后端 P-0810-14）：scene 变化 → POST /api/ai-image/scene-background → url；
 * 后端未就绪/生成中/失败 → 渐变占位（不阻塞 UI）；半透明遮罩（.galg-bg-veil）暗化保证可读性。
 */
import { useEffect, useRef, useState } from 'react';
import { useGalStore } from './GalStore';
import { GalCharacter } from './GalCharacter';
import { GalDialogBox } from './GalDialogBox';
import { GalChoiceBar } from './GalChoiceBar';
import { portraitUrlFor } from './GalStage';
import { fetchSceneBackground, type BgState } from './galBackground';
import { GalChatStage, allocateSlots } from './GalChatStage';

export { allocateSlots };

/** 兼容旧导入：allocateSlots 已迁至 GalChatStage（批3 方向5 公共核心），此处 re-export */

interface GalGeneralStageProps {
  /** 场景名（起局后从 GET /api/state 解析；背景端点入参，空则纯渐变占位） */
  scene?: string;
  /**
   * 布局：'layered'=分层（默认，立绘在对话框后方）/ 'side'=左立绘列（P-0810-15 变体）。
   * 缺省 'layered'（P-0811-G A-6：取消「NPC≥4 自动回落 side」——显式 side 才用左立绘列）。
   */
  layout?: 'layered' | 'side';
}

/** NPC 槽位分配（纯函数，P-0815-B 导出供 ScriptGalChatPanel 立绘舞台复用）：
 *  单角色居中 / 双角色左右 / 3-4 左中右摊开（说话者居中）/ >4 只显说话人 */
export function GalGeneralStage({ scene = '', layout = 'layered' }: GalGeneralStageProps) {
  const speakers = useGalStore(s => s.speakers);
  const activeId = useGalStore(s => s.activeSpeakerId);
  const portraits = useGalStore(s => s.portraits);
  // P-0811-G：玩家角色名（导演模式无玩家 → livePlayerName 空）——决定是否显示输入框/候选区
  const livePlayerName = useGalStore(s => s.livePlayerName);
  const hasPlayer = !!livePlayerName && String(livePlayerName).trim().length > 0;

  // ── 背景槽位（P-0810-15）：scene 变化 → 调后端 → 成功替换 / 失败保持渐变占位 ──
  const [bgUrl, setBgUrl] = useState<string | null>(null);
  const [bgState, setBgState] = useState<BgState>('placeholder');
  const requestedSceneRef = useRef('');

  useEffect(() => {
    const key = (scene || '').trim();
    // 无 scene 或同 scene 已请求过 → 不重复调（轮询元信息同值不重发）
    if (!key || key === requestedSceneRef.current) return;
    requestedSceneRef.current = key;
    let alive = true;
    setBgState('loading');
    void fetchSceneBackground(key).then(url => {
      if (!alive) return;
      setBgUrl(url);
      setBgState(url ? 'ready' : 'placeholder');
    });
    return () => { alive = false; };
  }, [scene]);

  const npcs = speakers.filter(s => !s.isPlayer);
  // 当前说话角色（activeSpeakerId 可能是 'player'（玩家节点）→ npcs 无此 id → 无高亮对象）
  const activeSp = activeId ? npcs.find(sp => sp.id === activeId) : undefined;

  // P-0811-G（A-6）：取消「NPC≥4 自动回落 side」——显式 layout='side' 才启用左立绘列
  const isSide = layout === 'side';

  if (!isSide) {
    /* ══ 默认：Galgame 分层布局（P-0815-F 批3 方向5：抽公共核心 GalChatStage） ══ */
    return (
      <GalChatStage
        scene={scene}
        backgroundImage={bgUrl ?? undefined}
        hasPlayer={hasPlayer}
        showClickCatcher
        bgTag={bgState === 'loading' ? <div className="galg-bg-tag" aria-hidden>🎨 背景生成中…</div> : null}
      />
    );
  }

  return (
    <div className="galg-stage galg-stage-side">
      {/* ══ 变体：左立绘列（P-0810-15，显式 layout='side' 才启用；原样保留） ══ */}
      <div className="galg-side-portraits">
        {activeSp ? (
          <div key={activeSp.id} className="galg-side-slot galg-side-active">
            <GalCharacter
              speaker={activeSp}
              active
              size={5}
              imageUrl={portraitUrlFor(activeSp, portraits)}
            />
          </div>
        ) : (
          <div className="galg-side-idle">
            <span className="galg-side-idle-icon">✦</span>
            <span className="galg-side-idle-text">角色登场中…</span>
            <span className="galg-side-idle-hint">说话的角色会在此现身</span>
          </div>
        )}
      </div>
      <div className="galg-side-main">
        <div className="galg-bottom">
          <GalDialogBox />
          {/* P-0811-G：导演模式（无玩家）不显示输入框/候选区 */}
          {hasPlayer && <GalChoiceBar />}
        </div>
      </div>
    </div>
  );
}
