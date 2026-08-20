/**
 * HomePage.tsx — 模式选择（主页面 1）
 *
 * 四个入口：剧本选择 / 剧本生成 / 狼人杀 / 设置。
 * 四感氛围：世界感（星夜背景）· 游戏感（卡片卡片动效）· 创造感（生成入口）· 沉浸感（无杂散 UI）。
 */
import { useDemoStore } from '../store';

const ENTRIES = [
  {
    id: 'scripts' as const,
    icon: '📜',
    title: '剧本选择',
    desc: '从剧本库挑选剧本杀或一般模式剧本，进入角色选择准备开局。',
    tags: ['剧本杀', '一般模式'],
    go: '进入选剧 →',
    feel: '世界感',
  },
  {
    id: 'roles-lib' as const,
    icon: '🎭',
    title: '角色库',
    desc: '统一管理所有角色卡（剧本杀/一般互通），手动或 AI 创建你的专属角色。',
    tags: ['手动创建', 'AI 生成', '单独 TTS'],
    go: '管理角色 →',
    feel: '创造感',
  },
  {
    id: 'gen' as const,
    icon: '🪄',
    title: '剧本生成',
    desc: 'AI 生成全新剧本与世界，或导入已有剧本，让灵感成为可玩的冒险。',
    tags: ['AI 生成', '导入解析', '同步角色', '2D 地图'],
    go: '开始创造 →',
    feel: '创造感',
  },
  {
    id: 'werewolf' as const,
    icon: '🐺',
    title: '狼人杀',
    desc: '狼影在暗处游走。选择你的角色，加入这场信任与谎言的游戏。',
    tags: ['联机房', '快速开局', '昼夜循环'],
    go: '直接进入角色选择 →',
    feel: '游戏感',
  },
  {
    id: 'settings' as const,
    icon: '⚙️',
    title: '设置',
    desc: 'AI 模型、语音、地图生成、素材与体验偏好，一切由你掌控。',
    tags: ['LLM', 'TTS', '地图生成', '素材', '其他'],
    go: '打开设置 →',
    feel: '沉浸感',
  },
];

export function HomePage() {
  const go = useDemoStore(s => s.go);
  const enterRoles = useDemoStore(s => s.enterRoles);

  return (
    <div className="home-page">
      <div className="home-hero">
        <div className="home-title">幻境之书</div>
        <div className="home-sub">每个角色都有自己的声音 —— 角色扮演 · 世界生成 · 狼人杀 / 剧本杀</div>
        <div className="home-feel">
          <div className="feel-item">🌍 <b>世界感</b> · 每个剧本都是一方天地</div>
          <div className="feel-item">🎮 <b>游戏感</b> · 从选角到对局的完整旅程</div>
          <div className="feel-item">✨ <b>创造感</b> · 你的想象即世界</div>
          <div className="feel-item">🔥 <b>沉浸感</b> · 心流不受打扰</div>
        </div>
      </div>

      <div className="home-grid">
        {ENTRIES.map(e => (
          <button
            key={e.id}
            className="home-card"
            onClick={() => {
              if (e.id === 'werewolf') {
                // 狼人杀：直接切到角色选择页（狼人杀变体）
                enterRoles({ kind: 'werewolf', scriptId: null });
              } else {
                go(e.id);
              }
            }}
          >
            <div className="hc-icon">{e.icon}</div>
            <div className="hc-title">{e.title}</div>
            <div className="hc-desc">{e.desc}</div>
            <div className="hc-tags">
              {e.tags.map(t => <span key={t} className="tag2">{t}</span>)}
            </div>
            <div className="hc-go">{e.go}</div>
          </button>
        ))}
      </div>
    </div>
  );
}
