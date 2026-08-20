import { useState } from 'react';
import { useAppStore } from '../store/appStore';

/**
 * 侧边滚动公告栏（演讲+广播合并地基 UI，调研报告 §2.3/§3.4）。
 *
 * <p>展示全部公告历史（新消息插顶部，最多 50 条，由 store 保证），
 * 每条按级别配色；演讲（area/speech）带「区域」标记，全局公告带「全服」标记。
 *
 * <p>P1-8：①不再由 App.tsx 全局挂载——仅在 2D 游戏视图（PhaserSimulationView）
 * 内挂载，传 inline 后改为跟随 2D 面板的绝对定位（不再常驻视口右上角）；
 * ②空态不再占位常驻（无公告时不渲染容器）；③提供「×」收起按钮（重新展开
 * 可点 2D 控制条上的「📢」开关关→开）。
 */
export function AnnouncementTicker({ inline }: { inline?: boolean }) {
  const announcements = useAppStore(s => s.announcements);
  const [open, setOpen] = useState(true);

  // P1-8：空态默认隐藏（等 AI 演讲/广播到达后再出现，不占位）
  if (announcements.length === 0) return null;
  if (!open) return null;

  return (
    <div className={`ann-ticker ${inline ? 'inline' : ''}`}>
      <div className="ann-ticker-title">
        <span>📢 公告栏</span>
        <button
          className="ann-ticker-close"
          onClick={() => setOpen(false)}
          title="收起公告栏（可在 2D 控制条「📢」开关重新打开）"
        >×</button>
      </div>
      {announcements.map(a => (
        <div key={a.id} className={`ann-item ann-item-${(a.level || '').toLowerCase()}`}>
          <span className="ann-tag">{a.level}</span>
          <span className="ann-scope">{a.channel === 'area' ? '区域' : '全服'}</span>
          <span className="ann-speaker">{a.speaker}</span>
          <span className="ann-text">{a.text}</span>
        </div>
      ))}
    </div>
  );
}
