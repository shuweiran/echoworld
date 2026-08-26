/**
 * TtsPlayButton.tsx — 对话消息 TTS 播放按钮（P-0817-A 后端 · 前端接入批次）
 *
 * 每条 AI 对话消息旁的 🎙 按钮：点击 → 调后端 MiMo 合成（loading 转圈）→ Web Audio 播放；
 * 播放中再点停止；合成/播放失败显示 ⚠️（tooltip 见原因，3s 后自动恢复）。
 * 视觉对齐项目设计语言（.btn-icon 圆角小按钮 + --text-2 次级色 + spin 动画）。
 *
 * 声线解析优先级（mimoTts.speak 内部）：显式 mode/voice_data > 本地角色卡（demo2 store）
 * > 后端角色库按 character 名解析（P-0817-A /voice-config 链路）。
 */
import { useMimoTts, isCharacterMuted, warmAudio } from '../services/mimoTts';

export interface TtsPlayButtonProps {
  /** 消息唯一标识（同 key 播放中再点 = 停止） */
  id: string;
  /** 合成文本（消息内容） */
  text: string;
  /** 角色名（声线解析锚点；显式声线缺省时生效） */
  character?: string;
  /** 显式声线（角色卡配置；任一存在即优先于 character 解析） */
  mode?: string;
  voiceData?: string;
  voice?: string;
  /** 语气描述（可选） */
  tone?: string;
  /** 按钮形态：message=消息流内联小按钮（默认）/ gal=Gal 对话框内按钮 */
  variant?: 'message' | 'gal';
  /** 可点条件（如 Gal 消息未播完时禁用） */
  disabled?: boolean;
}

export function TtsPlayButton({ id, text, character, mode, voiceData, voice, tone, variant = 'message', disabled }: TtsPlayButtonProps) {
  const { state, error, speak, stop, muted } = useMimoTts(id, character);

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (disabled || muted) return;
    if (state === 'playing') {
      stop();
    } else if (state !== 'loading') {
      // P-0817-N：点击手势内预热 AudioContext（fetch 合成数秒后 resume 会被 Chrome 拒绝 → 无声）
      warmAudio();
      void speak(text, { character, mode, voice_data: voiceData, voice, tone });
    }
  };

  // P-0817-I：静音态（全局静音 / 该角色单独静音）—— 按钮置 🔇 灰显不可点
  const icon = muted ? '🔇'
    : state === 'loading' ? '⏳' : state === 'playing' ? '⏹' : state === 'error' ? '⚠️' : '🎙';
  const title = muted
    ? (character && !isCharacterMuted(character) ? '已全局静音（顶栏 🔊 恢复）' : `已静音「${character || '该角色'}」（可在设置中恢复）`)
    : state === 'playing' ? '停止播放'
    : state === 'loading' ? '合成中…'
    : state === 'error' ? `播放失败：${error || '未知错误'}`
    : '播放语音';

  return (
    <button
      type="button"
      className={`tts-btn tts-btn-${variant}${state === 'loading' ? ' tts-loading' : ''}${state === 'playing' ? ' tts-playing' : ''}${state === 'error' ? ' tts-error' : ''}${muted ? ' tts-muted' : ''}`}
      title={title}
      aria-label={title}
      disabled={disabled || muted}
      onClick={handleClick}
    >
      <span className={state === 'loading' ? 'tts-spin' : undefined}>{icon}</span>
    </button>
  );
}
