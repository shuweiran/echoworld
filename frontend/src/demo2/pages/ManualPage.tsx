import { useState } from 'react';
import { Icon, type IconName } from '../../components/ui/Icon';
import { useDemoStore } from '../store';

const STEPS: { icon: IconName; title: string; text: string; action: string; view?: 'scripts' | 'settings' }[] = [
  { icon: 'book', title: '先选一个世界', text: '从剧本选择进入剧本杀或一般模式。晨雾镇只是一个可保存的世界，不会限制你接下来要讲什么故事。', action: '去选剧本', view: 'scripts' },
  { icon: 'users', title: '再选角色', text: '为玩家和 AI 角色分配身份。角色会根据位置、听觉和当前关系决定什么时候说话。', action: '下一步' },
  { icon: 'play', title: '开始探索', text: '自由聊天适合对话，2D 探索适合在地图中移动、靠近角色、加入空间会话。', action: '下一步' },
  { icon: 'sliders', title: '调整体验', text: '大多数用户只需选择界面主题、语音和地图风格。模型与 API 仅在你使用自己的 AI 服务时才需要填写。修改后点击保存，设置会保留在当前设备。', action: '打开设置', view: 'settings' },
];

const CONFIG_GUIDE: { icon: IconName; title: string; text: string }[] = [
  { icon: 'settings', title: '先用默认设置', text: '首次使用不用填写任何地址或密钥。默认配置即可浏览剧本、创建角色和体验界面。' },
  { icon: 'sparkles', title: '想接入自己的 AI', text: '只需填写“AI 服务地址、模型名称和访问密钥”。不清楚这些信息时，请向服务提供方索取；其余选项可保持默认。' },
  { icon: 'volume', title: '想听角色说话', text: '先选择语音服务和喜欢的音色；语速、音调、情绪是可选微调，默认值适合大多数场景。' },
  { icon: 'map', title: '想生成地图', text: '优先选择“推荐布局”和喜欢的世界风格。尺寸越大，生成和加载时间越长；不确定时保持默认。' },
];

export function ManualPage() {
  const [step, setStep] = useState(0);
  const go = useDemoStore(s => s.go);
  const current = STEPS[step];
  const move = (next: number) => setStep(Math.max(0, Math.min(STEPS.length - 1, next)));

  return (
    <section className="manual-page">
      <div className="manual-head">
        <div className="page-kicker"><Icon name="info" size={15} /> 快速上手</div>
        <h1>EchoWorld 使用说明</h1>
        <p>这是一个没有固定剧本的剧本。选择一个世界，让角色自己把故事走出来。</p>
      </div>
      <div className="manual-progress" aria-label="说明书步骤">
        {STEPS.map((item, index) => (
          <button key={item.title} className={index === step ? 'manual-dot active' : 'manual-dot'} onClick={() => move(index)} aria-label={`第 ${index + 1} 步：${item.title}`}>
            <Icon name={item.icon} size={16} />
          </button>
        ))}
      </div>
      <div className="manual-card">
        <div className="manual-card-icon"><Icon name={current.icon} size={32} /></div>
        <div className="manual-count">0{step + 1} / 0{STEPS.length}</div>
        <h2>{current.title}</h2>
        <p>{current.text}</p>
        <div className="manual-actions">
          <button className="btn2 btn2-ghost" onClick={() => move(step - 1)} disabled={step === 0}><Icon name="arrow-left" size={15} /> 上一步</button>
          {current.view ? <button className="btn2 btn2-primary" onClick={() => go(current.view!)}>{current.action} <Icon name="arrow-right" size={15} /></button> : <button className="btn2 btn2-primary" onClick={() => move(step + 1)}>{current.action} <Icon name="arrow-right" size={15} /></button>}
        </div>
      </div>
      <label className="manual-range-label" htmlFor="manual-progress">滑动浏览说明书</label>
      <input id="manual-progress" className="manual-range" type="range" min={0} max={STEPS.length - 1} step={1} value={step} onChange={event => move(Number(event.target.value))} />
      <section className="manual-config" aria-labelledby="manual-config-title">
        <div className="page-kicker"><Icon name="sliders" size={15} /> 配置说明</div>
        <h2 id="manual-config-title">哪些需要设置？</h2>
        <p>如果某项看不懂，先保持默认即可。设置页面中，鼠标停在带 <b>ⓘ</b> 的说明上可查看详细解释。</p>
        <div className="manual-config-grid">
          {CONFIG_GUIDE.map(item => (
            <article className="manual-config-item" key={item.title}>
              <Icon name={item.icon} size={20} />
              <div><h3>{item.title}</h3><p>{item.text}</p></div>
            </article>
          ))}
        </div>
        <button className="btn2 btn2-ghost" onClick={() => go('settings')}><Icon name="settings" size={15} /> 打开设置</button>
      </section>
    </section>
  );
}
