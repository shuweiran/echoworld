/**
 * App.tsx — 前端入口（P2-0805 前端定案架构）
 *
 * 6 新页面（模式选择/剧本选择/角色选择/剧本生成/设置/自由角色）+ 对局沿用整机版 ChatPage。
 * P-0815-F 批2（方向4）：旧版整机应用（AppLegacy.tsx）与旧页面（ScenePage/MaterialPage/
 * LoginPage/旧 HomePage/旧 SettingsPage/FreeCharsPage）已按引用普查确认死代码并删除；
 * 原注释声称的 src/demo/ 目录不存在（注释已同步修正）。
 */
import { App2 } from './demo2/App2';

export default function App() {
  return <App2 />;
}
