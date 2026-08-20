/**
 * GalStage.tsx — 舞台（P-0810-02）
 *
 * chat 模式：左立绘区 + 右对话框列（gal 典型布置）。
 * 2d 模式：整屏 2D 占位背景（CSS 渐变夜空 + 像素山丘 + 星点 + 站台占位），底部浮层对话框。
 * 说话者驱动：activeSpeakerId → 说话者立绘放大置前，其余置灰。
 */
import { useGalStore } from './GalStore';
import type { GalPortraitState } from './GalStore';
import { GalCharacter } from './GalCharacter';
import { GalDialogBox } from './GalDialogBox';
import { GalChoiceBar } from './GalChoiceBar';
import type { GalSpeaker } from './galDemoData';

/**
 * P-0810-03：取某角色当前应展示的立绘 URL。
 * 规则：backendId 映射 → 该后端角色立绘状态 → 当前选中帧 → 回退 avatar → 无图 undefined（占位）。
 * P-0810-06：优先透明版（{frame}_t，RMBG 抠背景产物）——立绘叠加无底色更融合，无 _t 回退原图。
 * P-0818-E：全身立绘（fullbody）——默认/头像选中时优先展示全身立绘（透明版 fullbody_t 优先），
 *   有全身立绘时角色以全身形态登场；用户显式切了表情帧则仍展示该表情（透明版优先），
 *   表情帧缺失时回退全身立绘再回退头像。
 */
export function portraitUrlFor(speaker: GalSpeaker, portraits: Record<string, GalPortraitState>): string | undefined {
  let bid = speaker.backendId;
  // P-0818-F：无 backendId 时，用 speaker.name 在 portraits 中按 name 查找
  if (!bid) {
    const match = Object.values(portraits).find(p => p.name === speaker.name);
    if (match) bid = match.backendId;
  }
  if (!bid) return undefined;
  let p: GalPortraitState | undefined = portraits[bid];
  // P-0818-F：bid 找不到 portrait 时，按 name 模糊匹配（后端 recoverOrphan 的 name 可能等于 id）
  if (!p) {
    p = Object.values(portraits).find(pt => pt.name === speaker.name || pt.backendId === speaker.name);
  }
  if (!p) return undefined;
  const sel = p.selectedFrame;
  const fullbody = p.frames['fullbody_t'] || p.frames['fullbody'];
  // 用户显式选了表情帧（非 avatar）→ 展示该表情；缺帧时回退全身立绘 → 头像
  if (sel && sel !== 'avatar') {
    return p.frames[`${sel}_t`] || p.frames[sel] || fullbody || p.frames['avatar_t'] || p.frames['avatar'];
  }
  // 默认/头像选中 → 优先全身立绘（透明版），其次头像
  return fullbody || p.frames['avatar_t'] || p.frames['avatar'];
}

/** 聊天模式：左立绘区 + 右对话框列 */
function ChatStage() {
  const speakers = useGalStore(s => s.speakers);
  const activeId = useGalStore(s => s.activeSpeakerId);
  const portraits = useGalStore(s => s.portraits);
  const npcs = speakers.filter(s => !s.isPlayer);

  return (
    <div className="gal-stage gal-stage-chat">
      <div className="gal-stage-left">
        <div className="gal-stage-left-label">✦ 登场角色</div>
        <div className="gal-chars">
          {npcs.map(sp => (
            <GalCharacter
              key={sp.id}
              speaker={sp}
              active={activeId === sp.id}
              size={5}
              imageUrl={portraitUrlFor(sp, portraits)}
            />
          ))}
        </div>
        <div className="gal-stage-left-tip">说话者会放大置前，其余角色半透明置灰</div>
      </div>
      <div className="gal-stage-right">
        <GalDialogBox />
        <GalChoiceBar />
      </div>
    </div>
  );
}

/** 2D 模式：背景保留 2D 占位界面（CSS 模拟，Phaser 集成后续），底部浮层对话框 */
function Stage2D() {
  const speakers = useGalStore(s => s.speakers);
  const activeId = useGalStore(s => s.activeSpeakerId);
  const portraits = useGalStore(s => s.portraits);
  const npcs = speakers.filter(s => !s.isPlayer);

  return (
    <div className="gal-stage gal-stage-2d">
      <div className="gal-2d-scene">
        <div className="gal-2d-stars" aria-hidden />
        <div className="gal-2d-moon" aria-hidden>
          <span className="gal-2d-moon-inner" />
        </div>
        <div className="gal-2d-hills gal-2d-hills-back" aria-hidden />
        <div className="gal-2d-hills gal-2d-hills-front" aria-hidden />
        <div className="gal-2d-platform">
          <span className="gal-2d-sign">【 星光站台 · 7 号月台 】</span>
          <span className="gal-2d-tag">2D 地图区域（Phaser 集成后续）</span>
        </div>
        <div className="gal-2d-chars">
          {npcs.map(sp => (
            <GalCharacter
              key={sp.id}
              speaker={sp}
              active={activeId === sp.id}
              size={3}
              imageUrl={portraitUrlFor(sp, portraits)}
            />
          ))}
        </div>
        <div className="gal-2d-scene-label">◆ 2D 模式：背景保留 2D 界面，对话框浮层于底部</div>
      </div>
      <div className="gal-2d-overlay">
        <GalDialogBox />
        <GalChoiceBar />
      </div>
    </div>
  );
}

export function GalStage() {
  const mode = useGalStore(s => s.mode);
  return mode === '2d' ? <Stage2D /> : <ChatStage />;
}
