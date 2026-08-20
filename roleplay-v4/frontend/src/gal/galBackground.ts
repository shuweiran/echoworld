/**
 * galBackground.ts — 场景背景槽位（P-0810-15）
 *
 * 一般模式 Gal 视图的背景层：
 *  - 起局后按 scene 调后端背景端点（P-0810-14：POST /api/ai-image/scene-background
 *    {scene} → {url}）→ 显示为背景；
 *  - 后端未就绪 / 生成中 / 失败 → 用「场景色渐变」占位（确定性：同场景同渐变，零依赖零阻塞）；
 *  - 生成完成替换（成功 url 直接显示，不做轮询——契约是同步返回 url）。
 *
 * 防御式设计：请求失败静默返回 null，调用方保持渐变占位，绝不让背景阻塞 UI。
 * 去重：同 scene 只请求一次（内存缓存 + inflight 合并），轮询元信息重复触发时零重复请求。
 */
import { api } from '../api/client';
import { hashHue } from './galDemoData';

/** 背景状态：placeholder=渐变占位（未就绪/失败）｜loading=后端请求中（仍显示渐变）｜ready=真实背景 */
export type BgState = 'placeholder' | 'loading' | 'ready';

/** scene → 已成功的背景 url（同 scene 只请求一次） */
const bgCache = new Map<string, string>();
/** scene → in-flight promise（并发去重） */
const inflight = new Map<string, Promise<string | null>>();

/**
 * 场景名 → 确定性场景色渐变（占位/加载中/失败兜底共用）。
 * 用 hashHue(scene) 派生色相 → 同场景跨会话稳定同色，视觉可识别。
 */
export function sceneGradient(scene: string): string {
  const h = hashHue(scene || '一般模式');
  const h2 = (h + 46) % 360;
  const h3 = (h + 96) % 360;
  return [
    `radial-gradient(820px 520px at 78% 12%, hsla(${h2}, 60%, 34%, 0.55), transparent 62%)`,
    `radial-gradient(700px 480px at 8% 92%, hsla(${h3}, 55%, 26%, 0.5), transparent 60%)`,
    `linear-gradient(160deg, hsl(${h} 32% 12%) 0%, hsl(${h2} 26% 8%) 48%, hsl(${h3} 22% 7%) 100%)`,
  ].join(', ');
}

/**
 * 拉取场景背景。成功 → url；后端未就绪（404/500/超时）/ 无 url / 空 scene → null。
 * 内部缓存 + inflight 合并：同 scene 并发/重复调用只发一次请求。
 */
export async function fetchSceneBackground(scene: string): Promise<string | null> {
  const key = (scene || '').trim();
  if (!key) return null;
  if (bgCache.has(key)) return bgCache.get(key)!;
  const existing = inflight.get(key);
  if (existing) return existing;

  const p = (async (): Promise<string | null> => {
    try {
      const res: any = await api.sceneBackground(key);
      const url = res?.url || res?.image_url || (typeof res === 'string' ? res : '');
      if (url) {
        bgCache.set(key, url);
        return url;
      }
      return null;
    } catch {
      // 后端未就绪 / 网络错误：静默降级占位，不抛不阻塞
      return null;
    }
  })();
  inflight.set(key, p);
  try {
    return await p;
  } finally {
    inflight.delete(key);
  }
}
