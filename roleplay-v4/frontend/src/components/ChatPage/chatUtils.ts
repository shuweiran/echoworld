/**
 * chatUtils.ts — 对局 UI 公共工具（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：颜色/轨道/狼人杀阶段/剧本杀阶段/语音识别等纯函数与常量，供
 * ChatPage 各子组件共享。零 React 依赖（startVoice 除外，独立使用）。
 */

/* ── 角色头像配色 ─────────────────────────── */
const COLORS = ['#0ea5e9', '#22c55e', '#f59e0b', '#7c4dff', '#4fd1c5', '#ff6ad5', '#ef4444', '#8b7bff'];

export function colorFor(name = '') {
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return COLORS[hash % COLORS.length];
}

/* ── 角色渐变头像（P-0816-M 对局页按原型重构） ─────────── */
/**
 * 角色头像渐变背景（对齐原型 investigation/discussion/vote 三页 av-* 渐变头像）：
 * 已知角色名（原型五主角）→ 原型固定渐变；其余角色 → colorFor 主色派生双色渐变。
 * 返回 CSS background 值（'linear-gradient(...)'），供左栏/右栏/投票卡头像使用。
 */
const ROLE_GRADIENTS: Record<string, string> = {
  林深: 'linear-gradient(135deg,#38bdf8,#0284c7)',
  苏晚: 'linear-gradient(135deg,#f472b6,#db2777)',
  陈默: 'linear-gradient(135deg,#a78bfa,#7c3aed)',
  顾言: 'linear-gradient(135deg,#2dd4bf,#0d9488)',
  阿岚: 'linear-gradient(135deg,#fb923c,#ea580c)',
  林深青: 'linear-gradient(135deg,#38bdf8,#0284c7)',
  苏晚粉: 'linear-gradient(135deg,#f472b6,#db2777)',
  陈默紫: 'linear-gradient(135deg,#a78bfa,#7c3aed)',
  顾言蓝绿: 'linear-gradient(135deg,#2dd4bf,#0d9488)',
  阿岚橙: 'linear-gradient(135deg,#fb923c,#ea580c)',
};
export function avatarGradientFor(name = ''): string {
  const n = String(name || '');
  if (ROLE_GRADIENTS[n]) return ROLE_GRADIENTS[n];
  const base = colorFor(n);
  return `linear-gradient(135deg, ${base}, ${base}cc)`;
}

/* ── 角色铭牌/气泡/名字色（P-0816-U：对齐原型 vn-tag/b-* 气泡的固定角色色） ── */
/**
 * 角色单色（讨论铭牌/气泡左边框/嫌疑人名字）：原型五主角固定色
 * （林#0ea5e9 / 苏#ec4899 / 陈#8b5cf6 / 顾#14b8a6 / 阿#f97316，对齐 discussion.html b-* 气泡），
 * 其余角色 → colorFor 哈希色回退。
 */
const ROLE_COLORS: Record<string, string> = {
  林深: '#0ea5e9', 苏晚: '#ec4899', 陈默: '#8b5cf6', 顾言: '#14b8a6', 阿岚: '#f97316',
  林深青: '#0ea5e9', 苏晚粉: '#ec4899', 陈默紫: '#8b5cf6', 顾言蓝绿: '#14b8a6', 阿岚橙: '#f97316',
};
export function roleColorFor(name = ''): string {
  const n = String(name || '');
  if (ROLE_COLORS[n]) return ROLE_COLORS[n];
  return colorFor(n);
}

/* ── 剧本杀阶段主题（P-0817-E 阶段D：已抽至共享层 components/ui/PhaseBadge.tsx，此处 re-export 兼容） ─── */
/**
 * SCRIPT_PHASE_LABEL / SCRIPT_PHASE_EMOJI / scriptPhaseThemeClass
 * 原实现已原样搬至 components/ui/PhaseBadge.tsx（只搬代码 + 改 import，零视觉变化）；
 * 本文件 re-export 兼容旧消费点（ScriptStatePanel/ChatMessageFlow/GameAtmosphereBanner/
 * ScriptGameInfoBar/ScriptProtoTopbar/ChatPage）；新消费点请直接引共享层路径。
 */
export { SCRIPT_PHASE_LABEL, SCRIPT_PHASE_EMOJI, scriptPhaseThemeClass } from '../ui/PhaseBadge';

/* ── 轨道模式徽标文案 ─────────────────────── */
export function trackModeName(mode?: string) {
  if (mode === 'weak') return '弱链';
  if (mode === 'isolated') return '隔离';
  return '强链';
}

/* ── 狼人杀 helpers ───────────────────────── */
/** Normalize backend phase names to frontend-compatible keys */
export function normalizePhase(p: string): string {
  if (p === 'discussion') return 'day_discussion';
  if (p === 'voting') return 'day_vote';
  return p;
}

export const PHASE_EMOJI: Record<string, string> = {
  night: '🌙',
  day_discussion: '☀️',
  day_vote: '🗳️',
  ended: '🏁',
  game_over: '🏁',
};
export const PHASE_LABEL: Record<string, string> = {
  night: '夜',
  day_discussion: '白天讨论',
  day_vote: '投票',
  ended: '已结束',
  game_over: '游戏结束',
};
export const ROLE_EMOJI: Record<string, string> = {
  '狼人': '🐺',
  '预言家': '🔮',
  '女巫': '🧪',
  '猎人': '🏹',
  '平民': '😴',
  '守卫': '🛡️',
  '白痴': '🤡',
  '长老': '👴',
  '骑士': '⚔️',
};

export function getPhaseGuide(phase: string, role: string): string {
  const p = normalizePhase(phase);
  if (p === 'night') {
    const guides: Record<string, string> = {
      '狼人': '🐺 夜间时段，请与队友讨论并确定击杀目标',
      '预言家': '🔮 夜间时段，请选择要查验的目标玩家',
      '女巫': '🧪 夜间时段，请选择是否使用解药或毒药',
      '猎人': '🏹 夜间时段，请闭眼等待天亮',
      '平民': '😴 夜间时段，请闭眼等待天亮',
      '守卫': '🛡️ 夜间时段，请选择要守护的目标玩家',
    };
    return guides[role] || '🌙 夜间时段，请闭眼等待';
  }
  if (p === 'day_discussion') {
    return '🗣️ 白天讨论时间，请发表你的看法和推理';
  }
  if (p === 'day_vote') {
    return '🗳️ 投票时间，请输入「我投XXX」进行投票';
  }
  return '';
}

export function getLoadingText(phase: string, role: string): string {
  const p = normalizePhase(phase);
  if (p === 'night') {
    const texts: Record<string, string> = {
      '狼人': '🐺 狼人在行动..',
      '预言家': '🔮 预言家在查验...',
      '女巫': '🧪 女巫在思考..',
    };
    return texts[role] || '🌙 夜间阶段进行中..';
  }
  if (p === 'day_discussion') return '☀️ 白天讨论中..';
  if (p === 'day_vote') return '🗳️ 投票统计中..';
  return '⏳ 运行中...';
}

export function phaseClassName(phase: string): string {
  const p = normalizePhase(phase);
  if (p === 'night') return 'phase-night';
  if (p === 'day_discussion') return 'phase-day';
  if (p === 'day_vote') return 'phase-vote';
  return '';
}

/* ── 剧本杀 helpers ───────────────────────── */
/** 剧本杀各阶段操作引导（P-0815-F 批2 方向3：引导共识化——单点文案，
 *  GameAtmosphereBanner guide / 阶段横幅共用，杜绝多处各写一份漂移）。
 *  对齐 P-0816-M 对局页主区实际操作位置（搜证=主区地点卡片 / 投票=主区嫌疑人卡）。 */
export const SCRIPT_GUIDES: Record<string, string> = {
  setup: '生成完整剧本后开始对局（完成后自动进入搜证阶段）',
  investigation: '点击地点卡片搜证（消耗行动点），收集线索后可进入讨论',
  discussion: '在下方输入框发言，交流线索、互相试探',
  vote: '点击嫌疑人卡片选择目标投票（超时未投将按弃票处理）',
  reveal: '真相已揭晓，查看揭晓结果，确认后结束对局',
  ended: '对局已结束，可再来一局或回到剧本选择',
};

/* ── 语音识别（本地 Whisper 经后端转写）────── */
let mediaRecorder: MediaRecorder | null = null;
let audioChunks: Blob[] = [];

export async function startVoice(setText: (t: string) => void) {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const micBtn = document.querySelector('.mic-btn') as HTMLElement;
    if (micBtn) { micBtn.style.background = '#ff5252'; micBtn.textContent = '\u23f3'; }
    audioChunks = [];
    mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' });
    mediaRecorder.ondataavailable = (e) => audioChunks.push(e.data);
    mediaRecorder.onstop = async () => {
      if (micBtn) { micBtn.style.background = ''; micBtn.textContent = '\ud83c\udfa4'; }
      stream.getTracks().forEach(t => t.stop());
      const audioBlob = new Blob(audioChunks, { type: 'audio/webm' });
      const formData = new FormData();
      formData.append('audio', audioBlob, 'voice.webm');
      try {
        const res = await fetch('/api/voice/transcribe', { method: 'POST', body: formData });
        const data = await res.json();
        if (data.text) setText(data.text);
      } catch (err) {
        console.error('Transcription error:', err);
        alert('\u8bed\u97f3\u8bc6\u522b\u5931\u8d25');
      }
    };
    mediaRecorder.start();
    setTimeout(() => {
      if (mediaRecorder && mediaRecorder.state === 'recording') {
        mediaRecorder.stop();
      }
    }, 10000);
  } catch (err) {
    console.error('Mic error:', err);
    alert('\u8bf7\u5141\u8bb8\u9ea6\u514b\u98ce\u6743\u9650');
  }
}
