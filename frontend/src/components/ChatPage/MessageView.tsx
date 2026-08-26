/**
 * MessageView.tsx — 消息渲染（agent/arbiter/user/system 单条视图 + 系统行）
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原 MessageView 函数组件）。
 * P-0810-01（AI 生图）：聊天气泡旁显示角色头像图（GET /api/ai-image/status 懒加载映射，
 * 无头像回退字母头像）；消息若带 imageUrl 字段则气泡内/下渲染图片。
 */
import { useEffect, useState } from 'react';
import type { AppMessage } from '../../types';
import { colorFor, trackModeName } from './chatUtils';
import { fetchAvatarMap } from './aiAvatar';
import { TtsPlayButton } from '../TtsPlayButton';

export function MessageView({ msg }: { msg: AppMessage }) {
  // P-0810-01：角色名 → 头像 URL 映射（模块级缓存，只拉一次；失败空表零破坏）
  const [avatarMap, setAvatarMap] = useState<Record<string, string>>({});
  useEffect(() => {
    let alive = true;
    fetchAvatarMap().then(map => { if (alive) setAvatarMap(map); });
    return () => { alive = false; };
  }, []);

  if (msg.role === 'arbiter') {
    return (
      <div className="arbiter-box">
        <div className="message-meta"><strong>主控整合</strong></div>
        <div>{msg.content}</div>
      </div>
    );
  }

  if (msg.role === 'user') {
    return (
      <div className="message user">
        <div className="message-body">
          <div className="message-meta">主控输入</div>
          <div className="bubble">
            {msg.content}
            {msg.imageUrl && <img className="ai-msg-image" src={msg.imageUrl} alt="image" loading="lazy" />}
          </div>
        </div>
      </div>
    );
  }

  const color = colorFor(msg.name);
  const avatarUrl = avatarMap[msg.name];
  return (
    <div className="message">
      {avatarUrl ? (
        <img className="avatar ai-avatar-img" src={avatarUrl} alt={msg.name} loading="lazy" />
      ) : (
        <div className="avatar" style={{ background: color }}>{msg.name?.[0] || '?'}</div>
      )}
      <div className="message-body">
        <div className="message-meta">
          <strong style={{ color }}>{msg.name}</strong>
          <span>{msg.track_label || msg.track_id}</span>
          <span>{trackModeName(msg.track_mode)}</span>
          {/* P-0817-A（前端接入）：AI 对话消息播放按钮 —— 点击合成并播放本消息语音
              （本地角色卡声线优先，后端按角色名解析兜底；同 key 播放中再点停止） */}
          {msg.content && (
            <TtsPlayButton
              id={`${msg.name}@${msg.timestamp}#${msg.content.slice(0, 16)}`}
              text={msg.content}
              character={msg.name}
            />
          )}
        </div>
        {/* P-0802-M：流式草稿带闪烁光标（ChatGPT 式逐字渲染）；agent_output 结算后光标消失。*/}
        <div className="bubble">
          {msg.content}{msg.streaming && <span className="stream-caret">▌</span>}
          {msg.imageUrl && <img className="ai-msg-image" src={msg.imageUrl} alt="image" loading="lazy" />}
        </div>
      </div>
    </div>
  );
}
