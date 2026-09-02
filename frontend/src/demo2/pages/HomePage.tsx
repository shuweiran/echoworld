/**
 * EchoWorld landing page. The spatial simulation is the product; roleplay and
 * hidden-information games are scenarios built on top of it.
 */
import { useDemoStore } from '../store';
import { Icon, type IconName } from '../../components/ui/Icon';

const ENTRIES = [
  {
    id: 'scripts' as const,
    icon: 'book' as IconName,
    title: '启动空间世界',
    desc: '选择一个二维世界和 3 个 Agent，观察移动、听觉、会话与上下文轨道。',
    tags: ['Spatial World', 'Agent', 'Context Track'],
    go: '创建 / 选择世界 →',
    feel: '空间仿真',
  },
  {
    id: 'roles-lib' as const,
    icon: 'users' as IconName,
    title: 'Agent 库',
    desc: '管理参与空间仿真的 Agent 身份、行为倾向、背景与可选语音配置。',
    tags: ['Persona', '行为目标', 'LLM Adapter'],
    go: '管理 Agent →',
    feel: 'Agent 建模',
  },
  {
    id: 'gen' as const,
    icon: 'sparkles' as IconName,
    title: '生成世界',
    desc: '生成或导入场景、角色与地图；几何结构由规则校验，LLM 只提供语义内容。',
    tags: ['地图契约', '碰撞层', '确定性降级'],
    go: '生成空间场景 →',
    feel: '世界建模',
  },
  {
    id: 'werewolf' as const,
    icon: 'moon' as IconName,
    title: '隐藏信息验证场景',
    desc: '通过狼人杀与剧本杀验证身份、私密线索和多会话上下文不会越界泄漏。',
    tags: ['Werewolf', 'Murder Mystery', 'Isolation'],
    go: '进入验证场景 →',
    feel: '规则验证',
  },
  {
    id: 'settings' as const,
    icon: 'settings' as IconName,
    title: '运行配置',
    desc: '配置 LLM Provider、地图生成、语音与开发选项；核心空间规则无需真实模型即可测试。',
    tags: ['Provider', 'Mock LLM', 'Developer Tools'],
    go: '打开运行配置 →',
    feel: '工程配置',
  },
];

const MOBILE_BUILD = import.meta.env.VITE_MOBILE_BUILD === 'true';

export function HomePage() {
  const go = useDemoStore(s => s.go);
  const enterRoles = useDemoStore(s => s.enterRoles);

  const entries = MOBILE_BUILD ? ENTRIES.filter(e => ['scripts', 'roles-lib', 'settings'].includes(e.id)) : ENTRIES;

  return (
    <div className="home-page">
      <div className="home-hero">
        <img className="home-brand-mark" src="/brand/open-script-mark.svg" alt="EchoWorld" />
        <div className="home-kicker"><Icon name="sparkles" size={15} /> Spatial Multi-Agent Simulation Engine</div>
        <div className="home-title">EchoWorld</div>
          <div className="home-sub">一般模式 · 自由对话 · 让每个 Agent 按自己的身份、目标与上下文回应</div>
        <div className="home-feel">
          <div className="feel-item"><Icon name="map" size={16} /> <b>General Chat</b> · 角色与对话</div>
          <div className="feel-item"><Icon name="goal" size={16} /> <b>Hearing</b> · 距离衰减与墙体遮挡</div>
          <div className="feel-item"><Icon name="sparkles" size={16} /> <b>Context Track</b> · MERGED / WEAK / ISOLATED</div>
          <div className="feel-item"><Icon name="moon" size={16} /> <b>Rule-bound AI</b> · 确定性规则约束 LLM</div>
        </div>
      </div>

      <div className="home-grid">
        {entries.map(e => (
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
            <div className="hc-icon"><Icon name={e.icon} size={28} /></div>
            <div className="hc-title">{e.title}</div>
            <div className="hc-desc">{e.desc}</div>
            <div className="hc-tags">
              {e.tags.map(t => <span key={t} className="tag2">{t}</span>)}
            </div>
            <div className="hc-go">{e.go} <Icon name="arrow-right" size={15} /></div>
          </button>
        ))}
      </div>
    </div>
  );
}
