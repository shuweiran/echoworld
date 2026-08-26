/**
 * characterVoiceSync.ts — 角色声线 → 后端角色库同步（P-0817-K）
 *
 * 背景：自由角色/各剧本新增角色只存 localStorage（demo2 store 角色卡），后端角色库（H2）没有对应行；
 * 直接 PUT /api/characters/{name} 对不存在的角色返回 404 → 声线配置无法持久化到后端。
 *
 * 策略（任务书「先 POST 创建再 PUT 更新」）：
 *  ① 先 PUT（角色已在后端库 → 直接更新 voice_mode/voice_data）；
 *  ② PUT 404（仅本地角色卡，不在后端库）→ POST /api/characters 创建带声线的角色
 *     （persona 摘要来自角色卡字段；显式 player_id: null 防止 client 层自动绑定为「玩家本人角色」的副作用）；
 *  ③ 都失败 → 'failed'（本地 localStorage 已生效，后端同步降级，调用方提示）。
 *
 * 清除语义：voice_mode/voice_data 传空串（''）——后端 PUT 忽略 null 值、nvl 将空串归一为 null → 正确清除；
 * 清除场景建议 createIfMissing=false（后端无此角色时不创建空角色，避免污染角色库）。
 */
import { api } from '../api/client';
import type { RoleCard } from '../demo2/types';

export type VoiceSyncResult = 'updated' | 'created' | 'failed';

export interface VoiceSyncOptions {
  /** PUT 404 时是否 POST 创建（缺省 true；清除声线场景传 false 不创建空角色） */
  createIfMissing?: boolean;
}

/**
 * 把角色声线同步到后端角色库。永不抛异常（内部全部捕获），返回结果枚举。
 *
 * @param name      角色名（后端唯一键；改名场景不适用，调用方先判断）
 * @param voiceMode 声线模式：basic/clone/design；空串 = 未配置（清除）
 * @param voiceData 声线数据：内置音色名 / 克隆参考音频 Data URL / 音色描述；空串 = 无
 * @param role      角色卡（POST 创建时拼 persona/voice/background 摘要，可缺省）
 */
export async function syncCharacterVoice(
  name: string,
  voiceMode: string,
  voiceData: string,
  role?: Pick<RoleCard, 'intro' | 'personality' | 'talkStyle' | 'background' | 'tts'>,
  opts?: VoiceSyncOptions,
): Promise<VoiceSyncResult> {
  // 空串=清除（PUT 端点 null 值不写入，nvl('') → null 落库清除；此处恒发字符串保证键存在）
  const payload = { voice_mode: voiceMode, voice_data: voiceData };
  try {
    await api.updateCharacter(name, payload);
    return 'updated';
  } catch {
    // PUT 失败（典型：404 角色不在后端库）
    if (opts?.createIfMissing === false) return 'failed';
    try {
      const persona = [
        role?.intro || '',
        role?.personality ? `性格：${role.personality}` : '',
        role?.talkStyle ? `说话风格：${role.talkStyle}` : '',
      ].filter(Boolean).join(' ').trim();
      await api.createCharacter({
        name,
        // 显式 null 覆盖 client 层「无绑定角色时自动携带 player_id」逻辑——声线同步不应改变玩家角色绑定
        player_id: null,
        persona,
        voice: role?.tts?.voice || '',
        background: role?.background || '',
        ...payload,
      });
      return 'created';
    } catch {
      return 'failed';
    }
  }
}
