export interface Character {
  name: string;
  persona: string;
  voice: string;
  background: string;
  /** P-0802-P1-demo：玩家身份绑定（改造方案 §3.2）；null/缺省 = 未绑定 */
  player_id?: string | null;
}

export interface Scene {
  scene_id: string;
  name: string;
  description: string;
  initial_agent_names: string[];
  /** P-0803-H：剧本/场景分类（general=一般模式 / werewolf=狼人杀模式；选择剧本时按分类展示与开局） */
  category?: 'general' | 'werewolf';
  /** P-0803-H：剧本绑定默认角色组（选择剧本时自动选中；前端按此分组展示角色卡） */
  default_roles?: string[];
  /** P-0803-H：剧本绑定默认地图（地图 JSON 契约 v1；剧本卡点开后地图预览渲染用） */
  default_map?: any | null;
}

export interface Track {
  id: string;
  agents: string[];
  agent_actions: Record<string, 'active' | 'silent' | 'offline'>;
  mode: 'merged' | 'weak' | 'isolated';
  color: string;
  label: string;
}

export interface TrackConfig {
  round: number;
  tracks: Track[];
  description: string;
}

export interface AppMessage {
  role: 'system' | 'agent' | 'user' | 'arbiter';
  name: string;
  content: string;
  timestamp: string;
  track_id: string;
  visible_to: string[];
  round_number: number;
  track_label?: string;
  track_mode?: string;
  character?: string;
  /** P-0802-M：流式增量草稿标记（agent_token 累积中，收到 agent_output 结算后置 false） */
  streaming?: boolean;
  /** P-0810-01（AI 生图）：消息附带的图片 URL（聊天气泡内渲染） */
  imageUrl?: string;
}

export interface Task {
  agent_name: string;
  task: string;
}

export interface AgentOutput {
  agent_name: string;
  content: string;
  track_id: string;
  track_label?: string;
  track_mode?: string;
}

// ── Werewolf / 狼人杀 ──────────────────────────────
export type WerewolfPhase = 'night' | 'day_discussion' | 'day_vote' | 'ended' | 'game_over';

export interface WerewolfPlayer {
  name: string;
  role: string;
  alive: boolean;
  roleRevealed: boolean;
}

// ── 演讲+广播合并地基（announcement SSE 事件）──────────────────
export interface Announcement {
  id: string;
  /** SYSTEM | EVENT | PLAYER | NPC */
  level: string;
  /** global | area | system */
  channel: string;
  speaker: string;
  text: string;
  x?: number;
  y?: number;
  radius?: number;
  /** speech=演讲（带空间范围）| announcement=公告 */
  mode: string;
  timestamp: number;
}
