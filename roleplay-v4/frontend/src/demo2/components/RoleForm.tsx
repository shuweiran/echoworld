/**
 * RoleForm.tsx — 角色卡表单（共享）
 *
 * 手动生成/编辑角色的统一表单：名字 / 说话风格 / 单独 TTS 设置 / 人格设定 / 背景故事 等模块。
 * 角色库、角色选择页「添加角色」、玩家角色创建 统一复用。
 */
import { useRef, useState, type ChangeEvent } from 'react';
import type { RoleCard, RoleTts } from '../types';
import { AvatarPicker } from './AvatarPicker';
import { useMimoTts, warmAudio } from '../../services/mimoTts';

/** P-0817-J：clone 参考音频上限（与 MiMo 服务端一致，≤10MB） */
export const CLONE_MAX_MB = 10;
export const CLONE_MAX_BYTES = CLONE_MAX_MB * 1024 * 1024;
export const CLONE_MAX_SECONDS = 10;

/** 字节数 → 人类可读（B/KB/MB） */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

/** ArrayBuffer → base64（分块拼接，避免大文件栈溢出） */
export function bufToBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  const CHUNK = 0x8000;
  const parts: string[] = [];
  for (let i = 0; i < bytes.length; i += CHUNK) {
    let bin = '';
    const end = Math.min(i + CHUNK, bytes.length);
    for (let j = i; j < end; j++) bin += String.fromCharCode(bytes[j]);
    parts.push(bin);
  }
  return btoa(parts.join(''));
}

/** 从 base64 Data URL 估算原始字节数（仅用于展示，未命中返回 null） */
export function dataUrlByteSize(v: string): number | null {
  const m = /^data:[^;]*;base64,(.*)$/.exec(v);
  if (!m || !m[1]) return null;
  let len = m[1].length;
  if (m[1].endsWith('==')) len -= 2;
  else if (m[1].endsWith('=')) len -= 1;
  return Math.floor((len * 3) / 4);
}

/** P-0817-J：clone 参考音频校验 + 转换 —— wav/mp3 字节 → MiMo 兼容 Data URL（wav→audio/wav、mp3→audio/mpeg）；
 * 非法格式/超限返回错误文案。纯函数，可单测。 */
export function buildCloneDataUrl(name: string, buf: ArrayBuffer): { dataUrl: string } | { error: string } {
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (ext !== 'wav' && ext !== 'mp3') return { error: '仅支持 wav / mp3 格式的音频文件' };
  if (buf.byteLength > CLONE_MAX_BYTES) return { error: `音频文件不能超过 ${CLONE_MAX_MB}MB（当前 ${formatBytes(buf.byteLength)}）` };
  const mime = ext === 'mp3' ? 'audio/mpeg' : 'audio/wav';
  return { dataUrl: `data:${mime};base64,${bufToBase64(buf)}` };
}

/** 在浏览器读取音频元数据，拒绝无法确定时长或超过 10 秒的文件。 */
export function readCloneAudioDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const audio = new Audio();
    const cleanup = () => {
      URL.revokeObjectURL(url);
      audio.removeAttribute('src');
      audio.load();
    };
    audio.preload = 'metadata';
    audio.onloadedmetadata = () => {
      const duration = audio.duration;
      cleanup();
      if (!Number.isFinite(duration) || duration <= 0) reject(new Error('无法读取音频时长，请换一个标准 wav/mp3 文件'));
      else resolve(duration);
    };
    audio.onerror = () => {
      cleanup();
      reject(new Error('无法解析音频文件，请确认文件未损坏'));
    };
    audio.src = url;
  });
}

/** P-0817-J：clone 模式参考音频上传控件 —— 选文件 → 读为 base64 Data URL（wav→audio/wav、mp3→audio/mpeg，
 * 与 MiMo voiceclone API 的 audio.voice 字段格式一致）；显示已选文件名 + 大小，支持点击更换 / 移除。
 * 已有值（保存过的 Data URL 或旧路径）在未重新选择时原样展示。 */
export function CloneAudioUpload({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [picked, setPicked] = useState<{ name: string; size: number; dataUrl: string } | null>(null);
  const pickedMatches = !!picked && picked.dataUrl === value;

  const pickFile = () => inputRef.current?.click();

  const handleFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选择同一文件（onChange 需值变化才触发）
    if (!file) return;
    const ext = (file.name.split('.').pop() || '').toLowerCase();
    if (ext !== 'wav' && ext !== 'mp3') {
      window.alert('仅支持 wav / mp3 格式的音频文件');
      return;
    }
    if (file.size > CLONE_MAX_BYTES) {
      window.alert(`音频文件不能超过 ${CLONE_MAX_MB}MB（当前 ${formatBytes(file.size)}）`);
      return;
    }
    try {
      const duration = await readCloneAudioDuration(file);
      if (duration > CLONE_MAX_SECONDS) {
        window.alert(`参考音频不能超过 ${CLONE_MAX_SECONDS} 秒（当前 ${duration.toFixed(2)} 秒）`);
        return;
      }
    } catch (err) {
      window.alert(err instanceof Error ? err.message : '无法读取音频时长');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const result = buildCloneDataUrl(file.name, reader.result as ArrayBuffer);
      if ('error' in result) {
        window.alert(result.error);
        return;
      }
      setPicked({ name: file.name, size: file.size, dataUrl: result.dataUrl });
      onChange(result.dataUrl);
    };
    reader.onerror = () => window.alert('读取音频文件失败，请重试');
    reader.readAsArrayBuffer(file);
  };

  // 展示信息：优先刚选的文件；否则按已有值推导（Data URL 估算大小，旧路径原样显示）
  let displayName = '';
  let displaySize: string | null = null;
  if (pickedMatches) {
    displayName = picked!.name;
    displaySize = formatBytes(picked!.size);
  } else if (/^data:audio\//.test(value)) {
    displayName = '已选参考音频';
    const sz = dataUrlByteSize(value);
    if (sz !== null) displaySize = `约 ${formatBytes(sz)}`;
  } else if (value) {
    displayName = value;
  }
  const hasValue = !!displayName;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <input
          ref={inputRef}
          type="file"
          accept=".wav,.mp3,audio/wav,audio/mpeg"
          style={{ display: 'none' }}
          onChange={handleFile}
        />
        {hasValue ? (
          <>
            <span className="hint" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: '#8ef0d8', maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={displayName}>
              🎵 {displayName}{displaySize ? `（${displaySize}）` : ''}
            </span>
            <button type="button" className="btn2 btn2-sm" onClick={pickFile}>🔄 更换文件</button>
            <button type="button" className="btn2 btn2-sm btn2-ghost" onClick={() => { setPicked(null); onChange(''); }}>✕ 移除</button>
          </>
        ) : (
          <button type="button" className="btn2 btn2-sm" onClick={pickFile}>📁 选择音频文件</button>
        )}
      </div>
      <div className="hint" style={{ marginTop: 4 }}>支持 wav / mp3，≤{CLONE_MAX_MB}MB，≤{CLONE_MAX_SECONDS} 秒；建议提供清晰的说话音频</div>
    </div>
  );
}

export function emptyRoleTts(): RoleTts {
  return { engine: '浏览器内置', model: 'edge-tts', apiBase: 'https://tts.example.com/v1', apiKey: '', voice: '默认女声', speed: 1, pitch: 1, emotion: 0.5 };
}

export interface RoleFormValues {
  name: string;
  avatar: string;
  intro: string;
  personality: string;
  talkStyle: string;
  background: string;
  secret: string;
  hasSecret: boolean;
  tts: RoleTts;
  /** 单独 TTS：是否覆盖全局 */
  ttsEnabled: boolean;
  /** P-0817-A（MiMo TTS 声线）：是否启用（对话消息语音播放） */
  mimoTtsEnabled: boolean;
  /** voice_mode：basic=内置音色 / clone=参考音频 / design=音色描述 */
  voiceMode: string;
  /** voice_data：basic=内置音色名 / clone=参考音频路径或 data URL / design=音色描述 */
  voiceData: string;
}

export function roleToForm(r: RoleCard): RoleFormValues {
  return {
    name: r.name,
    avatar: r.avatar,
    intro: r.intro,
    personality: r.personality,
    talkStyle: r.talkStyle,
    background: r.background ?? '',
    secret: r.secret ?? '',
    hasSecret: r.hasSecret,
    tts: r.tts ?? emptyRoleTts(),
    ttsEnabled: !!r.tts,
    mimoTtsEnabled: !!(r.voice_mode || r.voice_data),
    // P-0817-K：未配置声线的角色默认「未配置」（''），勾选后先选声线模式再展开具体设置
    voiceMode: r.voice_mode || '',
    voiceData: r.voice_data || '',
  };
}

export function formToRole(v: RoleFormValues, base: Partial<RoleCard>): RoleCard {
  return {
    ...base,
    name: v.name,
    avatar: v.avatar,
    intro: v.intro || `${v.name}，由你创造的角色。`,
    personality: v.personality || '待定',
    talkStyle: v.talkStyle || '待定',
    background: v.background || '',
    secret: v.secret || '',
    hasSecret: v.hasSecret || !!v.secret,
    tts: v.ttsEnabled ? { ...v.tts } : undefined,
    // P-0817-A：MiMo 声线透传（勾选才写；未勾选清除，避免旧配置残留）
    // P-0817-K：声线模式选「未配置」（''）同样清除——选择后才展开的语义与持久化一致
    voice_mode: v.mimoTtsEnabled && v.voiceMode ? v.voiceMode : undefined,
    voice_data: v.mimoTtsEnabled && v.voiceMode ? (v.voiceData || undefined) : undefined,
  } as RoleCard;
}

interface RoleFormProps {
  values: RoleFormValues;
  onChange: (v: RoleFormValues) => void;
  /** 剧本杀角色是否需要「秘密」模块 */
  showSecret?: boolean;
}

export function RoleForm({ values: v, onChange: set, showSecret = false }: RoleFormProps) {
  return (
    <div className="settings-grid">
      <div className="field">
        <label>🪪 角色名字 *</label>
        <input value={v.name} onChange={e => set({ ...v, name: e.target.value })} placeholder="例如：林晚秋" />
      </div>
      <div className="field">
        <label>🗣️ 说话风格</label>
        <input value={v.talkStyle} onChange={e => set({ ...v, talkStyle: e.target.value })} placeholder="例如：言辞犀利，直指要害" />
      </div>

      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label>头像</label>
        <AvatarPicker value={v.avatar} onChange={a => set({ ...v, avatar: a })} />
      </div>

      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label>🧠 人格设定</label>
        <input value={v.personality} onChange={e => set({ ...v, personality: e.target.value })} placeholder="例如：冷静理智 / 热情冲动 / 狡黠多谋" />
      </div>

      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label>📖 背景故事</label>
        <textarea rows={3} value={v.background} onChange={e => set({ ...v, background: e.target.value })} placeholder="这个角色从何而来？经历过什么？" />
      </div>

      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label>📝 一句话简介</label>
        <input value={v.intro} onChange={e => set({ ...v, intro: e.target.value })} placeholder="角色卡上的简短介绍" />
      </div>

      {showSecret && (
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <label style={{ flexDirection: 'row', gap: 8, display: 'flex', alignItems: 'center' }}>
            <input type="checkbox" checked={v.hasSecret} onChange={e => set({ ...v, hasSecret: e.target.checked })} style={{ width: 'auto' }} />
            🔒 剧本杀秘密角色
          </label>
          {v.hasSecret && (
            <textarea rows={2} value={v.secret} onChange={e => set({ ...v, secret: e.target.value })} placeholder="秘密内容（仅该角色可见）" />
          )}
        </div>
      )}

      {/* 单独 TTS 设置 */}
      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label style={{ flexDirection: 'row', gap: 8, display: 'flex', alignItems: 'center' }}>
          <input type="checkbox" checked={v.ttsEnabled} onChange={e => set({ ...v, ttsEnabled: e.target.checked })} style={{ width: 'auto' }} />
          🔊 单独 TTS 设置（不勾选则用全局设置）
        </label>
      </div>
      {v.ttsEnabled && (
        <div className="settings-grid" style={{ gridColumn: '1 / -1' }}>
          <div className="field"><label>TTS 引擎</label>
            <select value={v.tts.engine} onChange={e => set({ ...v, tts: { ...v.tts, engine: e.target.value } })}>
              <option>浏览器内置</option><option>Edge TTS</option><option>CosyVoice</option><option>离线</option>
            </select>
          </div>
          <div className="field"><label>语音生成模型</label>
            <input value={v.tts.model} onChange={e => set({ ...v, tts: { ...v.tts, model: e.target.value } })} />
          </div>
          <div className="field"><label>模型 API 地址</label>
            <input value={v.tts.apiBase} onChange={e => set({ ...v, tts: { ...v.tts, apiBase: e.target.value } })} />
          </div>
          <div className="field"><label>API Key</label>
            <input type="password" value={v.tts.apiKey} onChange={e => set({ ...v, tts: { ...v.tts, apiKey: e.target.value } })} placeholder="sk-..." />
          </div>
          <div className="field"><label>🎙️ 音色</label>
            <select value={v.tts.voice} onChange={e => set({ ...v, tts: { ...v.tts, voice: e.target.value } })}>
              <option>默认女声</option><option>默认男声</option><option>沉稳大叔</option><option>元气少女</option>
            </select>
          </div>
          <div className="field"><label>⚡ 语速（{v.tts.speed}）</label>
            <input type="range" min={0.5} max={2} step={0.1} value={v.tts.speed} onChange={e => set({ ...v, tts: { ...v.tts, speed: Number(e.target.value) } })} />
          </div>
          <div className="field"><label>🎚️ 音调（{v.tts.pitch}）</label>
            <input type="range" min={0.5} max={2} step={0.1} value={v.tts.pitch} onChange={e => set({ ...v, tts: { ...v.tts, pitch: Number(e.target.value) } })} />
          </div>
          <div className="field"><label>💗 情绪强度（{v.tts.emotion}）</label>
            <input type="range" min={0} max={1} step={0.05} value={v.tts.emotion} onChange={e => set({ ...v, tts: { ...v.tts, emotion: Number(e.target.value) } })} />
          </div>
        </div>
      )}

      {/* P-0817-A（MiMo TTS 声线）：对话消息语音播放的角色声线配置（basic/clone/design） */}
      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label style={{ flexDirection: 'row', gap: 8, display: 'flex', alignItems: 'center' }}>
          <input type="checkbox" checked={v.mimoTtsEnabled} onChange={e => set({ ...v, mimoTtsEnabled: e.target.checked })} style={{ width: 'auto' }} />
          🎙️ MiMo 声线（对话消息语音播放）
        </label>
        {v.mimoTtsEnabled && (
          <div className="hint" style={{ marginTop: 4 }}>
            为角色配置 MiMo 语音合成声线；对话消息旁的 🎙 按钮按此播放。未配置的角色使用默认音色。
          </div>
        )}
      </div>
      {v.mimoTtsEnabled && (
        <div className="settings-grid" style={{ gridColumn: '1 / -1' }}>
          {/* P-0817-K：先选声线模式 —— 具体设置（声线数据/试听）在选择后才展开，不一开始全部显示 */}
          <div className="field"><label>声线模式</label>
            <select value={v.voiceMode} onChange={e => set({ ...v, voiceMode: e.target.value })}>
              <option value="">未配置（使用默认音色）</option>
              <option value="basic">basic · 内置音色</option>
              <option value="clone">clone · 克隆参考音频</option>
              <option value="design">design · 音色描述</option>
            </select>
          </div>
          {v.voiceMode ? (
            /* P-0817-K：声线设置区可滚动（内容超出视口时），样式见 styles/voice.css .tts-settings-scroll */
            <div className="tts-settings-scroll" style={{ gridColumn: '1 / -1' }}>
              <div className="settings-grid">
                <div className="field"><label>声线数据</label>
                  {v.voiceMode === 'clone' ? (
                    <CloneAudioUpload value={v.voiceData} onChange={vd => set({ ...v, voiceData: vd })} />
                  ) : (
                    <input
                      value={v.voiceData}
                      onChange={e => set({ ...v, voiceData: e.target.value })}
                      placeholder={v.voiceMode === 'design'
                        ? '音色描述，如：低沉磁性男声，略带沙哑'
                        : '内置音色名（如：女声温柔），留空用默认'}
                    />
                  )}
                </div>
                <div className="field"><label>🔊 试听</label>
                  <MimoPreview
                    name={v.name}
                    mode={v.voiceMode}
                    voiceData={v.voiceData}
                    disabled={!v.name.trim()}
                  />
                </div>
              </div>
            </div>
          ) : (
            <div className="hint" style={{ gridColumn: '1 / -1' }}>
              选择声线模式后展开具体设置（声线数据 / 试听）。
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** P-0817-A：MiMo 声线试听按钮 —— 用当前声线参数合成并播放一句固定试听文本。
 * P-0817-B：导出共享（角色卡详情页复用）；previewKey 可选 —— 多个试听区共存时各自独立
 * 播放状态（如详情页内联试听 + 编辑弹窗内试听同屏），缺省 'roleform-preview' 保持旧行为。 */
export function MimoPreview({ name, mode, voiceData, disabled, previewKey }: { name: string; mode: string; voiceData: string; disabled?: boolean; previewKey?: string }) {
  const { state, error, speak, stop } = useMimoTts(previewKey || 'roleform-preview');
  const busy = state === 'loading' || state === 'playing';
  const label = state === 'loading' ? '合成中…' : state === 'playing' ? '⏹ 停止' : state === 'error' ? '⚠️ 失败' : '▶ 试听';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      <button
        type="button"
        className="btn2"
        disabled={disabled || (state === 'loading')}
        onClick={() => {
          if (state === 'playing') { stop(); return; }
          // P-0817-N：手势内预热 AudioContext（防 fetch 合成后 resume 被 Chrome 拒绝 → 无声）
          warmAudio();
          void speak(`你好，我是${name || '新角色'}。很高兴认识你。`, {
            mode: mode || 'basic',
            voice_data: voiceData || undefined,
          });
        }}
      >{busy ? <span className="tts-spin">⏳</span> : null}{label}</button>
      {state === 'error' && <span className="hint" style={{ color: '#ff9b9b' }}>{error || '合成失败'}</span>}
    </div>
  );
}
