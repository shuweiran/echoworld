/**
 * GalDialogBox.tsx — 对话框（P-0810-02 + P-0810-06）
 *
 * 角色名 + 小头像 + 打字机文本 + 点击推进（打字中=跳过，完成态=下一条）。
 * 旁白节点：居中斜体无头像；choice 节点：显示提问文本并提示等待玩家。
 * 上屏：最近 2-3 条 log 淡灰显示在对话框上方。
 *
 * P-0810-06（live 模式）：
 *  - 说话者从 store.speakers 解析（demo 角色 + live 动态占位角色）；\n *  - kind=system（announcement）→ 旁白样式 + 系统名（📢 前缀）展示；\n *  - kind=player（user_input/本地回显）→ 玩家气泡样式（右对齐金色）；\n *  - 流式句（streamed）→ 打字机实时渲染，hint 显示「✦ 生成中…」；\n *  - 无消息等待态：已连接等待 / 未连接提示。
 */
import { useGalStore } from './GalStore';
import type { GalLiveMessage } from './GalStore';
import type { GalMessage } from './galDemoData';
import { speakerName, speakerOf } from './galDemoData';
import { GalSprite, GalNamePlate } from './GalCharacter';
import { portraitUrlFor } from './GalStage';
import { TtsPlayButton } from '../components/TtsPlayButton';

export function GalDialogBox() {
  const current = useGalStore(s => s.current);
  const typing = useGalStore(s => s.typing);
  const log = useGalStore(s => s.log);
  const choiceNode = useGalStore(s => s.choiceNode);
  const finished = useGalStore(s => s.finished);
  const liveMode = useGalStore(s => s.liveMode);
  const liveSessionId = useGalStore(s => s.liveSessionId);
  const hidePlayerBubbles = useGalStore(s => s.hidePlayerBubbles);
  const liveGameType = useGalStore(s => s.liveGameType);
  const livePhase = useGalStore(s => s.livePhase);
  const speakers = useGalStore(s => s.speakers);
  const portraits = useGalStore(s => s.portraits);
  const advance = useGalStore(s => s.advance);
  const start = useGalStore(s => s.start);

  if (finished) {
    return (
      <div className="gal-dialog gal-dialog-end" onClick={start}>
        <div className="gal-end-title">— THE END —</div>
        <div className="gal-end-sub">「星光列车」的故事到此告一段落</div>
        <div className="gal-end-hint">（点击任意处重新开始）</div>
      </div>
    );
  }

  // P-0810-06：live 模式等待态（已连接无消息 / 未连接）
  // P-0815-F（方向2，根因 C）：等待态文案阶段感知——剧本杀 SETUP/INVESTIGATION 显示「准备中」，
  // DISCUSSION 显示「讨论中」，不再用误导性的「点击输入框可发言」（非讨论阶段输入无回显）。
  if (liveMode && !choiceNode && !current) {
    const waitLabel = liveGameType === 'script'
      ? (String(livePhase).toLowerCase() === 'discussion' ? '💬 讨论进行中…' : '⏳ 对局准备中…（消息将在这里播放）')
      : (liveSessionId ? '◉ 已连接 · 等待对局消息…' : '○ 未连接对局');
    const waitHint = liveGameType === 'script'
      ? (String(livePhase).toLowerCase() === 'discussion' ? 'AI 发言 / 阶段变化将在这里播放' : '完整剧本生成中，搜证 / 讨论消息将在这里播放')
      : (liveSessionId ? 'AI 发言 / 公告 / 阶段变化将在这里播放' : '顶部切换「🔌 真实对局」并输入 session_id / 房间码连接');
    return (
      <div className="gal-dialog-wrap">
        <div className="gal-dialog gal-dialog-wait">
          <div className="gal-wait-label">{waitLabel}</div>
          <div className="gal-wait-hint">{waitHint}</div>
        </div>
      </div>
    );
  }

  const isChoice = !!choiceNode;
  // P-0810-08：呈现接管视图隐藏玩家气泡 —— 若消息流里仍出现玩家消息（正常不应发生），
  // 跳过渲染并自动推进，保证「玩家发言不显示气泡」语义不被绕过
  if (liveMode && hidePlayerBubbles && !isChoice
      && current && (current as GalLiveMessage).kind === 'player') {
    return (
      <div className="gal-dialog-wrap">
        <div className="gal-dialog gal-dialog-wait">
          <div className="gal-wait-label">◉ 已连接 · 等待对局消息…</div>
        </div>
      </div>
    );
  }

  // demo 消息（GalMessage：type/text）与 live 消息（GalLiveMessage：kind/name/streamed）字段不同，统一宽松访问
  const msg = current as (GalMessage & { kind?: string; name?: string; streamed?: boolean; level?: string }) | (GalLiveMessage & { type?: string }) | null;
  const showLog = log.slice(-3);

  let name = '';
  let color = 'var(--gal-text, #e8eef9)';
  let isNarrator = false;
  let isSystem = false;
  let isPlayerMsg = false;
  const streamed = !!msg?.streamed;
  if (isChoice) {
    name = speakerName('player');
    color = 'var(--gal-gold)';
  } else if (msg && (msg.type === 'narrator' || msg.kind === 'system' || msg.kind === 'narrator')) {
    // demo 旁白（type=narrator）与 live 公告（kind=system/narrator）统一旁白样式
    isNarrator = true;
    isSystem = msg.kind === 'system';
    // live 公告显示系统名；demo 旁白不显示名字（居中斜体）
    name = msg.kind ? (msg.name || '系统') : '';
  } else if (msg && msg.kind === 'player') {
    isPlayerMsg = true;
    name = msg.name || speakerName('player');
    color = 'var(--gal-gold)';
  } else if (msg) {
    const sp = speakers.find(x => x.id === msg.speakerId) ?? speakerOf(msg.speakerId);
    name = sp?.name ?? msg.name ?? msg.speakerId;
    color = sp?.color ?? 'var(--gal-text, #e8eef9)';
  }

  const text = isChoice
    ? (msg?.text ?? '')
    : typing
      ? typing.full.slice(0, typing.chars)
      : (msg?.text ?? '');
  const typingInProgress = !!typing && !typing.done;

  return (
    <div className="gal-dialog-wrap">
      {showLog.length > 0 && (
        <div className="gal-log">
          {showLog.map(l => (
            <div key={l.id} className={`gal-log-line${l.isPlayer ? ' gal-log-player' : ''}`}>
              <span className="gal-log-name">{l.name}</span> {l.text}
            </div>
          ))}
        </div>
      )}

      <div
        className={[
          'gal-dialog',
          isChoice ? 'gal-dialog-choice' : '',
          isNarrator ? 'gal-dialog-narrator' : '',
          isSystem ? 'gal-dialog-system' : '',
          isPlayerMsg ? 'gal-dialog-player' : '',
          streamed ? 'gal-dialog-stream' : '',
        ].filter(Boolean).join(' ')}
        onClick={advance}
      >
        {isChoice ? (
          <div className="gal-choice-question">
            <div className="gal-choice-label">❖ 轮到你了</div>
            <div className="gal-choice-text">{text}</div>
            <div className="gal-dialog-hint">▼ 请在下方选择或输入</div>
          </div>
        ) : isNarrator ? (
          <div className="gal-narrator-text">
            {isSystem && <div className="gal-system-name">{name}</div>}
            {text}{typingInProgress && <span className="gal-caret">▌</span>}
          </div>
        ) : isPlayerMsg ? (
          <>
            <div className="gal-dialog-head gal-dialog-head-right">
              <div className="gal-dialog-name" style={{ color }}>{name}</div>
              <div className="gal-dialog-avatar">
                {msg && <GalSprite speaker={speakerOf('player')!} size={2} />}
              </div>
            </div>
            <div className="gal-dialog-text">
              {text}{typingInProgress && <span className="gal-caret">▌</span>}
            </div>
            {/* P-0815-E：玩家自己的消息播完自动推进（GalStore.tick），不设「点击继续」等待按钮——
                发消息后等 AI 回复到达，回复直接自动显示；AI 消息保留 Gal 式点击推进 */}
            {typingInProgress && <div className="gal-dialog-hint">■ 点击跳过</div>}
          </>
        ) : (
          <>
            <div className="gal-dialog-head">
              <div className="gal-dialog-avatar">
                {msg && (() => {
                  const sp = speakers.find(x => x.id === msg.speakerId) ?? speakerOf(msg.speakerId);
                  if (!sp) return null;
                  const url = portraitUrlFor(sp, portraits);
                  if (url) return <img className="gal-dialog-avatar-img" src={url} alt={sp.name} style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 4, imageRendering: 'auto' }} />;
                  return sp.placeholder ? <GalNamePlate speaker={sp} size={2} /> : <GalSprite speaker={sp} size={2} />;
                })()}
              </div>
              <div className="gal-dialog-name" style={{ color }}>{name}</div>
            </div>
            <div className="gal-dialog-text">
              {text}{typingInProgress && <span className="gal-caret">▌</span>}
            </div>
            <div className="gal-dialog-hint" style={{ display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between' }}>
              <span>
                {typingInProgress
                  ? (streamed ? '✦ 生成中… 点击跳过' : '■ 点击跳过')
                  : '▼ 点击继续'}
              </span>
              {/* P-0817-A（前端接入）：AI 对话消息 TTS 播放按钮 —— 完成态显示于提示行右侧；
                  点击合成并播放本条发言语音（角色名声线解析；同 key 播放中再点停止） */}
              {!typingInProgress && msg && msg.kind === 'agent' && (
                <TtsPlayButton
                  id={`gal:${msg.id || `${name}@${(msg as { ts?: number }).ts || 0}`}`}
                  text={text}
                  character={name}
                  variant="gal"
                />
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
