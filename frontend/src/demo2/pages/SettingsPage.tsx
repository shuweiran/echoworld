/**
 * SettingsPage.tsx — 设置（主页面 5）
 *
 * 六个页签：LLM（角色与主控分离）/ TTS / 地图生成 / 素材导入 / 其他。
 * 表单 + localStorage 持久化。
 */
import { useEffect, useState } from 'react';
import { useDemoStore } from '../store';
import type { Settings } from '../types';
import { api } from '../../api/client';

const TABS = [
  { key: 'llm', label: '🧠 LLM' },
  { key: 'tts', label: '🔊 TTS' },
  { key: 'image', label: '🖼️ 图片生成' },
  { key: 'map', label: '🗺️ 地图生成' },
  { key: 'assets', label: '📦 素材导入' },
  { key: 'other', label: '🛠️ 其他' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

function HintLabel({ text, detail }: { text: string; detail: string }) {
  return <label>{text}<span className="setting-help" tabIndex={0} aria-label={`${text}：${detail}`} data-tooltip={detail}>ⓘ</span></label>;
}

export function SettingsPage() {
  const settings = useDemoStore(s => s.settings);
  const updateSettings = useDemoStore(s => s.updateSettings);
  const resetSettings = useDemoStore(s => s.resetSettings);

  const [tab, setTab] = useState<TabKey>('llm');
  const [draft, setDraft] = useState<Settings>(settings);
  const [toast, setToast] = useState('');
  const [asset, setAsset] = useState({ name: '', type: '角色头像', path: '' });
  const [discoveredModels, setDiscoveredModels] = useState<Array<{ id: string; name?: string }>>([]);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsMessage, setModelsMessage] = useState('');

  // 本地默认配置保持不变；这里只读取后端“外部接入状态”，不把掩码密钥覆盖到表单。
  useEffect(() => {
    api.getIntegrationConfig().catch(() => undefined);
  }, []);

  // API 地址变化后自动探测 OpenAI-compatible /v1/models；失败时不打扰手工输入。
  useEffect(() => {
    const baseUrl = draft.llm.apiBase.trim();
    if (!baseUrl) { setDiscoveredModels([]); setModelsMessage(''); return; }
    let active = true;
    const timer = setTimeout(async () => {
      if (!active) return;
      setModelsLoading(true);
      setModelsMessage('正在读取模型…');
      try {
        const result = await api.discoverModels(baseUrl, draft.llm.apiKey);
        if (active) {
          setDiscoveredModels(result.models || []);
          setModelsMessage(result.models?.length ? `已找到 ${result.models.length} 个模型` : '服务未返回可选模型');
        }
      } catch {
        if (active) { setDiscoveredModels([]); setModelsMessage('暂未读取到模型，可继续手工填写'); }
      } finally {
        if (active) setModelsLoading(false);
      }
    }, 500);
    return () => { active = false; clearTimeout(timer); };
  }, [draft.llm.apiBase, draft.llm.apiKey]);

  const set = (patch: Partial<Settings>) => setDraft(d => ({ ...d, ...patch }));
  const setLlm = (p: Partial<Settings['llm']>) => set({ llm: { ...draft.llm, ...p } });
  const setTts = (p: Partial<Settings['tts']>) => set({ tts: { ...draft.tts, ...p } });
  const setMap = (p: Partial<Settings['mapGen']>) => set({ mapGen: { ...draft.mapGen, ...p } });
  const setOther = (p: Partial<Settings['other']>) => set({ other: { ...draft.other, ...p } });

  const show = (t: string) => { setToast(t); setTimeout(() => setToast(''), 2200); };

  const save = async () => {
    updateSettings(draft);
    try {
      await api.setIntegrationConfig({
        llm: { api_key: draft.llm.apiKey, base_url: draft.llm.apiBase, model: draft.llm.model, temperature: draft.llm.temperature, max_tokens: draft.llm.maxTokens },
        arbiter_llm: { api_key: draft.llm.arbiterApiKey, base_url: draft.llm.arbiterApiBase, model: draft.llm.arbiterModel },
        map_llm: { api_key: draft.llm.mapApiKey, base_url: draft.llm.mapApiBase, model: draft.llm.mapModel },
        tts: { provider: draft.tts.provider, api_key: draft.tts.apiKey, base_url: draft.tts.apiBase, model: draft.tts.model, voice: draft.tts.voice,
          ...(draft.tts.engine === 'MiMo TTS（外部 API）' ? { enabled: true } : {}) },
        image: { provider: draft.image.provider, base_url: draft.image.baseUrl, external_base_url: draft.image.externalBaseUrl, external_api_key: draft.image.externalApiKey, external_model: draft.image.externalModel, external_endpoint: draft.image.externalEndpoint, lora_name: draft.image.loraName, rmbg_enabled: draft.image.rmbgEnabled, img2img_denoise: draft.image.img2imgDenoise },
      });
      show('✅ 已同步外部 API，并保存本地设置');
    } catch { show('⚠️ 本地已保存，后端暂未同步'); }
  };
  const reset = () => { resetSettings(); setDraft(useDemoStore.getState().settings); show('✅ 已恢复默认设置'); };

  const importAsset = () => {
    if (!asset.name.trim() || !asset.path.trim()) { show('⚠️ 请填写素材名与路径'); return; }
    set({ assets: [...draft.assets, { name: asset.name.trim(), type: asset.type, path: asset.path.trim(), time: new Date().toLocaleString('zh-CN', { hour12: false }) }] });
    setAsset({ name: '', type: '角色头像', path: '' });
    show('✅ 素材已登记');
  };

  return (
    <div style={{ maxWidth: 960 }}>
      <div className="page-head">
        <h2>⚙️ 设置</h2>
        <span className="page-sub">不确定的项目先保持默认；把光标停在 ⓘ 上可查看说明。</span>
        <button className="btn2 btn2-ghost btn2-sm" style={{ marginLeft: 'auto' }} onClick={reset}>🔄 恢复默认</button>
      </div>

      <div className="settings-tabs">
        {TABS.map(t => (
          <button key={t.key} className={`settings-tab ${tab === t.key ? 'active' : ''}`} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>

      {/* LLM */}
      {tab === 'llm' && (
        <div className="settings-grid">
          <div className="field"><HintLabel text="🌐 AI 服务地址" detail="AI 服务商提供的接口网址。使用默认服务时不用修改。" /><input value={draft.llm.apiBase} onChange={e => setLlm({ apiBase: e.target.value })} /></div>
          <div className="field"><HintLabel text="🧠 AI 名称" detail="服务商给出的模型名称。不填写密钥也可以尝试读取公开模型列表；下拉栏仅在读取到模型后显示。" />
            <input value={draft.llm.model} onChange={e => setLlm({ model: e.target.value })} list="discovered-llm-models" placeholder="例如 deepseek-chat" />
            <datalist id="discovered-llm-models">{discoveredModels.map(model => <option key={model.id} value={model.id}>{model.name && model.name !== model.id ? model.name : undefined}</option>)}</datalist>
            {discoveredModels.length > 0 && <select aria-label="选择已检索到的模型" value={discoveredModels.some(model => model.id === draft.llm.model) ? draft.llm.model : ''} onChange={e => e.target.value && setLlm({ model: e.target.value })}>
              <option value="">从服务商模型列表选择</option>
              {discoveredModels.map(model => <option key={model.id} value={model.id}>{model.name && model.name !== model.id ? `${model.name}（${model.id}）` : model.id}</option>)}
            </select>}
            <span className="hint">{modelsLoading ? '正在根据 API 地址检索…' : modelsMessage || '填写或修改 API 地址后会自动检索'}</span>
          </div>
          <div className="field"><HintLabel text="🔑 访问密钥" detail="用于连接你的 AI 服务的私人凭据。仅在使用自己的服务时填写，勿分享给他人。" /><input type="password" value={draft.llm.apiKey} onChange={e => setLlm({ apiKey: e.target.value })} placeholder="sk-..." /></div>
          <div className="field"><HintLabel text={`🌡️ 回答创意度（${draft.llm.temperature}）`} detail="数值越低回答越稳定，越高越有变化。0.7 是平衡选择。" /><input type="range" min={0} max={2} step={0.1} value={draft.llm.temperature} onChange={e => setLlm({ temperature: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text={`📏 单次回答长度（${draft.llm.maxTokens}）`} detail="AI 一次最多写多少内容。数值越大可写得更长，也会更慢、更耗额度。" /><input type="number" min={256} max={16384} step={256} value={draft.llm.maxTokens} onChange={e => setLlm({ maxTokens: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text={`🧩 记忆长度（${draft.llm.contextLength}）`} detail="AI 同时参考多少先前内容。默认值适合大多数对局，增大可能增加费用。" /><input type="number" min={2048} max={65536} step={1024} value={draft.llm.contextLength} onChange={e => setLlm({ contextLength: Number(e.target.value) })} /></div>

          <div className="card2" style={{ gridColumn: '1 / -1' }}>
            <div className="settings-sec-title">🎛️ 主控 LLM（仲裁、地图、角色与场景生成）</div>
            <div className="settings-grid">
              <div className="field"><HintLabel text="主控 AI 名称" detail="用于剧情调度、地图、角色和场景生成。建议选择上下文更长、推理和逻辑能力更强的模型。" /><input value={draft.llm.arbiterModel} onChange={e => setLlm({ arbiterModel: e.target.value })} placeholder="例如 deepseek-v4-pro" /></div>
              <div className="field"><HintLabel text="主控服务地址" detail="主控模型可使用独立服务商；留默认即可与角色对话服务分开配置。" /><input value={draft.llm.arbiterApiBase} onChange={e => setLlm({ arbiterApiBase: e.target.value })} /></div>
              <div className="field"><HintLabel text="主控访问密钥" detail="主控模型的独立密钥。留空时服务端会回退使用角色 LLM 的密钥。" /><input type="password" value={draft.llm.arbiterApiKey} onChange={e => setLlm({ arbiterApiKey: e.target.value })} placeholder="留空 = 复用角色 LLM 密钥" /></div>
            </div>
            <div className="hint">主控会以更完整的场景、目标、轨道和历史作判断；角色逐轮说话仍使用上方「角色对话 LLM」。</div>
          </div>

          <div className="card2" style={{ gridColumn: '1 / -1' }}>
            <div className="settings-sec-title">🖼️ 地图视觉审核 LLM（可选，多模态）</div>
            <div className="settings-grid">
              <div className="field"><HintLabel text="审核 AI 名称" detail="可选。仅在开启地图视觉审核时使用；地图生成本身固定使用上方主控 LLM。" /><input value={draft.llm.mapModel} onChange={e => setLlm({ mapModel: e.target.value })} placeholder="可选多模态模型" /></div>
              <div className="field"><HintLabel text="审核服务地址" detail="可选。只在地图审核 AI 与主控来自不同服务商时填写。" /><input value={draft.llm.mapApiBase} onChange={e => setLlm({ mapApiBase: e.target.value })} placeholder="可选" /></div>
              <div className="field"><HintLabel text="审核访问密钥" detail="可选。仅在地图视觉审核使用独立服务时填写。" /><input type="password" value={draft.llm.mapApiKey} onChange={e => setLlm({ mapApiKey: e.target.value })} placeholder="可选" /></div>
              <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" checked={draft.llm.multimodal} onChange={e => setLlm({ multimodal: e.target.checked })} style={{ width: 'auto' }} />
                <label style={{ margin: 0 }}>模型支持多模态（图像/地图理解）</label>
              </div>
            </div>
            <div className="hint">地图生成与结构蓝图默认使用主控 LLM；此处只保留对渲染图的可选视觉审核。</div>
          </div>
        </div>
      )}

      {/* TTS */}
      {tab === 'tts' && (
        <div className="settings-grid">
          <div className="field"><HintLabel text="🔊 语音来源" detail="选择角色声音从哪里生成。浏览器内置最省事；外部服务需要填写自己的连接信息。" />
            <select value={draft.tts.engine} onChange={e => setTts({ engine: e.target.value })}>
              <option>浏览器内置</option><option>MiMo TTS（外部 API）</option><option>Edge TTS</option><option>CosyVoice</option><option>离线</option>
            </select>
          </div>
          <div className="field"><HintLabel text="语音服务类型" detail="只有使用外部语音服务时才需要选择。默认“当前 MiMo”会沿用已有设置。" /><select value={draft.tts.provider} onChange={e => setTts({ provider: e.target.value })}><option value="xiaomimimo">当前 MiMo</option><option value="openai-compatible">兼容 OpenAI 的外部服务</option></select></div>
          <div className="field"><label>🎙️ 音色选择</label>
            <select value={draft.tts.voice} onChange={e => setTts({ voice: e.target.value })}>
              <option>默认女声</option><option>默认男声</option><option>沉稳大叔</option><option>元气少女</option><option>空灵少年</option>
            </select>
          </div>
          <div className="field"><HintLabel text={`⚡ 说话速度（${draft.tts.speed}）`} detail="1 是正常速度。小于 1 更慢，大于 1 更快。" /><input type="range" min={0.5} max={2} step={0.1} value={draft.tts.speed} onChange={e => setTts({ speed: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text={`🎚️ 声音高低（${draft.tts.pitch}）`} detail="1 是原始音调。小于 1 更低沉，大于 1 更明亮。" /><input type="range" min={0.5} max={2} step={0.1} value={draft.tts.pitch} onChange={e => setTts({ pitch: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text={`💗 情感表现（${draft.tts.emotion}）`} detail="数值越高，朗读的情绪起伏越明显。默认值较自然。" /><input type="range" min={0} max={1} step={0.05} value={draft.tts.emotion} onChange={e => setTts({ emotion: Number(e.target.value) })} /></div>

          <div className="card2" style={{ gridColumn: '1 / -1' }}>
            <div className="settings-sec-title" style={{ marginTop: 0 }}>🤖 语音生成模型 API</div>
            <div className="settings-grid">
              <div className="field"><label>语音生成模型</label><input value={draft.tts.model} onChange={e => setTts({ model: e.target.value })} placeholder="例如：edge-tts / cosyvoice-v2 / qwen-tts" /></div>
              <div className="field"><label>API 地址</label><input value={draft.tts.apiBase} onChange={e => setTts({ apiBase: e.target.value })} /></div>
              <div className="field"><label>API Key</label><input type="password" value={draft.tts.apiKey} onChange={e => setTts({ apiKey: e.target.value })} placeholder="sk-..." /></div>
            </div>
            <div className="hint">MiMo 保持本地当前配置；切换 OpenAI-compatible 后，后端调用「API 地址 + /audio/speech」，可接入不同 TTS 模型。</div>
          </div>
        </div>
      )}

      {/* 图片生成 */}
      {tab === 'image' && (
        <div className="settings-grid">
          <div className="field"><HintLabel text="🖼️ 图片生成来源" detail="选择本机 ComfyUI 或你自己的外部图片服务。没有外部服务时保持本机选项。" /><select value={draft.image.provider} onChange={e => set({ image: { ...draft.image, provider: e.target.value } })}><option value="comfyui">本机图片服务（ComfyUI）</option><option value="openai-compatible">兼容 OpenAI 的外部服务</option></select></div>
          <div className="field"><label>ComfyUI API 地址</label><input value={draft.image.baseUrl} onChange={e => set({ image: { ...draft.image, baseUrl: e.target.value } })} placeholder="http://127.0.0.1:8188" /></div>
          <div className="field"><label>外部图片 API 地址</label><input value={draft.image.externalBaseUrl} onChange={e => set({ image: { ...draft.image, externalBaseUrl: e.target.value } })} placeholder="https://api.example.com/v1" /></div>
          <div className="field"><label>外部图片模型</label><input value={draft.image.externalModel} onChange={e => set({ image: { ...draft.image, externalModel: e.target.value } })} placeholder="gpt-image-1 / doubao-seedream" /></div>
          <div className="field"><label>外部图片 API Key</label><input type="password" value={draft.image.externalApiKey} onChange={e => set({ image: { ...draft.image, externalApiKey: e.target.value } })} placeholder="sk-..." /></div>
          <div className="field"><HintLabel text="风格补充文件（可选）" detail="LoRA 是让图片更接近某种角色或画风的附加文件。没有准备过此类文件时请留空。" /><input value={draft.image.loraName} onChange={e => set({ image: { ...draft.image, loraName: e.target.value } })} placeholder="留空 = 不使用额外风格" /></div>
          <div className="field"><HintLabel text={`参考图影响程度（${draft.image.img2imgDenoise}）`} detail="仅在以图改图时使用。数值越高，结果与原图差异越大。" /><input type="range" min={0} max={1} step={0.05} value={draft.image.img2imgDenoise} onChange={e => set({ image: { ...draft.image, img2imgDenoise: Number(e.target.value) } })} /></div>
          <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <input type="checkbox" checked={draft.image.rmbgEnabled} onChange={e => set({ image: { ...draft.image, rmbgEnabled: e.target.checked } })} style={{ width: 'auto' }} />
            <label style={{ margin: 0 }}>生成透明背景图（RMBG）</label>
          </div>
          <div className="hint" style={{ gridColumn: '1 / -1' }}>默认仍走当前本地 ComfyUI；切换 OpenAI-compatible 后使用外部图片 API 的 `/images/generations`，可接入不同图片生成模型。</div>
        </div>
      )}

      {/* 地图生成 */}
      {tab === 'map' && (
        <div className="settings-grid">
          <div className="field"><HintLabel text="🗺️ 地图制作方式" detail="推荐布局速度快且稳定；AI 布局更有变化，但需要可用的地图 AI。" />
            <select value={draft.mapGen.model} onChange={e => setMap({ model: e.target.value })}>
              <option value="structure">结构模板 + 程序化布局（推荐）</option>
              <option value="llm">LLM 语义结构 + 程序化布局</option>
            </select>
          </div>
          <div className="field"><label>🏛️ 地图结构</label>
            <select value={draft.mapGen.kind} onChange={e => setMap({ kind: e.target.value })}>
              <option value="castle">城堡</option>
              <option value="mansion">庄园</option>
              <option value="city_block">城市街区</option>
              <option value="dungeon">地牢</option>
              <option value="custom">自定义结构（LLM）</option>
            </select>
          </div>
          <div className="field"><HintLabel text="地图怎么分区" detail="单张地图最简单；多地图和室外加室内适合更大的世界，但探索时先使用当前地图。" />
            <select value={draft.mapGen.mapMode} onChange={e => setMap({ mapMode: e.target.value })}>
              <option value="single">单张大地图</option>
              <option value="multi">多张地图 + 传送连接</option>
              <option value="exterior">外部城镇 + 建筑内部</option>
            </select>
          </div>
          <div className="field"><label>🎨 地图风格</label>
            <select value={draft.mapGen.style} onChange={e => setMap({ style: e.target.value })}>
              <option value="随剧本风格">随剧本风格（默认）</option>
              <option value="幻想">幻想</option><option value="现实">现实</option><option value="科幻">科幻</option><option value="古风">古风</option>
            </select>
          </div>
          <div className="field"><HintLabel text="📐 地图宽度（格）" detail="地图横向由多少小格组成。越大可探索范围越大，也更耗时。" /><input type="number" min={10} max={256} value={draft.mapGen.width} onChange={e => setMap({ width: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text="📐 地图高度（格）" detail="地图纵向由多少小格组成。和宽度一起决定地图面积。" /><input type="number" min={8} max={256} value={draft.mapGen.height} onChange={e => setMap({ height: Number(e.target.value) })} /></div>
          <div className="field"><HintLabel text="🧱 每格显示大小" detail="这是画面中一格的显示大小。32 是推荐值；它不改变地图实际范围。" />
            <select value={draft.mapGen.tileSize} onChange={e => setMap({ tileSize: Number(e.target.value) })}>
              <option value={16}>16</option><option value={32}>32</option><option value={48}>48</option>
            </select>
          </div>
          <div className="field"><label>⚙️ 默认生成规则</label><input value={draft.mapGen.rule} onChange={e => setMap({ rule: e.target.value })} /></div>
          <div className="field"><HintLabel text="🎲 重现同一张地图（可选）" detail="填入一个数字后，每次可生成相同布局；留空则每次随机生成。" /><input value={draft.mapGen.seed} onChange={e => setMap({ seed: e.target.value })} placeholder="留空 = 每次随机" /></div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}><input type="checkbox" checked={draft.mapGen.audit} onChange={e => setMap({ audit: e.target.checked })} style={{ width: 'auto' }} />生成后进行视觉布局审核</label>
          <div className="hint" style={{ gridColumn: '1 / -1' }}>这里的尺寸、结构、地图组织、风格和种子会同时用于一般模式普通地图与大型地图；上限 256×256 格。多图/外部-内部结果可预览，但当前一般模式运行时优先使用当前图。</div>
        </div>
      )}

      {/* 素材导入 */}
      {tab === 'assets' && (
        <div>
          <div className="card2" style={{ marginBottom: 14, borderColor: 'var(--color-accent-gold-2)' }}>
            <div className="settings-sec-title" style={{ marginTop: 0, color: 'var(--color-accent-gold)' }}>📖 素材导入说明书</div>
            <div style={{ fontSize: 12.5, color: 'var(--color-text-dim)', lineHeight: 2 }}>
              <div><b>① 支持的类型</b>：角色头像 / 角色立绘 / 地图素材 / 音效 / 背景音乐 / 动画资源</div>
              <div><b>② 导入方式（登记式）</b>：素材文件先放入后端 <code style={{ color: 'var(--color-accent-cyan)' }}>static/assets/</code> 目录，再在本页登记「素材名 + 类型 + 文件路径」即可（demo 模式仅本地登记，不实际上传）。</div>
              <div><b>③ 路径约定</b>：<code style={{ color: 'var(--color-accent-cyan)' }}>assets/CHARACTER_ANIMATION/&lt;角色名&gt;/xxx.png</code>（角色动画）、<code style={{ color: 'var(--color-accent-cyan)' }}>assets/SCENE_TILESET/&lt;图集名&gt;/tiles.png</code>（地图瓦片）。</div>
              <div><b>④ 2D 游戏内自动调用</b>：登记后的角色动画/瓦片图集会在 2D 世界中自动应用；无素材时回退默认色块/圆点，不影响游玩。</div>
              <div><b>⑤ 提示</b>：素材名建议唯一；同类型重复登记会全部展示，请自行命名区分。</div>
            </div>
          </div>
          <div className="card2" style={{ marginBottom: 14 }}>
            <div className="settings-sec-title" style={{ marginTop: 0 }}>📥 导入素材（登记式）</div>
            <div className="settings-grid">
              <div className="field"><label>素材名</label><input value={asset.name} onChange={e => setAsset({ ...asset, name: e.target.value })} /></div>
              <div className="field"><label>类型</label>
                <select value={asset.type} onChange={e => setAsset({ ...asset, type: e.target.value })}>
                  <option>角色头像</option><option>角色立绘</option><option>地图素材</option><option>音效</option><option>背景音乐</option><option>动画资源</option>
                </select>
              </div>
              <div className="field" style={{ gridColumn: '1 / -1' }}><label>文件路径</label><input value={asset.path} onChange={e => setAsset({ ...asset, path: e.target.value })} placeholder="assets/…" /></div>
            </div>
            <button className="btn2 btn2-primary btn2-sm" onClick={importAsset}>📥 登记素材</button>
          </div>
          <div className="settings-sec-title">已登记素材（{draft.assets.length}）</div>
          {draft.assets.length === 0 && <div className="empty-note">暂无素材</div>}
          {draft.assets.map(a => (
            <div key={a.name} className="asset-row">
              <span style={{ fontSize: 18 }}>{a.type.includes('头像') || a.type.includes('立绘') ? '🧍' : a.type.includes('地图') ? '🗺️' : a.type.includes('音') ? '🎵' : '🎬'}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 700 }}>{a.name}</div>
                <div style={{ fontSize: 11.5, color: 'var(--color-text-dim2)' }}>{a.type} · {a.path} · {a.time}</div>
              </div>
              <button className="btn2 btn2-danger btn2-sm" onClick={() => set({ assets: draft.assets.filter(x => x.name !== a.name) })}>删除</button>
            </div>
          ))}
        </div>
      )}

      {/* 其他 */}
      {tab === 'other' && (
        <div className="settings-grid">
          <div className="field"><label>💾 数据保存位置</label><input value={draft.other.dataPath} onChange={e => setOther({ dataPath: e.target.value })} /></div>
          <div className="field"><label>⏱️ 自动备份</label>
            <select value={String(draft.other.autoBackup)} onChange={e => setOther({ autoBackup: e.target.value === 'true' })}>
              <option value="true">开启</option><option value="false">关闭</option>
            </select>
          </div>
          <div className="field"><label>🗒️ 日志管理</label>
            <select value={draft.other.logLevel} onChange={e => setOther({ logLevel: e.target.value })}>
              <option value="debug">debug</option><option value="info">info</option><option value="warn">warn</option><option value="error">error</option>
            </select>
          </div>
          <div className="field"><label>🎨 UI 主题</label>
            <select value={draft.other.uiTheme} onChange={e => {
              const uiTheme = e.target.value;
              setOther({ uiTheme });
              // 主题是纯本地体验设置：选择后立即生效，不要求用户再滚到底部保存。
              updateSettings({ other: { ...settings.other, uiTheme } });
              show(`✅ 已切换为${uiTheme}`);
            }}>
              <option>深色</option><option>浅色</option><option>跟随系统</option>
            </select>
            <div className="hint">选择后立即生效并自动记住；其他设置仍可在页面底部统一保存。</div>
          </div>
          <div className="field"><label>⌨️ 快捷键</label><input value={draft.other.shortcut} onChange={e => setOther({ shortcut: e.target.value })} /></div>
          <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <input type="checkbox" checked={draft.other.experiment} onChange={e => setOther({ experiment: e.target.checked })} style={{ width: 'auto' }} />
            <label style={{ margin: 0 }}>实验功能（新特性抢先体验，可能不稳定）</label>
          </div>
        </div>
      )}

      <div className="settings-actions">
        <button className="btn2 btn2-primary" onClick={save}>💾 保存全部设置</button>
        {toast && <span style={{ fontSize: 13, color: 'var(--color-accent-cyan)' }}>{toast}</span>}
      </div>
    </div>
  );
}
