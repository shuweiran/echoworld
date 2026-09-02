import { useEffect, useRef } from 'react';
import { api, API_ORIGIN } from './client';

type SSEHandler = (eventType: string, data: any) => void;

/** P-0810-06：连接状态回调（Gal 直播连接状态显示用；旧调用方不传零影响） */
export type SSEStatus = 'connecting' | 'open' | 'reconnecting';

export interface SSEIdentity {
  player?: string;
  playerKey?: string;
}

/**
 * SSE 连接钩子。
 * @param onEvent 事件处理器
 * @param sessionId 可选会话标识（P-0802-I）：带 session_id 的连接只接收该对局的定向事件
 *   服务端按 session_id 隔离所有对局事件；为空时仅订阅无会话的公共事件。sessionId 变化时自动重连。
 * @param onStatus P-0810-06 可选连接状态回调（connecting/open/reconnecting，重连退避期间循环触发）
 * @param identity 私密订阅身份；player/playerKey 必须成对出现，缺失时仅接收该会话公开事件
 *
 * <p>P-0803-P（断线补发前端接线，原 P3）：SSE 断线自动重连（onerror → 指数退避重连）成功后，
 * 调 {@code GET /api/announcements/recent?since=<lastTs>} 补拉断线期间错过的公告/演讲，
 * 逐条以 {@code announcement} 事件重放（补发语义与 AnnouncementService.recentSince 对齐）。
 */
export function useSSE(
  onEvent: SSEHandler,
  sessionId?: string,
  onStatus?: (s: SSEStatus) => void,
  identity?: SSEIdentity,
) {
  const esRef = useRef<EventSource | null>(null);
  const reconnectRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onEventRef = useRef(onEvent);
  const onStatusRef = useRef(onStatus);
  /** 已消费的公告最新时间戳（用于断线补拉 since 游标）。 */
  const lastAnnouncementTsRef = useRef(0);

  useEffect(() => { onEventRef.current = onEvent; }, [onEvent]);
  useEffect(() => { onStatusRef.current = onStatus; }, [onStatus]);

  useEffect(() => {
    let disposed = false;

    /** 断线补发：拉取最近公告并逐条以 announcement 事件重放。失败静默（等下次重连）。 */
    const pullRecentAnnouncements = async () => {
      try {
        const res: any = await api.announcementRecent(lastAnnouncementTsRef.current);
        if (disposed) return;
        const list: any[] = res?.announcements ?? [];
        for (const item of list) {
          onEventRef.current('announcement', item);
          const ts = item?.timestamp;
          if (typeof ts === 'number' && ts > lastAnnouncementTsRef.current) {
            lastAnnouncementTsRef.current = ts;
          }
        }
      } catch {
        // 补拉失败静默，等下次重连再试。
      }
    };

    const connect = () => {
      if (disposed) return;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      esRef.current?.close();
      onStatusRef.current?.('connecting');
      const params = new URLSearchParams();
      if (sessionId) params.set('session_id', sessionId);
      // 私密事件必须同时绑定玩家身份与 roleKey；缺任一项时保持公开会话语义。
      if (sessionId && identity?.player && identity.playerKey) {
        params.set('player', identity.player);
        params.set('player_key', identity.playerKey);
      }
      const query = params.toString();
      const es = new EventSource(`${API_ORIGIN}${query ? `/api/events?${query}` : '/api/events'}`);
      esRef.current = es;

      es.onopen = () => {
        if (disposed || esRef.current !== es) return;
        onStatusRef.current?.('open');
        const wasReconnect = reconnectRef.current > 0;
        reconnectRef.current = 0;
        if (wasReconnect) void pullRecentAnnouncements();
      };
      es.onerror = () => {
        if (disposed || esRef.current !== es) return;
        es.close();
        onStatusRef.current?.('reconnecting');
        reconnectRef.current++;
        const delay = Math.min(1000 * Math.pow(2, reconnectRef.current), 30000);
        reconnectTimerRef.current = setTimeout(connect, delay);
      };

      const events = ['round_start', 'arbiter_task', 'agent_output', 'agent_silent',
        'arbiter_integrate', 'round_complete', 'compression', 'user_input',
        'auto_complete', 'stopped', 'error', 'saved',
        // P-0802-M：LLM 流式增量（逐字渲染）
        'agent_token',
        'werewolf_wait_human', 'werewolf_phase', 'werewolf_player_update',
        'werewolf_my_role', 'werewolf_player_eliminated', 'werewolf_witch_info',
        'werewolf_game_over', 'werewolf_night_result', 'werewolf_vote_update',
        'werewolf_speech', 'werewolf_status', 'agent_added', 'agent_removed',
        'script_phase', 'script_status', 'script_reveal',
        'script_private',
        // P-0815-B：剧本杀讨论实时发言（SSEController.broadcastScriptSpeech 会话定向）+ 完整剧本就绪
        'script_speech', 'script_ready',
        // P-0816-H（UI 重设计阶段一 §3.3）：投票进度 / 目标 HUD 实时推送（会话定向；前端 3s 轮询兜底）
        'script_vote_progress', 'script_goal',
        'script_locks', 'script_press', 'script_present',
        'track_created', 'track_closed', 'phase_changed',
        'announcement',
        // P-0810-03：AI 生图事件（后端推送时全局通道可消费；Gal Demo 经 aiImage.ts 独立订阅）
        'ai_image_ready', 'ai_image_error',
        'tts_start', 'tts_chunk', 'tts_end', 'tts_error'];
      events.forEach(evt => {
        es.addEventListener(evt, (e: MessageEvent) => {
          try {
            const data = JSON.parse(e.data);
            if (evt === 'announcement' && data && typeof data.timestamp === 'number'
                && data.timestamp > lastAnnouncementTsRef.current) {
              lastAnnouncementTsRef.current = data.timestamp;
            }
            onEventRef.current(evt, data);
          } catch {}
        });
      });
    };
    connect();
    return () => {
      disposed = true;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      esRef.current?.close();
      esRef.current = null;
    };
  }, [sessionId, identity?.player, identity?.playerKey]);
}
