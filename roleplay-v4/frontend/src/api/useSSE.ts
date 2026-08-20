import { useEffect, useRef, useCallback } from 'react';
import { api } from './client';

type SSEHandler = (eventType: string, data: any) => void;

/** P-0810-06：连接状态回调（Gal 直播连接状态显示用；旧调用方不传零影响） */
export type SSEStatus = 'connecting' | 'open' | 'reconnecting';

/**
 * SSE 连接钩子。
 * @param onEvent 事件处理器
 * @param sessionId 可选会话标识（P-0802-I）：带 session_id 的连接只接收该对局的定向事件
 *   （werewolf_* 经 SSEController.broadcastToSession 定向推送），同时仍接收全部全局广播；
 *   为空时与旧版一致（全局广播全覆盖）。sessionId 变化时自动重连。
 * @param onStatus P-0810-06 可选连接状态回调（connecting/open/reconnecting，重连退避期间循环触发）
 *
 * <p>P-0803-P（断线补发前端接线，原 P3）：SSE 断线自动重连（onerror → 指数退避重连）成功后，
 * 调 {@code GET /api/announcements/recent?since=<lastTs>} 补拉断线期间错过的公告/演讲，
 * 逐条以 {@code announcement} 事件重放（补发语义与 AnnouncementService.recentSince 对齐）。
 */
export function useSSE(onEvent: SSEHandler, sessionId?: string, onStatus?: (s: SSEStatus) => void) {
  const esRef = useRef<EventSource | null>(null);
  const reconnectRef = useRef(0);
  /** 已消费的公告最新时间戳（用于断线补拉 since 游标）。 */
  const lastAnnouncementTsRef = useRef(0);

  /** 断线补发：拉取最近公告并逐条以 announcement 事件重放。失败静默（等下次重连）。 */
  const pullRecentAnnouncements = useCallback(async () => {
    try {
      const res: any = await api.announcementRecent(lastAnnouncementTsRef.current);
      const list: any[] = res?.announcements ?? [];
      for (const item of list) {
        onEvent('announcement', item);
        const ts = item?.timestamp;
        if (typeof ts === 'number' && ts > lastAnnouncementTsRef.current) {
          lastAnnouncementTsRef.current = ts;
        }
      }
    } catch {
      // 补拉失败静默，等下次重连再试。
    }
  }, [onEvent]);

  const connect = useCallback(() => {
    if (esRef.current) esRef.current.close();
    onStatus?.('connecting');
    const url = sessionId
      ? `/api/events?session_id=${encodeURIComponent(sessionId)}`
      : '/api/events';
    const es = new EventSource(url);
    esRef.current = es;

    es.onopen = () => {
      onStatus?.('open');
      const wasReconnect = reconnectRef.current > 0;
      reconnectRef.current = 0;
      if (wasReconnect) pullRecentAnnouncements();
    };
    es.onerror = () => {
      es.close();
      onStatus?.('reconnecting');
      reconnectRef.current++;
      const delay = Math.min(1000 * Math.pow(2, reconnectRef.current), 30000);
      setTimeout(connect, delay);
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
          onEvent(evt, data);
        } catch {}
      });
    });
  }, [onEvent, sessionId, pullRecentAnnouncements, onStatus]);

  useEffect(() => {
    connect();
    return () => { esRef.current?.close(); };
  }, [connect]);
}
