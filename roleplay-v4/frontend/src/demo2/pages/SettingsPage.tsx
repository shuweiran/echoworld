/**
 * SettingsPage.tsx — 设置（主页面 5）
 *
 * 五个页签：LLM（含地图生成 LLM，推荐多模态）/ TTS / 地图生成 / 素材导入 / 其他。
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

export function SettingsPage() {
  const settings = useDemoStore(s => s.settings);
  const updateSettings = useDemoStore(s => s.updateSettings);
  const resetSettings = useDemoStore(s => s.resetSettings);

  const [tab, setTab] = useState<TabKey>('llm');
  const [draft, setDraft] = useState<Settings>(settings);
  const [toast, setToast] = useState('');
  const [asset, setAsset] = useState({ name: '', type: '角色头像', path: '' });

  // 本地默认配置保持不变；这里只读取后端“外部接入状态”，不把掩码密钥覆盖到表单。
  useEffect(() => {
    api.getIntegrationConfig().catch(() => undefined);
  }, []);

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
        map_llm: { api_key: draft.llm.mapApiKey, base_url: draft.llm.mapApiBase, model: draft.llm.mapModel },
        tts: { api_key: draft.tts.apiKey, base_url: draft.tts.apiBase, model: draft.tts.model, voice: draft.tts.voice,
          ...(draft.tts.engine === 'MiMo TTS（外部 API）' ? { enabled: true } : {}) },
        image: { base_url: draft.image.baseUrl, lora_name: draft.image.loraName, rmbg_enabled: draft.image.rmbgEnabled, img2img_denoise: draft.image.img2imgDenoise },
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
        <span className="page-sub">demo 模式仅保存配置，不发起真实请求。</span>
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
          <div className="field"><label>🌐 API 地址</label><input value={draft.llm.apiBase} onChange={e => setLlm({ apiBase: e.target.value })} /></div>
          <div className="field"><label>🧠 模型选择</label><input value={draft.llm.model} onChange={e => setLlm({ model: e.target.value })} /></div>
          <div className="field"><label>🔑 API Key</label><input type="password" value={draft.llm.apiKey} onChange={e => setLlm({ apiKey: e.target.value })} placeholder="sk-..." /></div>
          <div className="field"><label>🌡️ 温度（{draft.llm.temperature}）</label><input type="range" min={0} max={2} step={0.1} value={draft.llm.temperature} onChange={e => setLlm({ temperature: Number(e.target.value) })} /></div>
          <div className="field"><label>📏 Token 限制（{draft.llm.maxTokens}）</label><input type="number" min={256} max={16384} step={256} value={draft.llm.maxTokens} onChange={e => setLlm({ maxTokens: Number(e.target.value) })} /></div>
          <div className="field"><label>🧩 上下文长度（{draft.llm.contextLength}）</label><input type="number" min={2048} max={65536} step={1024} value={draft.llm.contextLength} onChange={e => setLlm({ contextLength: Number(e.target.value) })} /></div>

          <div className="card2" style={{ gridColumn: '1 / -1' }}>
            <div className="settings-sec-title">🗺️ 地图生成 LLM（推荐多模态）</div>
            <div className="settings-grid">
              <div className="field"><label>地图生成模型</label><input value={draft.llm.mapModel} onChange={e => setLlm({ mapModel: e.target.value })} placeholder="留空 = 复用上方主 LLM" /></div>
              <div className="field"><label>地图生成 API 地址</label><input value={draft.llm.mapApiBase} onChange={e => setLlm({ mapApiBase: e.target.value })} placeholder="留空 = 复用主 LLM" /></div>
              <div className="field"><label>地图生成 API Key</label><input type="password" value={draft.llm.mapApiKey} onChange={e => setLlm({ mapApiKey: e.target.value })} placeholder="可选" /></div>
              <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" checked={draft.llm.multimodal} onChange={e => setLlm({ multimodal: e.target.checked })} style={{ width: 'auto' }} />
                <label style={{ margin: 0 }}>模型支持多模态（图像/地图理解）</label>
              </div>
            </div>
            <div className="hint">若未单独配置地图生成 LLM，则自动复用「🧠 模型选择」中的主 LLM。</div>
          </div>
        </div>
      )}

      {/* TTS */}
      {tab === 'tts' && (
        <div className="settings-grid">
          <div className="field"><label>🔊 TTS 服务选择</label>
            <select value={draft.tts.engine} onChange={e => setTts({ engine: e.target.value })}>
              <option>浏览器内置</option><option>MiMo TTS（外部 API）</option><option>Edge TTS</option><option>CosyVoice</option><option>离线</option>
            </select>
          </div>
          <div className="field"><label>🎙️ 音色选择</label>
            <select value={draft.tts.voice} onChange={e => setTts({ voice: e.target.value })}>
              <option>默认女声</option><option>默认男声</option><option>沉稳大叔</option><option>元气少女</option><option>空灵少年</option>
            </select>
          </div>
          <div className="field"><label>⚡ 语速（{draft.tts.speed}）</label><input type="range" min={0.5} max={2} step={0.1} value={draft.tts.speed} onChange={e => setTts({ speed: Number(e.target.value) })} /></div>
          <div className="field"><label>🎚️ 音调（{draft.tts.pitch}）</label><input type="range" min={0.5} max={2} step={0.1} value={draft.tts.pitch} onChange={e => setTts({ pitch: Number(e.target.value) })} /></div>
          <div className="field"><label>💗 情绪强度（{draft.tts.emotion}）</label><input type="range" min={0} max={1} step={0.05} value={draft.tts.emotion} onChange={e => setTts({ emotion: Number(e.target.value) })} /></div>

          <div className="card2" style={{ gridColumn: '1 / -1' }}>
            <div className="settings-sec-title" style={{ marginTop: 0 }}>🤖 语音生成模型 API</div>
            <div className="settings-grid">
              <div className="field"><label>语音生成模型</label><input value={draft.tts.model} onChange={e => setTts({ model: e.target.value })} placeholder="例如：edge-tts / cosyvoice-v2 / qwen-tts" /></div>
              <div className="field"><label>API 地址</label><input value={draft.tts.apiBase} onChange={e => setTts({ apiBase: e.target.value })} /></div>
              <div className="field"><label>API Key</label><input type="password" value={draft.tts.apiKey} onChange={e => setTts({ apiKey: e.target.value })} placeholder="sk-..." /></div>
            </div>
            <div className="hint">全局语音模型设置；角色卡可在「角色库」中单独覆盖 TTS（名字/音色/语速/音调/情绪）。</div>
          </div>
        </div>
      )}

      {/* 图片生成 */}
      {tab === 'image' && (
        <div className="settings-grid">
          <div className="field"><label>🖼️ 图片生成引擎</label><input value={draft.image.provider} readOnly /></div>
          <div className="field"><label>ComfyUI API 地址</label><input value={draft.image.baseUrl} onChange={e => set({ image: { ...draft.image, baseUrl: e.target.value } })} placeholder="http://127.0.0.1:8188" /></div>
          <div className="field"><label>LoRA 文件名</label><input value={draft.image.loraName} onChange={e => set({ image: { ...draft.image, loraName: e.target.value } })} placeholder="留空表示不使用 LoRA" /></div>
          <div className="field"><label>img2img 强度（{draft.image.img2imgDenoise}）</label><input type="range" min={0} max={1} step={0.05} value={draft.image.img2imgDenoise} onChange={e => set({ image: { ...draft.image, img2imgDenoise: Number(e.target.value) } })} /></div>
          <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <input type="checkbox" checked={draft.image.rmbgEnabled} onChange={e => set({ image: { ...draft.image, rmbgEnabled: e.target.checked } })} style={{ width: 'auto' }} />
            <label style={{ margin: 0 }}>生成透明背景图（RMBG）</label>
          </div>
          <div className="hint" style={{ gridColumn: '1 / -1' }}>本地默认仍走 application.yml 的 ComfyUI；保存后才把外部 API 作为当前运行时覆盖。</div>
        </div>
      )}

      {/* 地图生成 */}
      {tab === 'map' && (
        <div className="settings-grid">
          <div className="field"><label>🗺️ 地图生成方式</label>
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
          <div className="field"><label>🔀 地图组织</label>
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
          <div className="field"><label>📐 分辨率 宽（格）</label><input type="number" min={10} max={256} value={draft.mapGen.width} onChange={e => setMap({ width: Number(e.target.value) })} /></div>
          <div className="field"><label>📐 分辨率 高（格）</label><input type="number" min={8} max={256} value={draft.mapGen.height} onChange={e => setMap({ height: Number(e.target.value) })} /></div>
          <div className="field"><label>🧱 Tile 大小（px）</label>
            <select value={draft.mapGen.tileSize} onChange={e => setMap({ tileSize: Number(e.target.value) })}>
              <option value={16}>16</option><option value={32}>32</option><option value={48}>48</option>
            </select>
          </div>
          <div className="field"><label>⚙️ 默认生成规则</label><input value={draft.mapGen.rule} onChange={e => setMap({ rule: e.target.value })} /></div>
          <div className="field"><label>🎲 固定种子（可选）</label><input value={draft.mapGen.seed} onChange={e => setMap({ seed: e.target.value })} placeholder="留空 = 自动种子" /></div>
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
            <select value={draft.other.uiTheme} onChange={e => setOther({ uiTheme: e.target.value })}>
              <option>深色</option><option>浅色</option><option>跟随系统</option>
            </select>
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
