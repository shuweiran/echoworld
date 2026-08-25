/**
 * RoleDetailPage.tsx — 角色卡详情（子视图）
 *
 * 从角色选择页/角色库右键进入。秘密不向玩家展示。
 * 剧本杀模式默认角色：设定只读（不可编辑/删除，保证剧本秘密与真相不破坏）；
 * 其余角色（一般模式/自由/AI/新增）：可编辑设定 + 可删除。
 */
import { useEffect, useRef, useState } from 'react';
import { useDemoStore } from '../store';
import { getGeneralScriptById, getMurderScriptById } from '../mockData';
import type { RoleCard } from '../types';
import { RoleForm, MimoPreview, CloneAudioUpload, formToRole, roleToForm, type RoleFormValues } from '../components/RoleForm';
// P-0817-K：声线 → 后端角色库同步（PUT 404 时 POST 创建，自由角色持久化）
import { syncCharacterVoice } from '../../services/characterVoiceSync';
// P-0818-F：AI 形象生成 —— client 封装 + Gal 立绘系统联动（生成完成后刷新立绘状态）
import { api } from '../../api/client';
import { defaultGalStore } from '../../gal/GalStore';
// P-0818-F：角色名 → ID 映射注册（生成形象后局内立绘可查）
import { registerBackendMapping } from '../../gal/galDemoData';

/** 稳定空数组（zustand 选择器避免每次返回新引用引发无限重渲染） */
const EMPTY_ROLES: RoleCard[] = [];

// ── P-0818-F：AI 形象生成（后端 /api/ai-image/* 异步任务，5-15 分钟）──────────

/** 生成任务进度帧名 → 中文标签（后端 task.progress：avatar/表情名/fullbody/done） */
const IMG_FRAME_LABELS: Record<string, string> = {
  submitting: '提交中',
  queued: '排队中',
  avatar: '头像',
  happy: '开心',
  angry: '生气',
  sad: '伤心',
  surprised: '惊讶',
  embarrassed: '害羞',
  neutral: '平静',
  fullbody: '全身立绘',
  done: '收尾',
};

/** 进度帧名 → 中文标签（未知帧原样显示，空 → 排队中） */
function imgFrameLabel(p: string): string {
  const t = String(p || '').trim();
  return IMG_FRAME_LABELS[t] || t || '排队中';
}

/** 从角色卡描述字段提取外貌描述（简介 > 人格 > 说话风格 > 背景 > 动机；拼接后截断防超长） */
function extractAppearance(role: RoleCard): string {
  const parts = [role.intro, role.personality, role.talkStyle, role.background, role.motive]
    .filter(Boolean)
    .map(s => String(s).trim());
  return parts.join('，').slice(0, 240);
}

/** 头像展示优先级：fullbody_t（透明全身）→ avatar_t（透明头像）→ fullbody → avatar；空 = 无形象（emoji 兜底） */
function pickPortrait(frames: Record<string, string>): string {
  return frames.fullbody_t || frames.avatar_t || frames.fullbody || frames.avatar || '';
}

/** 将角色 ID 转为后端安全 ASCII ID（[A-Za-z0-9_-]{1,64}）：
 *  中文/特殊字符 → 替换为下划线；连续下划线合并；首尾去下划线；超 64 截断。
 *  空结果兜底用 'role_' + 4 位随机 hex。 */
function safeBackendId(raw: string): string {
  let id = (raw || '').replace(/[^A-Za-z0-9_-]/g, '_').replace(/_+/g, '_').replace(/^_|_$/g, '');
  if (!id) id = 'role_' + Math.random().toString(16).slice(2, 6);
  return id.slice(0, 64);
}

/** P-0818-F：AI 形象生成 UI 状态机（idle=未生成 / generating=生成中 / done=已生成 / error=失败） */
interface ImageGenState {
  status: 'idle' | 'generating' | 'done' | 'error';
  /** 当前帧名（avatar/happy/.../fullbody） */
  progress: string;
  error: string;
  /** frame → URL（磁盘扫描结果，生成中逐帧增长） */
  frames: Record<string, string>;
}

/** P-0818-F：轮询周期 5s；安全阀 30 分钟（生成任务上限约 15 分钟，留足余量） */
const IMG_POLL_INTERVAL_MS = 5000;
const IMG_POLL_MAX = 360;

/** P-0817-K：声线状态标签（详情页头部展示当前配置） */
function voiceStatusText(r: RoleCard): string {
  if (!r.voice_mode && r.ttsTone) return `AI 音色：${r.ttsTone}`;
  if (!r.voice_mode) return '未配置（使用默认音色）';
  if (r.voice_mode === 'basic') return 'basic · 内置音色';
  if (r.voice_mode === 'clone') return 'clone · 克隆参考音频';
  if (r.voice_mode === 'design') return 'design · 音色描述';
  return r.voice_mode;
}

export function RoleDetailPage() {
  const back = useDemoStore(s => s.back);
  const ctx = useDemoStore(s => s.selectCtx);
  const roleId = useDemoStore(s => s.selectedRoleId);
  const extraRoles = useDemoStore(s => s.extraRoles[ctx.scriptId ?? ''] || EMPTY_ROLES);
  const freeRoles = useDemoStore(s => s.freeRoles);
  const genRoles = useDemoStore(s => s.genRoles);
  const removeExtraRole = useDemoStore(s => s.removeExtraRole);
  const removeScriptRole = useDemoStore(s => s.removeScriptRole);
  const updateExtraRole = useDemoStore(s => s.updateExtraRole);
  const upsertRole = useDemoStore(s => s.upsertRole);

  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<RoleFormValues | null>(null);

  // P-0817-B：详情页直接配置 MiMo 声线（voice_mode / voice_data）—— 本地编辑态 + 保存结果提示
  // 初始值不依赖 role（其定义在下方，避免 TDZ）；实际值由下方 useEffect 同步
  // P-0817-K：未配置默认「未配置」（''），选择声线模式后才展开具体设置
  const [vm, setVm] = useState<string>('');
  const [vd, setVd] = useState('');
  const [voiceMsg, setVoiceMsg] = useState('');
  const [savingVoice, setSavingVoice] = useState(false);
  // 角色卡「TTS 与设置」隐藏栏：AI 始终按角色卡声线（未配置则系统默认）合成；
  // 隐藏栏只保留玩家修改声线的入口，不再提供独立的启动开关。
  const [barOpen, setBarOpen] = useState(false);
  const [panelOpen, setPanelOpen] = useState(false);

  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);

  const murder = (ctx.kind === 'murder' && ctx.scriptId)
    ? (getMurderScriptById(ctx.scriptId) ?? (generatedMurder?.id === ctx.scriptId ? generatedMurder : undefined))
    : undefined;
  const general = (ctx.kind === 'general' && ctx.scriptId)
    ? (getGeneralScriptById(ctx.scriptId) ?? (generatedGeneral?.id === ctx.scriptId ? generatedGeneral : undefined))
    : undefined;

  const defaultRoles = murder?.roles ?? general?.roles ?? [];
  // P-0817-K：本地库副本优先（自由/新增/AI 生成角色的编辑与声线持久化在本地角色卡，需优先展示；
  // 否则预设剧本角色被 upsertRole 写入本地库的同 id 副本遮蔽 → 关闭重开声线「丢失」）
  const roles = [...extraRoles, ...freeRoles, ...genRoles, ...defaultRoles];
  const role = roles.find(r => r.id === roleId);

  // P-0817-B：进入详情页 / 数据源变化（如编辑弹窗保存）时，声线编辑态跟随角色卡（置于 role 定义后、
  // 条件 return 前，保证 hooks 顺序稳定且依赖数组求值安全）
  useEffect(() => {
    setVm(role?.voice_mode || '');
    setVd(role?.voice_data || '');
    // P-0817-A：预设角色可能没有 voice_mode（demo store 不含后端声线）→ 从后端查询补充
    if (role && !role.voice_mode && role.name) {
      (async () => {
        try {
          const r = await fetch('/api/characters');
          const data = await r.json();
          const chars = data.value || data;
          const backend = chars.find((c: any) => c.name === role.name);
          if (backend?.voice_mode) {
            setVm(backend.voice_mode);
            setVd(backend.voice_data || '');
            // 同步更新 form（修改声线面板的下拉框）
            setForm(f => f ? { ...f, mimoTtsEnabled: true, voiceMode: backend.voice_mode, voiceData: backend.voice_data || '' } : f);
          }
        } catch { /* 静默失败 */ }
      })();
    }
  }, [role?.voice_mode, role?.voice_data, role?.name]);

  // 进入详情页不主动展开隐藏设置；已保存配置仍由消息播放链路自动带入。
  useEffect(() => {
    setBarOpen(false);
    setPanelOpen(false);
  }, [role?.voice_mode, role?.voice_data]);

  // ── P-0818-F：AI 形象生成 ────────────────────────────────────────────
  const [img, setImg] = useState<ImageGenState>({ status: 'idle', progress: '', error: '', frames: {} });
  const pollTimer = useRef<number | null>(null);
  const pollCount = useRef(0);

  function stopPolling() {
    if (pollTimer.current != null) {
      window.clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }

  /** 轮询一次：从 /api/ai-image/status 取任务进度 + 已生成帧（单请求同时覆盖两需求） */
  async function pollImageStatus() {
    if (!role) return;
    const bid = safeBackendId(role.id);
    try {
      const st = await api.aiImageStatus();
      const ch = ((st?.characters || []) as any[]).find(c => c?.id === bid);
      const task = ch?.task;
      const frames = (ch?.images || {}) as Record<string, string>;
      if (task?.status === 'running') {
        // 仍在生成：更新进度帧名 + 已落盘帧（头像先出可先行展示）
        setImg(s => ({ ...s, status: 'generating', progress: task.progress || s.progress, frames }));
      } else if (task?.status === 'failed') {
        stopPolling();
        setImg({ status: 'error', progress: '', error: task.error || '生成失败', frames });
      } else if (Object.keys(frames).length > 0) {
        // 任务结束且已有图 → 完成
        stopPolling();
        setImg({ status: 'done', progress: '', error: '', frames });
        // P-0818-F：联动 Gal 立绘系统刷新（Gal 对局中该角色立绘自动可用）
        void defaultGalStore.getState().refreshImageStatus().catch(() => { /* 静默降级 */ });
      } else if (++pollCount.current > IMG_POLL_MAX) {
        // 安全阀：任务既不可见也无图且超 30 分钟 → 停止（防无限轮询）
        stopPolling();
        setImg(s => ({ ...s, status: 'error', progress: '', error: '生成超时（30 分钟），请稍后重试' }));
      }
      // 其他（任务未就绪/网络瞬时异常）→ 保持轮询
    } catch {
      // 网络抖动：不中断轮询，下个周期再试
    }
  }

  function startPolling() {
    stopPolling();
    pollCount.current = 0;
    pollTimer.current = window.setInterval(() => { void pollImageStatus(); }, IMG_POLL_INTERVAL_MS);
  }

  /** 点击「生成形象」：注册（幂等）→ 触发 → 轮询到完成 */
  async function handleGenerateImage() {
    if (!role || img.status === 'generating') return;
    const backendId = safeBackendId(role.id);
    setImg(s => ({ status: 'generating', progress: 'submitting', error: '', frames: s.frames }));
    try {
      // 1. 注册到 AI 形象系统（幂等：后端按 id 覆盖档案）——appearance 从角色描述提取，无则默认
      const appearance = extractAppearance(role) || `${role.name}，动漫风格角色，精致五官，全身立绘`;
      const style = 'retro game character art style, 16-bit pixel art, clean outlines, flat colors';
      await api.aiImageRegisterCharacter({ id: backendId, name: role.name, appearance, style });
      // P-0818-F：前端本地注册角色名 → ID 映射（防重启后后端 name 丢失）
      registerBackendMapping(role.name, backendId);
      // 2. 触发生成（异步任务，5-15 分钟；重复提交后端幂等返回在途任务）
      const res = await api.aiImageGenerate(backendId);
      setImg(s => ({ ...s, progress: res?.progress || 'queued' }));
      // 3. 轮询状态直到 avatar 就绪（每 5s；进度帧名实时更新到按钮/状态行）
      startPolling();
    } catch (e: any) {
      stopPolling();
      setImg(s => ({ ...s, status: 'error', progress: '', error: e?.message || '触发生成失败' }));
    }
  }

  // 进入详情页 → 检查该角色是否已有 AI 形象（重启/重进/换角色后状态保持）；
  // 无已生成图但任务在途（如刷新页面）→ 直接恢复轮询
  useEffect(() => {
    if (!role) return;
    const bid = safeBackendId(role.id);
    let cancelled = false;
    (async () => {
      try {
        const res = await api.aiImageCharacterImages(bid);
        if (cancelled) return;
        const frames = (res?.images || {}) as Record<string, string>;
        if (Object.keys(frames).length > 0) {
          setImg({ status: 'done', progress: '', error: '', frames });
          return;
        }
        const st = await api.aiImageStatus();
        if (cancelled) return;
        const ch = ((st?.characters || []) as any[]).find(c => c?.id === bid);
        if (ch?.task?.status === 'running') {
          setImg({ status: 'generating', progress: ch.task.progress || 'queued', error: '', frames: {} });
          startPolling();
        }
      } catch {
        // 后端不可达：保持未生成态（按钮仍可用，点击失败时提示）
      }
    })();
    return () => { cancelled = true; stopPolling(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role?.id]);

  const portraitUrl = pickPortrait(img.frames);

  if (!role) {
    return (
      <div style={{ maxWidth: 560, margin: '0 auto' }}>
        <div className="empty-note">未找到该角色。</div>
        <div style={{ textAlign: 'center' }}><button className="btn2" onClick={back}>返回</button></div>
      </div>
    );
  }

  const isPresetMurder = ctx.kind === 'murder' && defaultRoles.some(r => r.id === role.id);
  const isExtra = extraRoles.some(r => r.id === role.id);
  /** 剧本杀默认角色：只读，不可改设定、不可删除 */
  const readonly = isPresetMurder;
  const editable = !readonly;

  const startEdit = () => {
    const f = roleToForm(role);
    // P-0817-A：用后端查询到的声线覆盖（预设角色 demo store 无 voice_mode）
    if (vm) { f.mimoTtsEnabled = true; f.voiceMode = vm; f.voiceData = vd; }
    setForm(f);
    setEditing(true);
  };

  const saveEdit = () => {
    if (!form || !form.name.trim()) return;
    const updated = formToRole(form, { ...role, id: role.id });
    if (isExtra) {
      updateExtraRole(ctx.scriptId ?? '', updated);
    } else {
      upsertRole({ ...updated, source: 'free', homeScripts: role.homeScripts });
    }
    setEditing(false);
    // P-0817-I：编辑弹窗保存时，声线变更也同步后端角色库（PUT /api/characters/{name}，
    // P-0817-A 后端已透传 voice_mode/voice_data）
    // P-0817-K：后端无此角色（404）时自动 POST 创建再同步；清除场景不创建空角色
    if (form.name === role.name) {
      const mode = form.voiceMode || '';
      const data = (form.voiceData || '').trim();
      const changed = mode !== (role.voice_mode || '') || data !== (role.voice_data || '');
      const enabled = form.mimoTtsEnabled && !!mode;
      if (enabled && changed) {
        void syncCharacterVoice(role.name, mode, data, role).catch(() => { /* 静默降级 */ });
      } else if (!form.mimoTtsEnabled && (role.voice_mode || role.voice_data)) {
        // 勾选取消 → 清除后端声线（PUT 空串；角色不在后端库时不创建）
        void syncCharacterVoice(role.name, '', '', role, { createIfMissing: false }).catch(() => { /* 静默降级 */ });
      }
    }
  };

  const doDelete = () => {
    if (isExtra) {
      removeExtraRole(ctx.scriptId ?? '', role.id);
    } else {
      removeScriptRole(ctx.scriptId ?? '', role.id);
    }
    back();
  };

  // P-0817-B：保存声线 —— ①本地角色卡（demo2 store，TTS 播放的本地解析数据源）②同步后端角色库
  // P-0817-K：后端无此角色（404）时自动 POST 创建（任务书「先 POST 创建再 PUT 更新」）；
  // 未配置（''）= 清除本地声线 + 后端清除（PUT 空串，nvl 归一为 null）；清除不创建空角色
  const saveVoice = async () => {
    if (!role) return;
    const mode = vm || '';
    const trimmed = vd.trim();
    const updated = {
      ...role,
      voice_mode: mode || undefined,
      voice_data: mode ? (trimmed || undefined) : undefined,
    };
    if (isExtra) {
      updateExtraRole(ctx.scriptId ?? '', updated);
    } else {
      upsertRole({ ...updated, source: 'free', homeScripts: role.homeScripts });
    }
    setVoiceMsg('✅ 已保存到本地角色卡');
    setSavingVoice(true);
    try {
      const result = await syncCharacterVoice(role.name, mode, trimmed, role, { createIfMissing: !!mode });
      setVoiceMsg(
        result === 'updated' ? '✅ 已保存（本地角色卡 + 后端角色库）'
          : result === 'created' ? '✅ 已保存（本地角色卡 + 已同步到后端角色库）'
          : '✅ 已保存到本地（后端角色库同步失败，仅本地生效）',
      );
    } finally {
      setSavingVoice(false);
    }
  };

  return (
    <div className={`role-detail${panelOpen ? ' rd-with-panel' : ''}`}>
      <div className="page-head">
        <button className="btn2 btn2-ghost" onClick={back}>← 返回</button>
        <h2>角色卡详情</h2>
      </div>

      <div className="rd-layout">
      <div className="card2 rd-card">
        <div className="rd-head">
          {/* P-0818-F：有 AI 形象 → 缩略图替换 emoji；未生成 → emoji 兜底 */}
          <div className="rd-avatar">
            {portraitUrl ? (
              <img className="rd-avatar-img" src={portraitUrl} alt={role.name} />
            ) : role.avatar}
          </div>
          <div>
            <div className="rd-name">{role.name}</div>
            <div className="rd-tags">
              <span className="tag2">{role.personality}</span>
              {role.hasSecret && <span className="tag2 tag2-danger">🔒 有秘密</span>}
              {role.source === 'free' && <span className="tag2 tag2-cyan">自由角色</span>}
              {role.source === 'ai' && <span className="tag2 tag2-gold">AI 生成</span>}
              {readonly && <span className="tag2">🔒 剧本杀角色 · 设定只读</span>}
            </div>
            {/* P-0818-F：AI 形象生成状态行（生成中显示当前帧进度 / 失败显示原因 / 完成提示） */}
            {img.status !== 'idle' && (
              <div className={`rd-img-status rd-img-${img.status}`}>
                {img.status === 'generating' && <>⏳ 正在生成 AI 形象：{imgFrameLabel(img.progress)}</>}
                {img.status === 'error' && <>⚠️ {img.error}</>}
                {img.status === 'done' && <>✅ AI 形象已生成（全身立绘 + 头像 + 表情）</>}
              </div>
            )}
          </div>
        </div>

        <div className="rd-field"><label>简介</label><div>{role.intro}</div></div>
        <div className="rd-field"><label>性格</label><div>{role.personality}</div></div>
        <div className="rd-field"><label>说话风格</label><div>{role.talkStyle}</div></div>
        {role.background && <div className="rd-field"><label>背景故事</label><div>{role.background}</div></div>}
        {role.motive && <div className="rd-field"><label>动机</label><div>{role.motive}</div></div>}
        {role.hasSecret && (
          <div className="rd-secret">🔒 该角色身怀秘密（内容在对局中向你揭晓，此处保密）</div>
        )}

        {/* 角色卡底部隐藏栏：AI 默认自动使用本卡声线；玩家按需打开修改。 */}
        <div className="rd-tts-bar" data-open={barOpen ? 'true' : 'false'}>
          <button
            type="button"
            className="rd-tts-handle"
            aria-expanded={barOpen}
            aria-controls="rd-tts-content"
            onClick={() => setBarOpen(o => !o)}
          >
            <span className="rd-tts-handle-title">🎙️ TTS 与设置</span>
            <span className="rd-tts-status">{role.voice_mode ? '已配置角色声线' : 'AI 自动使用默认音色'}</span>
            <span className="rd-tts-chevron" aria-hidden="true">{barOpen ? '▲' : '▼'}</span>
          </button>
          <div className="rd-tts-content" id="rd-tts-content">
            <div className="rd-tts-content-inner">
              <div className="rd-tts-row">
                <span>AI 对话会自动带入本角色卡的声线设置。</span>
                <button type="button" className="btn2 btn2-sm" onClick={() => setPanelOpen(true)}>⚙️ 修改声线</button>
              </div>
              <div className="rd-tts-hint">当前配置：{voiceStatusText(role)}；未配置时使用系统默认音色。</div>
            </div>
          </div>
        </div>

        <div style={{ marginTop: 20, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {/* P-0818-F：生成形象 —— 任意角色可用（含剧本杀只读角色）；生成中禁用防重复提交，
              已生成 → 点击重新生成 */}
          <button
            className="btn2 btn2-primary"
            onClick={() => void handleGenerateImage()}
            disabled={img.status === 'generating'}
            title={img.status === 'done' ? '点击重新生成' : '为该角色生成 AI 形象（全身立绘 + 头像 + 表情，约 5-15 分钟）'}
          >
            {img.status === 'generating'
              ? `⏳ 生成中...（${imgFrameLabel(img.progress)}）`
              : img.status === 'done'
                ? '✅ 已生成形象'
                : '🎨 生成形象'}
          </button>
          {editable && (
            <>
              <button className="btn2 btn2-primary" onClick={startEdit}>✏️ 编辑设定</button>
              <button className="btn2 btn2-danger" onClick={doDelete}>🗑️ 删除此角色</button>
            </>
          )}
          <button className="btn2 btn2-ghost" onClick={back}>返回</button>
        </div>
      </div>

      {/* 角色卡右侧隐藏的 TTS 设置面板，仅供玩家修改默认自动应用的声线。 */}
      <aside className="rd-tts-panel" data-open={panelOpen ? 'true' : 'false'} aria-hidden={!panelOpen} aria-label="TTS 详细设置">
        <div className="rd-tts-panel-head">
          <div className="rd-tts-panel-title">🎙️ TTS 详细设置</div>
          <button type="button" className="modal-close" onClick={() => setPanelOpen(false)} aria-label="收起设置">✕</button>
        </div>
        <div className="tts-settings-scroll">
          <div className="settings-grid">
            <div className="rd-tts-hint">AI 对话默认使用此角色卡声线；不配置时自动回退系统默认音色。</div>
            <>
                <div className="field">
                  <label>声线模式</label>
                  <select value={vm} onChange={e => setVm(e.target.value)}>
                    <option value="">未配置（使用默认音色）</option>
                    <option value="basic">basic · 内置音色</option>
                    <option value="clone">clone · 克隆参考音频</option>
                    <option value="design">design · 音色描述</option>
                  </select>
                </div>
                {vm ? (
                  <>
                    <div className="field">
                      <label>声线数据</label>
                      {vm === 'clone' ? (
                        <CloneAudioUpload value={vd} onChange={setVd} />
                      ) : (
                        <input
                          value={vd}
                          onChange={e => setVd(e.target.value)}
                          placeholder={vm === 'design'
                            ? '音色描述，如：低沉磁性男声，略带沙哑'
                            : '内置音色名（如：女声温柔），留空用默认'}
                        />
                      )}
                    </div>
                    <div className="field">
                      <label>🔊 试听</label>
                      <MimoPreview name={role.name} mode={vm} voiceData={vd} previewKey="role-detail-preview" />
                    </div>
                  </>
                ) : (
                  <div className="field" style={{ gridColumn: '1 / -1' }}>
                    <span className="hint">选择声线模式后展开具体设置（声线数据 / 试听）。</span>
                  </div>
                )}
                <div className="field" style={{ gridColumn: '1 / -1' }}>
                  <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                    <button className="btn2 btn2-primary btn2-sm" onClick={() => void saveVoice()} disabled={savingVoice}>
                      {savingVoice ? '保存中…' : '💾 保存声线'}
                    </button>
                    {voiceMsg && <span className="hint" style={{ color: '#8ef0d8' }}>{voiceMsg}</span>}
                  </div>
                </div>
            </>
          </div>
        </div>
      </aside>
      </div>

      {/* 编辑设定弹窗 */}
      {editing && form && (
        <div className="modal-mask" onClick={() => setEditing(false)}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720 }}>
            <div className="modal-head">
              <div className="modal-title">✏️ 编辑「{role.name}」的设定</div>
              <button className="modal-close" onClick={() => setEditing(false)}>✕</button>
            </div>
            <RoleForm values={form} onChange={setForm} showSecret={false} />
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
              <button className="btn2 btn2-ghost" onClick={() => setEditing(false)}>取消</button>
              <button className="btn2 btn2-primary" onClick={saveEdit}>💾 保存设定</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
