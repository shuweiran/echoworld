import { useEffect, useRef, useState } from 'react';
import { useAppStore } from '../store/appStore';
import type { Announcement } from '../types';

/**
 * 中央顶部横幅（演讲+广播合并地基 UI，调研报告 §2.3/§3.4）。
 *
 * <p>从 store.bannerQueue 取未展示过的消息：打字机逐字渲染（CJK 40ms/字），
 * 播完后停留 2.5s 自动消失并播放下一条；队列上限由 store 保证（≤3）。
 * 级别不同配色不同（SYSTEM=金色 / EVENT=蓝 / PLAYER=绿 / NPC=灰）。
 *
 * <p>P1-8：不再由 App.tsx 全局挂载——横幅仅在 2D 游戏视图（PhaserSimulationView）
 * 内挂载，传 inline 后改为跟随 2D 面板的绝对定位（不再常驻视口顶部）。
 */
export function AnnouncementBanner({ inline }: { inline?: boolean }) {
  const bannerQueue = useAppStore(s => s.bannerQueue);
  const [current, setCurrent] = useState<Announcement | null>(null);
  const [visible, setVisible] = useState(false);
  const [typed, setTyped] = useState('');
  const shownIds = useRef(new Set<string>());

  useEffect(() => {
    if (current) return; // 正在播放下一条，等本条结束
    const next = bannerQueue.find(a => !shownIds.current.has(a.id));
    if (!next) return;
    shownIds.current.add(next.id);
    setCurrent(next);
    setTyped('');
    setVisible(false);

    const typeTimer = setInterval(() => {
      setTyped(prev => {
        if (prev.length >= next.text.length) {
          clearInterval(typeTimer);
          return prev;
        }
        return next.text.slice(0, prev.length + 1);
      });
    }, 40);

    const showTimer = setTimeout(() => setVisible(true), 10);
    const hideTimer = setTimeout(() => {
      setVisible(false);
      setCurrent(null);
    }, 2500 + next.text.length * 40);

    return () => {
      clearInterval(typeTimer);
      clearTimeout(showTimer);
      clearTimeout(hideTimer);
    };
  }, [bannerQueue, current]);

  if (!current || !visible) return null;
  return (
    <div className={`ann-banner ${inline ? 'inline' : ''} ann-${(current.level || '').toLowerCase()}`}>
      <span className="ann-banner-mode">{current.mode === 'speech' ? '🎙 演讲' : '📢 公告'}</span>
      <span className="ann-banner-speaker">{current.speaker}</span>：
      <span className="ann-banner-text">{typed}</span>
    </div>
  );
}
