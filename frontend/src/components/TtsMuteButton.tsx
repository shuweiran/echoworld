/**
 * TtsMuteButton.tsx — 全局语音静音开关按钮（P-0817-I）
 *
 * 局内声音控制入口：🔊 开启 / 🔇 已静音（全局）。
 * - 状态持久化于 localStorage（mimoTts 单例 globalMuted）；
 * - 静音时立即停掉正在播放的语音，所有消息 🎙 按钮置 🔇 灰显不可点；
 * - 切换经 emit 触发所有订阅者重渲染（顶栏按钮与消息按钮实时同步）。
 *
 * 用法：<TtsMuteButton />（默认 .btn 对局顶栏样式）或
 *      <TtsMuteButton className="galg-top-btn" />（Gal 视图顶栏样式）。
 */
import { useEffect, useState } from 'react';
import { isGlobalMuted, setGlobalMuted, subscribeTtsStatus } from '../services/mimoTts';

export function TtsMuteButton({ className }: { className?: string }) {
  const [muted, setMuted] = useState(isGlobalMuted);

  // 订阅 TTS 状态变化（含静音切换 emit）——与其他按钮实时同步
  useEffect(() => {
    const update = () => setMuted(isGlobalMuted());
    update();
    return subscribeTtsStatus(update);
  }, []);

  return (
    <button
      type="button"
      className={className || 'btn tts-mute-btn'}
      title={muted ? '已全局静音 — 点击恢复语音' : '语音开启 — 点击全局静音'}
      aria-label={muted ? '恢复语音' : '全局静音'}
      onClick={() => setGlobalMuted(!muted)}
      style={muted ? { color: '#ff9b9b', borderColor: 'rgba(255,107,107,0.45)' } : undefined}
    >
      {muted ? '🔇 已静音' : '🔊 语音'}
    </button>
  );
}
