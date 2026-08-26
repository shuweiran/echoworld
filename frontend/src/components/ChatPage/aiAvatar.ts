/**
 * aiAvatar.ts — P-0810-01（本地 ComfyUI + Pony V6 XL 角色表情集预生成）
 *
 * 聊天气泡旁角色头像的懒加载映射：GET /api/ai-image/status 拉取注册表 +
 * 已生成头像 URL（/ai-images/{characterId}/avatar.png），按角色名缓存到模块级。
 * 失败静默回退（无头像时 MessageView 维持原字母头像，零破坏）。
 */
import { api } from '../../api/client';

let cache: Record<string, string> | null = null;
let inflight: Promise<Record<string, string>> | null = null;

/** 角色名 → 头像 URL 映射（只拉一次；后续直接命中缓存）。 */
export function fetchAvatarMap(): Promise<Record<string, string>> {
  if (cache) return Promise.resolve(cache);
  if (!inflight) {
    inflight = api.aiImageStatus()
      .then((res: any) => {
        const map: Record<string, string> = {};
        const list = Array.isArray(res?.characters) ? res.characters : [];
        for (const c of list) {
          const avatar = c?.images?.avatar;
          if (typeof avatar === 'string' && avatar) map[c.name] = avatar;
        }
        cache = map;
        return map;
      })
      .catch(() => {
        cache = {};
        return cache;
      });
  }
  return inflight;
}

/** 取某角色名的头像 URL（无则 undefined）。 */
export function avatarUrlFor(name: string, map: Record<string, string>): string | undefined {
  return map[name];
}
