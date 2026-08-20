/**
 * ScriptVnReveal.tsx — 搜证 VN 演出首版弹层（P-0816-I，ui-proto-v2）
 *
 * 决策 U13（MVP 前端拼装，零新端点）：点击已搜地点回看 / 搜到新线索时弹出
 * VN 面板——角色色大头像 + 名字铭牌 + 线索 content 拼装文本（打字机逐行）+
 * 「🔍 记录线索」按钮（新线索态）/「✓ 已记录」（回看态）。
 * 文本由 actionUtils.buildVnLines 前端拼装（线索 content）；阶段三 API-14
 * （action_playback 后端模板）为可选升级点，接通时替换 lines 数据源即可。
 *
 * 打字机资产：复用 gal 打字机交互范式（26ms/字符逐行推进，完成态显示按钮；
 * 点击文本跳过当前行）。不改动 ScriptGalChatPanel 内部逻辑（独立弹层）。
 */
import { useEffect, useRef, useState } from 'react';
import { roleColorFor, avatarGradientFor } from './chatUtils';

export interface ScriptVnRevealProps {
  open: boolean;
  /** 演出角色名（名字铭牌；回看/搜证引导人） */
  name: string;
  /** VN 拼装文本行（buildVnLines 产物） */
  lines: string[];
  /** true=回看（已记录）；false=新线索（显示「🔍 记录线索」按钮） */
  recorded: boolean;
  onClose: () => void;
}

export function ScriptVnReveal({ open, name, lines, recorded, onClose }: ScriptVnRevealProps) {
  const [typed, setTyped] = useState<number[]>(() => lines.map(() => 0));
  const [done, setDone] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const linesRef = useRef(lines);
  linesRef.current = lines;

  // 打开时重置并启动打字机（26ms/字符逐行，对齐原型 VN 动画节奏）
  useEffect(() => {
    if (!open) return;
    setTyped(lines.map(() => 0));
    setDone(false);
    const interval = setInterval(() => {
      const ls = linesRef.current;
      setTyped(prev => {
        let idx = -1;
        let advanced = false;
        for (let i = 0; i < ls.length; i++) {
          if (prev[i] < ls[i].length) { idx = i; break; }
        }
        const next = prev.slice();
        if (idx >= 0) {
          next[idx] = Math.min(ls[idx].length, prev[idx] + 1);
          advanced = true;
        }
        if (!advanced) {
          setDone(true);
          if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
        }
        return next;
      });
    }, 26);
    timerRef.current = interval;
    return () => { if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; } };
  }, [open, lines]);

  // 点击文本：当前行未打完 → 直接打完；全部打完 → 无操作（按钮区交互）
  const skipLine = () => {
    setTyped(prev => {
      const ls = linesRef.current;
      const next = prev.slice();
      for (let i = 0; i < ls.length; i++) {
        if (next[i] < ls[i].length) { next[i] = ls[i].length; break; }
      }
      if (next.every((v, i) => v >= ls[i].length)) setDone(true);
      return next;
    });
  };

  if (!open) return null;
  const color = roleColorFor(name || '你');

  return (
    <div className="proto-vn-mask" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="proto-vn-dialog">
        <div className="proto-vn-stage">
          {/* 角色色大头像（P-0816-U：角色渐变底，对齐原型 vn-avatar av-* 渐变） */}
          <span className="avatar proto-vn-avatar" style={{ background: avatarGradientFor(name || '?') }}>{name ? name[0] : '?'}</span>
          {/* 名字铭牌（角色色底白字，对齐原型 vn-nameplate） */}
          <div className="proto-vn-nameplate" style={{ background: color }}>{name || '搜证'}</div>
          <span className="proto-vn-caption">VN 发现演出 · 线索内容拼装（U13 前端拼装）</span>
          {/* 半透明文字框 + 逐行打字机 */}
          <div className="proto-vn-textbox" onClick={skipLine} title="点击跳过本行">
            {lines.map((line, i) => (
              <p key={i} className={`proto-vn-line${i > 0 ? ' dim' : ''}${!done && i === lines.length - 1 ? ' typing' : ''}`}>
                {line.slice(0, typed[i])}
              </p>
            ))}
            {lines.length === 0 && <p className="proto-vn-line">……（此处暂无线索文本）</p>}
          </div>
          <div className="proto-vn-actions">
            {recorded ? (
              <span className="proto-vn-recorded">✓ 已记录（回看不消耗行动点 · U7）</span>
            ) : (
              <button className="btn btn-smallall proto-vn-btn" onClick={onClose} title="线索已由搜证行动收录进证据袋">
                🔍 记录线索
              </button>
            )}
            <button className="btn btn-smallall proto-vn-btn ghost" onClick={onClose}>关闭</button>
          </div>
        </div>
      </div>
    </div>
  );
}
