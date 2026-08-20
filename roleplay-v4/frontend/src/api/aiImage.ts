/**
 * aiImage.ts — AI 立绘 API 封装 + SSE 事件订阅（P-0810-03 前端联调）
 *
 * 对 P-0810-01 后端生图服务（本地 ComfyUI + Pony V6 XL）的最小前端封装：
 *   1) 4 个 REST API（listStatus / listCharacterImages / registerCharacter / triggerGenerate）
 *   2) SSE 事件订阅 helper（ai_image_ready / ai_image_error）
 *
 * 说明：
 *   - REST 请求复用 api/client.ts 的统一 request 管线（X-Request-Id / 超时 / 错误规范化），
 *     本模块只做类型化薄封装 + 语义化命名（供 GalStore 等消费方使用）。
 *   - SSE helper 独立于 useSSE 钩子（可在非 React 环境如 Zustand store 中使用），
 *     打开 /api/events 只监听生图两个事件，返回取消订阅函数。
 *   - ⚠️ 事件 payload 契约（按 P-0810-01 设计约定）：ai_image_ready 含
 *     { characterId, frame/type, url }；ai_image_error 含 { characterId, error }。
 *     解析做防御式容错；后端未推送时订阅不报错（轮询兜底见 GalStore）。
 */
import { api } from './client';

// ── 类型 ───────────────────────────────────────────────────────

/** GET /api/ai-image/status 单角色条目 */
export interface AiImageCharacterStatus {
  id: string;
  name: string;
  appearance: string;
  style: string;
  /** frame → URL（avatar / happy / angry / sad / surprised / embarrassed / neutral） */
  images: Record<string, string>;
  task?: {
    taskId?: string;
    status: string; // idle | running | done | failed
    progress?: string;
    error?: string;
  };
}

/** GET /api/ai-image/status 全量响应 */
export interface AiImageStatus {
  ok: boolean;
  lora?: string;
  characters: AiImageCharacterStatus[];
}

/** GET /api/ai-image/character/{id}/images 响应 */
export interface AiImageCharacterImages {
  characterId: string;
  name: string;
  avatar?: string | null;
  expressions: Record<string, string>;
  images: Record<string, string>;
}

/** POST /api/ai-image/generate 响应 */
export interface AiImageGenerateResult {
  ok: boolean;
  taskId?: string;
  characterId?: string;
  status?: string;
  progress?: string;
}

/** SSE ai_image_ready payload（含 characterId/类型/URL，容错解析） */
export interface AiImageReadyPayload {
  characterId?: string;
  frame?: string;
  type?: string;
  url?: string;
  [k: string]: unknown;
}

/** SSE ai_image_error payload */
export interface AiImageErrorPayload {
  characterId?: string;
  error?: string;
  [k: string]: unknown;
}

// ── 4 个 REST API（薄封装）───────────────────────────────────

/** 全量状态（注册表 + 任务 + 已生成图 URL）——立绘面板数据源 */
export function listStatus(): Promise<AiImageStatus> {
  return api.aiImageStatus();
}

/** 某角色已生成图（avatar + 表情集） */
export function listCharacterImages(id: string): Promise<AiImageCharacterImages> {
  return api.aiImageCharacterImages(id);
}

/** 注册/更新角色（id/name/appearance 外貌描述/style 风格描述——风格必须固定保证同角色一致） */
export function registerCharacter(data: {
  id: string;
  name: string;
  appearance: string;
  style: string;
}): Promise<{ ok: boolean; character?: unknown }> {
  return api.aiImageRegisterCharacter(data);
}

/** 触发某角色生成（头像 1 + 表情 6，异步；已有运行中任务直接返回该任务） */
export function triggerGenerate(characterId: string): Promise<AiImageGenerateResult> {
  return api.aiImageGenerate(characterId);
}

// ── SSE 事件订阅 helper ──────────────────────────────────────

/**
 * 订阅生图 SSE 事件（ai_image_ready / ai_image_error）。
 *
 * 独立 EventSource（不经 useSSE 钩子），可在组件外（Zustand store / 工具函数）调用；
 * 返回取消订阅函数（组件卸载 / store dispose 时调用）。
 * 后端未推送事件时静默等待（不报错）——轮询兜底由消费方负责。
 */
export function subscribeAiImageEvents(handlers: {
  onReady?: (payload: AiImageReadyPayload) => void;
  onError?: (payload: AiImageErrorPayload) => void;
}): () => void {
  let es: EventSource | null = null;
  try {
    es = new EventSource('/api/events');
  } catch {
    // 极老环境无 EventSource：返回空操作，轮询兜底
    return () => {};
  }
  const onReady = (e: MessageEvent) => {
    try {
      const data = JSON.parse(e.data) as AiImageReadyPayload;
      handlers.onReady?.(data);
    } catch { /* 非 JSON 数据忽略 */ }
  };
  const onError = (e: MessageEvent) => {
    try {
      const data = JSON.parse(e.data) as AiImageErrorPayload;
      handlers.onError?.(data);
    } catch { /* 忽略 */ }
  };
  es.addEventListener('ai_image_ready', onReady);
  es.addEventListener('ai_image_error', onError);
  return () => {
    es?.removeEventListener('ai_image_ready', onReady);
    es?.removeEventListener('ai_image_error', onError);
    es?.close();
    es = null;
  };
}
