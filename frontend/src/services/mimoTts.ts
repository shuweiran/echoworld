/**
 * mimoTts.ts — MiMo TTS 合成与播放服务（P-0817-A 后端就绪 · 前端接入批次）
 *
 * 职责：
 *  ① 调 POST /api/tts/mimo/synthesize?json=true 合成（真实 LLM 音色，耗时数秒）；
 *  ② Web Audio API 播放 base64 WAV（浏览器解码，对齐既有 ttsPlayer.ts 先例）；
 *  ③ 全局单例：同一时间只播一条（多消息并发点击不串音，后点击者顶替先播放者）；
 *  ④ 状态订阅（React 消费）：idle | loading | playing | error；
 *  ⑤ 声线解析：本地角色卡（demo2 store）显式声线优先 → 后端角色库按名解析兜底。
 */
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { extractSpeechText } from './ttsText';
// 静态 import（Vite ESM 环境；demo2 store 不依赖本模块，无循环依赖）——
// 用途：播放时按角色名从本地角色卡解析显式声线（不依赖后端角色库）
import { useDemoStore } from '../demo2/store';

export type TtsPlayState = 'idle' | 'loading' | 'playing' | 'error';

export interface TtsPlayStatus {
  /** 当前消息 key（空 = 无活动） */
  key: string;
  state: TtsPlayState;
  error?: string;
}

export interface TtsSpeakOptions {
  /** 按角色名从角色库解析声线（voice_mode/voice_data）兜底 */
  character?: string;
  /** 显式声线（本地角色卡配置优先；三者任一存在即覆盖 character 解析） */
  mode?: string;
  voice_data?: string;
  voice?: string;
  tone?: string;
}

// ── 单例状态 ─────────────────────────────────────────────

let status: TtsPlayStatus = { key: '', state: 'idle' };
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach(l => l());
}

function setStatus(key: string, state: TtsPlayState, error?: string) {
  status = { key, state, error };
  emit();
}

export function getTtsStatus(): TtsPlayStatus {
  return status;
}

export function subscribeTtsStatus(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// ── 静音控制（P-0817-I：全局静音 + 单角色静音，localStorage 持久化） ──

const MUTED_GLOBAL_KEY = 'roleplay_tts_global_muted';
const MUTED_CHARS_KEY = 'roleplay_tts_muted_chars';

function loadGlobalMuted(): boolean {
  try { return localStorage.getItem(MUTED_GLOBAL_KEY) === '1'; } catch { return false; }
}

function loadMutedChars(): Set<string> {
  try {
    const raw = localStorage.getItem(MUTED_CHARS_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr.filter((x: unknown) => typeof x === 'string') : []);
  } catch { return new Set(); }
}

let globalMuted = loadGlobalMuted();
let mutedChars = loadMutedChars();
/** 当前播放/合成中消息对应的角色名（用于对该角色静音时立即停播） */
let currentSpeakCharacter: string | undefined;

function persistMutedChars() {
  try { localStorage.setItem(MUTED_CHARS_KEY, JSON.stringify([...mutedChars])); } catch { /* ignore */ }
}

/** 是否全局静音 */
export function isGlobalMuted(): boolean {
  return globalMuted;
}

/** 是否指定角色被单独静音 */
export function isCharacterMuted(name?: string): boolean {
  if (!name) return false;
  return mutedChars.has(name);
}

/** 是否整体静音（全局静音 或 该角色单独静音）——speak 入口拦截依据 */
export function isTtsMuted(name?: string): boolean {
  return globalMuted || isCharacterMuted(name);
}

/** 设置全局静音；静音时立即停掉正在播放的语音 */
export function setGlobalMuted(v: boolean) {
  if (globalMuted === v) return;
  globalMuted = v;
  try { localStorage.setItem(MUTED_GLOBAL_KEY, v ? '1' : '0'); } catch { /* ignore */ }
  if (v) {
    currentSpeakCharacter = undefined;
    stopActiveSource();
    setStatus(status.key, 'idle');
  }
  emit();
}

/** 设置单个角色静音；对该角色正在播放的语音立即停止 */
export function setCharacterMuted(name: string, v: boolean) {
  if (!name) return;
  const changed = v ? !mutedChars.has(name) : mutedChars.has(name);
  if (!changed) return;
  if (v) {
    mutedChars.add(name);
    if (currentSpeakCharacter === name) {
      currentSpeakCharacter = undefined;
      stopActiveSource();
      setStatus(status.key, 'idle');
    }
  } else {
    mutedChars.delete(name);
  }
  persistMutedChars();
  emit();
}

/** 切换单个角色静音（返回新状态） */
export function toggleCharacterMuted(name: string): boolean {
  const next = !mutedChars.has(name);
  setCharacterMuted(name, next);
  return next;
}

/** 当前被单独静音的角色名列表（设置面板展示用） */
export function getMutedCharacters(): string[] {
  return [...mutedChars];
}

// ── Web Audio 播放（单例，同一时间只播一条） ───────────────

let audioCtx: AudioContext | null = null;
let activeSource: AudioBufferSourceNode | null = null;

function ensureCtx(): AudioContext {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
  }
  return audioCtx;
}

/**
 * P-0817-N：在用户手势内预热 AudioContext（创建 + resume）。
 * speak 里 fetch 合成耗时数秒，若等到合成完才 resume，Chrome 的用户激活窗口已过期，
 * resume() 被拒（NotAllowedError）→ 音频排队但不输出 → 按钮显示 playing 但实际无声。
 * 点击按钮的同步阶段调用本函数，保证 ctx 在激活窗口内转为 running。
 */
export function warmAudio(): void {
  try {
    const ctx = ensureCtx();
    if (ctx.state === 'suspended') void ctx.resume().catch(() => { /* 播放时再重试 */ });
  } catch { /* 忽略（非浏览器环境/禁用） */ }
}

function stopActiveSource() {
  if (activeSource) {
    try { activeSource.onended = null; activeSource.stop(); } catch { /* 已停止 */ }
    activeSource = null;
  }
}

/** 播放 base64 音频（wav/mp3 浏览器均解码）；返回播放完成 Promise（被顶替时 resolve）。 */
async function playBase64(base64: string): Promise<void> {
  const ctx = ensureCtx();
  // P-0817-N：合成完成后若仍 suspended（预热失败/未预热），等待期间再尝试 resume；
  // 仍被拒则抛错 → speak 捕获 → 按钮显示「播放失败」（不再无声假播放）
  if (ctx.state === 'suspended') {
    try { await ctx.resume(); } catch (e: any) { throw new Error('音频被浏览器阻止（需再次点击播放）'); }
  }
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return ctx.decodeAudioData(bytes.buffer).then(buffer => {
    if (activeSource) stopActiveSource();
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(ctx.destination);
    activeSource = src;
    return new Promise<void>(resolve => {
      src.onended = () => { activeSource = null; resolve(); };
      src.start();
    });
  });
}

// ── 声线解析 ──────────────────────────────────────────────

/**
 * 从本地角色卡（demo2 store）按名解析声线配置。
 * 覆盖：自由角色 / 各剧本新增角色 / 生成角色；localStorage 角色卡优先（前端显式传参，
 * 不依赖后端角色库）。找不到返回 undefined → 调用方回退 character 后端解析。
 */
function resolveLocalVoice(character: string): { mode?: string; voice_data?: string } | undefined {
  try {
    const st = useDemoStore.getState() as {
      freeRoles?: Array<{ name: string; voice_mode?: string; voice_data?: string }>;
      extraRoles?: Record<string, Array<{ name: string; voice_mode?: string; voice_data?: string }>>;
      genRoles?: Array<{ name: string; voice_mode?: string; voice_data?: string }>;
      generatedMurder?: { roles?: Array<{ name: string; voice_mode?: string; voice_data?: string }> } | null;
      generatedGeneral?: { roles?: Array<{ name: string; voice_mode?: string; voice_data?: string }> } | null;
    };
    const found: Array<{ name: string; voice_mode?: string; voice_data?: string }> = [];
    for (const list of [
      st?.freeRoles ?? [],
      st?.genRoles ?? [],
      st?.generatedMurder?.roles ?? [],
      st?.generatedGeneral?.roles ?? [],
      ...Object.values(st?.extraRoles ?? {}),
    ]) {
      if (Array.isArray(list)) found.push(...list);
    }
    const hit = found.find(r => r.name === character && (r.voice_mode || r.voice_data));
    if (!hit) return undefined;
    return { mode: hit.voice_mode || 'basic', voice_data: hit.voice_data || undefined };
  } catch {
    return undefined;
  }
}

/** 组装合成参数：显式声线 > 本地角色卡 > 后端角色库（character 名）。 */
function buildSpec(text: string, opts?: TtsSpeakOptions): { text: string; character?: string; mode?: string; voice_data?: string; voice?: string; tone?: string } {
  const spec: { text: string; character?: string; mode?: string; voice_data?: string; voice?: string; tone?: string } = { text };
  const local = opts?.character && !opts?.mode && !opts?.voice_data ? resolveLocalVoice(opts.character) : undefined;
  if (opts?.mode || opts?.voice_data || opts?.voice) {
    if (opts?.mode) spec.mode = opts.mode;
    if (opts?.voice_data) spec.voice_data = opts.voice_data;
    if (opts?.voice) spec.voice = opts.voice;
  } else if (local) {
    spec.mode = local.mode;
    if (local.voice_data) spec.voice_data = local.voice_data;
  } else if (opts?.character) {
    spec.character = opts.character;
  }
  if (opts?.tone) spec.tone = opts.tone;
  return spec;
}

// ── 对外 API ──────────────────────────────────────────────

/**
 * 合成并播放一条消息语音。同一 key 播放中再次调用 = 停止。
 * 状态流转：idle → loading → playing → idle；失败 → error（3s 后自动回 idle）。
 */
export async function speak(key: string, text: string, opts?: TtsSpeakOptions): Promise<void> {
  // P-0818-B：只朗读「语句」——去掉（动作/表情描述）【情绪标注】等括号内非语句内容；
  // 整条只有括号内容 → 空串不合成（避免把动作描述念出来）
  const trimmed = extractSpeechText(text);
  if (!trimmed) return;
  // P-0817-I：全局静音 / 该角色单独静音 → 不合成不播放（按钮已置 🔇 态，此处兜底拦截）
  if (isTtsMuted(opts?.character)) return;
  // 同 key 播放中再点 = 停止；不同 key 顶替（先停旧声源）
  if (status.key === key && status.state === 'playing') {
    stopActiveSource();
    currentSpeakCharacter = undefined;
    setStatus(key, 'idle');
    return;
  }
  stopActiveSource();
  currentSpeakCharacter = opts?.character;
  setStatus(key, 'loading');
  try {
    const spec = buildSpec(trimmed, opts);
    const res = await api.mimoTtsSynthesize(spec);
    if (!res?.audio_base64) throw new Error('合成响应缺少 audio_base64');
    // 等待期间被新的 speak 顶替（key 变了）→ 不再播放，避免旧声源压新声源
    if (status.key !== key) return;
    setStatus(key, 'playing');
    await playBase64(res.audio_base64);
    if (status.key === key) {
      currentSpeakCharacter = undefined;
      setStatus(key, 'idle');
    }
  } catch (e: any) {
    if (status.key !== key) return;
    setStatus(key, 'error', friendlyError(e));
    setTimeout(() => {
      if (status.key === key && status.state === 'error') setStatus(key, 'idle');
    }, 3000);
  }
}

/** 停止指定 key 的播放（无活动时静默）。 */
export function stop(key: string) {
  if (status.key !== key) return;
  currentSpeakCharacter = undefined;
  stopActiveSource();
  setStatus(key, 'idle');
}

/** 错误信息中文化（短文案，适合按钮 tooltip/角标）。 */
function friendlyError(e: any): string {
  const m = String(e?.message || e || '');
  if (/超时|abort/i.test(m)) return '合成超时，请重试';
  if (/503|未启用|未配置/i.test(m)) return 'TTS 未启用（后端未配置 MiMo）';
  if (/404|角色不存在/i.test(m)) return '角色声线未配置';
  if (/400|不能为空/i.test(m)) return '文本为空';
  if (/decode/i.test(m)) return '音频解码失败';
  return '合成失败';
}

// ── React Hook ───────────────────────────────────────────

/**
 * useMimoTts(key) — 消息级 TTS 播放状态（key 建议 = 消息唯一标识）。
 * 返回 { state, error, speak(text, opts?), stop(), muted }；
 * state 只对当前 key 有效（其他消息播放不影响本按钮显示）；
 * muted = 该角色是否被静音（全局静音或单独静音，静音变化经 emit 触发重渲染）。
 */
export function useMimoTts(key: string, character?: string) {
  const [st, setSt] = useState<TtsPlayStatus>(() => ({ ...getTtsStatus() }));
  useEffect(() => {
    const update = () => setSt({ ...getTtsStatus() });
    update();
    return subscribeTtsStatus(update);
  }, [key]);
  const mine = st.key === key;
  return {
    state: mine ? st.state : 'idle',
    error: mine ? st.error : undefined,
    muted: isTtsMuted(character),
    speak: (text: string, opts?: TtsSpeakOptions) => speak(key, text, opts),
    stop: () => stop(key),
  };
}
