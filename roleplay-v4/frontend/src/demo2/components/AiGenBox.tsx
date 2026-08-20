/**
 * AiGenBox.tsx — AI 生成聊天框（共享，剧本/场景/角色生成统一入口）
 *
 * 一个聊天框搞定 AI 生成：输入主题/想法 → mock 流式生成 → 产出回调。
 */
import { useState } from 'react';

export interface AiGenResult {
  text: string;
  /** 结构化产出（可选） */
  data?: unknown;
}

interface AiGenBoxProps {
  title?: string;
  placeholder?: string;
  hint?: string;
  /** 执行生成的函数（mock 层） */
  generate: (prompt: string) => Promise<AiGenResult>;
  onResult: (r: AiGenResult) => void;
  /** 生成中的阶段文案 */
  stages?: string[];
}

export function AiGenBox({ title = '✨ AI 生成', placeholder = '输入你的主题或想法…', hint, generate, onResult, stages = ['AI 正在构思…'] }: AiGenBoxProps) {
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [stage, setStage] = useState('');
  const [chat, setChat] = useState<{ who: 'user' | 'ai'; text: string }[]>([]);

  const run = async () => {
    const p = prompt.trim();
    if (!p || busy) return;
    setChat(c => [...c, { who: 'user', text: p }]);
    setPrompt('');
    setBusy(true);
    for (let i = 0; i < stages.length; i++) {
      setStage(stages[i]);
      await new Promise(r => setTimeout(r, 450));
    }
    try {
      const r = await generate(p);
      setChat(c => [...c, { who: 'ai', text: r.text }]);
      onResult(r);
    } catch (e) {
      setChat(c => [...c, { who: 'ai', text: '生成失败：' + (e as Error).message }]);
    } finally {
      setBusy(false);
      setStage('');
    }
  };

  return (
    <div>
      {title && <div className="gen-step-head" style={{ marginBottom: 10 }}><span className="gen-step-icon">🤖</span><span className="gen-step-title">{title}</span></div>}
      {chat.length > 0 && (
        <div className="chat-box" style={{ height: 220, marginBottom: 10 }}>
          {chat.map((m, i) => (
            <div key={i} className={`msg ${m.who === 'user' ? 'msg-player' : 'msg-bot'}`}>
              {m.who === 'ai' && <div className="msg-who">AI</div>}
              {m.text}
            </div>
          ))}
          {busy && <div className="msg msg-bot"><div className="msg-who">AI</div>{stage || '…'}</div>}
        </div>
      )}
      <div className="chat-input-row">
        <input
          value={prompt}
          onChange={e => setPrompt(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); run(); } }}
          placeholder={placeholder}
          disabled={busy}
        />
        <button className="btn2 btn2-primary" onClick={run} disabled={busy || !prompt.trim()}>
          {busy ? '生成中…' : '发送'}
        </button>
      </div>
      {hint && <div className="hint" style={{ marginTop: 8 }}>{hint}</div>}
    </div>
  );
}
