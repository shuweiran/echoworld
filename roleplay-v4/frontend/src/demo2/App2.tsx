/**
 * App2.tsx — 全新 demo 应用壳（P2-0805，定案架构）
 *
 * 6 新页面：模式选择 / 剧本选择(A) / 角色选择(B) / 剧本生成 / 设置 / 自由角色管理；
 * 对局沿用整机版前端（GameBridge → ChatPage）。
 */
import { useDemoStore, type View } from './store';
import { HomePage } from './pages/HomePage';
import { ScriptSelectPage } from './pages/ScriptSelectPage';
import { RoleSelectPage } from './pages/RoleSelectPage';
import { ScriptGenPage } from './pages/ScriptGenPage';
import { RoleLibPage } from './pages/RoleLibPage';
import { SettingsPage } from './pages/SettingsPage';
import { RoleDetailPage } from './pages/RoleDetailPage';
import { GameBridge } from './pages/GameBridge';
import './styles.css';

const NAV: { view: View | 'werewolf'; label: string; icon: string }[] = [
  { view: 'home', label: '模式选择', icon: '🌌' },
  { view: 'scripts', label: '剧本选择', icon: '📜' },
  { view: 'roles-lib', label: '角色库', icon: '🎭' },
  { view: 'gen', label: '剧本生成', icon: '🪄' },
  { view: 'werewolf', label: '狼人杀', icon: '🐺' },
  { view: 'settings', label: '设置', icon: '⚙️' },
];

export function App2() {
  const view = useDemoStore(s => s.view);
  const go = useDemoStore(s => s.go);
  const back = useDemoStore(s => s.back);
  const history = useDemoStore(s => s.history);
  const enterRoles = useDemoStore(s => s.enterRoles);
  const selectCtx = useDemoStore(s => s.selectCtx);

  // #122：进入对局（view==='game'）隐藏全局顶栏 → 游戏界面全屏沉浸（游戏内操作栏由 ChatPage 自带的 ChatTopbar 承担）
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
            <span className="app2-logo">◈</span>
            <span className="app2-brand-name">幻境之书</span>
            <span className="app2-brand-sub">· 角色扮演 · 世界生成 · 狼人杀/剧本杀</span>
          </div>
          {!isMain && (
            <button className="btn2 btn2-ghost btn2-sm" onClick={back} disabled={!canBack}>← 返回</button>
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
                <span className="app2-nav-icon">{n.icon}</span>
                <span>{n.label}</span>
              </button>
            ))}
          </nav>
        </header>
      )}

      {/* #122：顶栏隐藏后 app2-main 对齐顶部（全宽沉浸布局见 .app2-game .app2-main） */}
      <main className={`app2-main${isGame ? ' app2-main-game' : ''}`}>
        {view === 'home' && <HomePage />}
        {view === 'scripts' && <ScriptSelectPage />}
        {view === 'roles' && <RoleSelectPage />}
        {view === 'gen' && <ScriptGenPage />}
        {view === 'settings' && <SettingsPage />}
        {view === 'roles-lib' && <RoleLibPage />}
        {view === 'free-chars' && <RoleLibPage />}
        {view === 'role-detail' && <RoleDetailPage />}
        {view === 'game' && <GameBridge />}
      </main>
    </div>
  );
}
