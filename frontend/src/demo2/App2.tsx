/**
 * App2.tsx — 全新 demo 应用壳（P2-0805，定案架构）
 *
 * 6 新页面：模式选择 / 剧本选择(A) / 角色选择(B) / 剧本生成 / 设置 / 自由角色管理；
 * 对局沿用整机版前端（GameBridge → ChatPage）。
 */
import { useEffect, useState } from 'react';
import { useDemoStore, type View } from './store';
import { HomePage } from './pages/HomePage';
import { ScriptSelectPage } from './pages/ScriptSelectPage';
import { RoleSelectPage } from './pages/RoleSelectPage';
import { ScriptGenPage } from './pages/ScriptGenPage';
import { RoleLibPage } from './pages/RoleLibPage';
import { SettingsPage } from './pages/SettingsPage';
import { ManualPage } from './pages/ManualPage';
import { RoleDetailPage } from './pages/RoleDetailPage';
import { GameBridge } from './pages/GameBridge';
import { Icon, type IconName } from '../components/ui/Icon';
import { UpdateStatus } from '../components/UpdateStatus';
import './styles.css';

const NAV: { view: View | 'werewolf'; label: string; icon: IconName }[] = [
  { view: 'home', label: '总览', icon: 'home' },
  { view: 'scripts', label: '空间世界', icon: 'book' },
  { view: 'roles-lib', label: 'Agent 库', icon: 'users' },
  { view: 'manual', label: '说明书', icon: 'info' },
  { view: 'settings', label: '设置', icon: 'settings' },
];

export function App2() {
  const view = useDemoStore(s => s.view);
  const uiTheme = useDemoStore(s => s.settings.other.uiTheme);
  const go = useDemoStore(s => s.go);
  const back = useDemoStore(s => s.back);
  const history = useDemoStore(s => s.history);
  const enterRoles = useDemoStore(s => s.enterRoles);
  const selectCtx = useDemoStore(s => s.selectCtx);
  const [systemPrefersLight, setSystemPrefersLight] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: light)').matches,
  );

  const resolvedTheme = uiTheme === '浅色' || (uiTheme === '跟随系统' && systemPrefersLight)
    ? 'light'
    : 'dark';

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: light)');
    const sync = () => setSystemPrefersLight(media.matches);
    sync();
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.uiTheme = resolvedTheme;
    return () => { delete document.documentElement.dataset.uiTheme; };
  }, [resolvedTheme]);

  // 进入对局时隐藏全局顶栏，由 ChatPage 的 ChatTopbar 承担局内操作。
  const isGame = view === 'game';
  const isMain = NAV.some(n => n.view === view);
  const canBack = history.length > 0;
  const isActive = (n: typeof NAV[number]) =>
    n.view === 'werewolf' ? (view === 'roles' && selectCtx.kind === 'werewolf') : view === n.view;

  return (
    <div className={`app2${isGame ? ' app2-game' : ''}`}>
      <div className="app2-bg" aria-hidden />
      {!isGame && (
        <header className="app2-topbar">
          <div className="app2-brand">
            <img className="app2-logo" src="/brand/open-script-mark.svg" alt="EchoWorld" />
            <span className="app2-brand-name">EchoWorld</span>
            <span className="app2-brand-sub">· Spatial World · Hearing · Context Isolation</span>
          </div>
          <UpdateStatus />
          {!isMain && (
            <button className="btn2 btn2-ghost btn2-sm" onClick={back} disabled={!canBack}><Icon name="arrow-left" size={15} /> 返回</button>
          )}
          <nav className="app2-nav">
            {NAV.map(n => (
              <button
                key={n.view}
                className={`app2-nav-btn ${isActive(n) ? 'active' : ''}`}
                onClick={() => {
                  if (n.view === 'werewolf') enterRoles({ kind: 'werewolf', scriptId: null });
                  else go(n.view);
                }}
              >
                <span className="app2-nav-icon"><Icon name={n.icon} size={16} /></span>
                <span>{n.label}</span>
              </button>
            ))}
          </nav>
        </header>
      )}

      {/* 对局状态下主区域对齐顶部，使用全宽沉浸布局。 */}
      <main className={`app2-main${isGame ? ' app2-main-game' : ''}${view === 'home' ? ' app2-main-home' : ' app2-main-scroll'}`}>
        {view === 'home' && <HomePage />}
        {view === 'scripts' && <ScriptSelectPage />}
        {view === 'roles' && <RoleSelectPage />}
        {view === 'gen' && <ScriptGenPage />}
        {view === 'settings' && <SettingsPage />}
        {view === 'manual' && <ManualPage />}
        {view === 'roles-lib' && <RoleLibPage />}
        {view === 'free-chars' && <RoleLibPage />}
        {view === 'role-detail' && <RoleDetailPage />}
        {view === 'game' && <GameBridge />}
      </main>
    </div>
  );
}
