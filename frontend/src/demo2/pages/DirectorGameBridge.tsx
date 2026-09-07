import { useEffect, useMemo, useRef, useState } from 'react';
import { directorApi, type DirectorState } from '../../api/director';
import { useAppStore } from '../../store/appStore';
import { GalGeneralView } from '../../gal/GalGeneralView';
import { useGalStore } from '../../gal/GalStore';
import { getGeneralScriptById } from '../mockData';
import { useDemoStore } from '../store';
import type { GeneralScript, RoleCard } from '../types';

type Phase = 'preflight-loading' | 'preflight' | 'launching' | 'ready' | 'error';

type ChatLine = { role: 'user' | 'assistant'; content: string };

export function DirectorGameBridge() {
  const selectCtx = useDemoStore(s => s.selectCtx);
  const gamePlayers = useDemoStore(s => s.gamePlayers);
  const playerRole = useDemoStore(s => s.playerRole);
  const playerDisplayName = useDemoStore(s => s.playerDisplayName);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);
  const backendGeneral = useDemoStore(s => s.backendGeneral);
  const freeRoles = useDemoStore(s => s.freeRoles);
  const genRoles = useDemoStore(s => s.genRoles);
  const extraRoles = useDemoStore(s => s.extraRoles);
  const back = useDemoStore(s => s.back);
  const go = useDemoStore(s => s.go);

  const [phase, setPhase] = useState<Phase>('preflight-loading');
  const [preflightId, setPreflightId] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [directorState, setDirectorState] = useState<DirectorState | null>(null);
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const startedRef = useRef(false);

  const script = useMemo(() => {
    if (!selectCtx.scriptId) return undefined;
    return getGeneralScriptById(selectCtx.scriptId)
      ?? (generatedGeneral?.id === selectCtx.scriptId ? generatedGeneral : undefined)
      ?? backendGeneral.find(x => x.id === selectCtx.scriptId);
  }, [selectCtx.scriptId, generatedGeneral, backendGeneral]);

  const roleByName = useMemo(() => {
    const map = new Map<string, RoleCard>();
    const put = (r: RoleCard) => map.set(r.name, r);
    script?.roles.forEach(put);
    (extraRoles[selectCtx.scriptId ?? ''] || []).forEach(put);
    genRoles.forEach(put);
    freeRoles.forEach(put);
    if (playerRole) put(playerRole);
    return map;
  }, [script, extraRoles, selectCtx.scriptId, genRoles, freeRoles, playerRole]);

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    const create = async () => {
      try {
        const g = script as GeneralScript | undefined;
        if (!g) throw new Error('场景数据缺失，请返回重新选择。');
        if (!playerRole) throw new Error('请先选择你要扮演的角色。');
        const aiNames = gamePlayers.filter(n => n !== playerRole.name);
        const names = [...new Set([playerRole.name, ...aiNames])];
        if (names.length === 0) throw new Error('至少需要一名角色。');
        const fullRole = (name: string) => {
          const r = roleByName.get(name);
          return {
            name,
            persona: r?.personality || '',
            personality: r?.personality || '',
            voice: '',
            talk_style: r?.talkStyle || '',
            background: r?.background || '',
            intro: r?.intro || '',
          };
        };
        const player = {
          ...fullRole(playerRole.name),
          player_id: directorApi.playerId(),
          display_name: playerDisplayName || playerRole.name,
        };
        const sceneDescription = [g.desc, g.background, g.opening].filter(Boolean).join('\n');
        const response = await directorApi.createPreflight({
          scene_id: g.title,
          scene_description: sceneDescription,
          player,
          characters: names.map(fullRole),
          relationships: g.relations || [],
          entry_order: names,
          onstage: names,
        });
        const state = response.state;
        if (!state?.preflight_id) throw new Error('主控开局响应缺少 preflight_id');
        setPreflightId(state.preflight_id);
        setDirectorState(state);
        if (response.reply) setLines([{ role: 'assistant', content: response.reply }]);
        setPhase('preflight');
      } catch (e: unknown) {
        setError(e instanceof Error ? e.message : '主控开局初始化失败');
        setPhase('error');
      }
    };
    void create();
  }, [script, gamePlayers, playerRole, playerDisplayName, roleByName]);

  const sendPreflight = async (message: string) => {
    const text = message.trim();
    if (!text || !preflightId || sending) return null;
    setSending(true);
    setLines(prev => [...prev, { role: 'user', content: text }]);
    setInput('');
    try {
      const response = await directorApi.preflightChat(preflightId, text);
      if (response.state) setDirectorState(response.state);
      if (response.reply) setLines(prev => [...prev, { role: 'assistant', content: response.reply! }]);
      return response;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '主控请求失败';
      setLines(prev => [...prev, { role: 'assistant', content: `请求失败：${msg}` }]);
      return null;
    } finally {
      setSending(false);
    }
  };

  const confirmAndStart = async () => {
    if (!preflightId || sending) return;
    try {
      let confirmed = directorState?.confirmed === true;
      if (!confirmed) {
        const response = await sendPreflight('确认进入场景');
        confirmed = response?.state?.confirmed === true;
      }
      if (!confirmed) return;
      setPhase('launching');
      const start = await directorApi.start(preflightId);
      const sid = String(start.session_id || '');
      if (!sid) throw new Error('正式开局响应缺少 session_id');
      setSessionId(sid);
      useGalStore.getState().setLiveGoals(start.goals);
      useAppStore.setState({
        sessionId: sid,
        mode: 'free',
        currentPlayer: playerRole?.name ?? '我',
        boundCharacterName: playerRole?.name ?? '',
      });
      await useAppStore.getState().loadState(sid);
      if (start.director_state) setDirectorState(start.director_state);
      setPhase('ready');
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '正式开局失败');
      setPhase('error');
    }
  };

  const title = script?.title || '一般模式 · 自由聊天';

  if (phase === 'ready') {
    return (
      <div style={{ position: 'relative' }}>
        <GalGeneralView
          sessionId={sessionId}
          playerName={playerRole?.name}
          displayName={playerDisplayName || playerRole?.name}
          onBack={back}
        />
        <RuntimeDirectorPanel sessionId={sessionId} initialState={directorState} />
      </div>
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <button className="btn2 btn2-ghost btn2-sm" onClick={back}>← 返回角色选择</button>
        <b style={{ fontSize: 15 }}>🎬 开局主控 · {title}</b>
        <button className="btn2 btn2-sm" style={{ marginLeft: 'auto' }} onClick={() => go('home')}>🏠 模式选择</button>
      </div>

      {(phase === 'preflight-loading' || phase === 'launching') && (
        <div className="card2" style={{ textAlign: 'center', padding: 40 }}>
          <div className="loading-dots" style={{ fontSize: 30 }}>🔄</div>
          <div style={{ marginTop: 12 }}>{phase === 'launching' ? '正在按已确认配置进入场景…' : '正在建立主控会话…'}</div>
        </div>
      )}

      {phase === 'error' && (
        <div className="card2" style={{ maxWidth: 620, margin: '40px auto', textAlign: 'center', padding: 28 }}>
          <div style={{ fontSize: 34 }}>⚠️</div>
          <b style={{ display: 'block', marginTop: 10 }}>主控初始化失败</b>
          <div className="hint" style={{ marginTop: 10 }}>{error}</div>
          <button className="btn2 btn2-ghost" style={{ marginTop: 16 }} onClick={back}>返回角色选择</button>
        </div>
      )}

      {phase === 'preflight' && (
        <div style={{ maxWidth: 900, margin: '0 auto', display: 'grid', gap: 12 }}>
          <div className="card2" style={{ padding: 16 }}>
            <b>开局权威状态</b>
            <DirectorStateStrip state={directorState} />
            <div className="hint" style={{ marginTop: 10 }}>
              这里确认的是服务器会真正执行的状态，不是剧情旁白。你可以先说“鲸鱼先离场，之后我叫她再进来”。
            </div>
          </div>

          <div className="card2" style={{ padding: 16 }}>
            <div style={{ maxHeight: 360, overflowY: 'auto', display: 'grid', gap: 8 }}>
              {lines.map((line, index) => (
                <div key={`${line.role}-${index}`} style={{
                  justifySelf: line.role === 'user' ? 'end' : 'start',
                  maxWidth: '82%',
                  padding: '9px 12px',
                  borderRadius: 10,
                  background: line.role === 'user' ? 'var(--color-primary-soft, rgba(95,120,255,.15))' : 'var(--color-surface-2, rgba(255,255,255,.06))',
                  whiteSpace: 'pre-wrap',
                  lineHeight: 1.65,
                }}>
                  {line.content}
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <input
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) void sendPreflight(input); }}
                placeholder="例如：先让兔子和我在场，鲸鱼先离场；之后再让鲸鱼进来。"
                disabled={sending}
                style={{ flex: 1, minWidth: 0 }}
              />
              <button className="btn2 btn2-ghost" disabled={sending || !input.trim()} onClick={() => void sendPreflight(input)}>
                {sending ? '处理中…' : '发送'}
              </button>
              <button className="btn2" disabled={sending} onClick={() => void confirmAndStart()}>
                确认并进入
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function DirectorStateStrip({ state }: { state: DirectorState | null }) {
  if (!state) return <div className="hint" style={{ marginTop: 8 }}>正在读取状态…</div>;
  const playerName = String(state.player?.name || '未指定');
  return (
    <div style={{ marginTop: 10, display: 'grid', gap: 6, fontSize: 13, lineHeight: 1.6 }}>
      <div><b>你扮演：</b>{playerName}</div>
      <div><b>当前在场：</b>{state.onstage?.length ? state.onstage.join('、') : '无'}</div>
      <div><b>当前离场：</b>{state.offstage?.length ? state.offstage.join('、') : '无'}</div>
      <div><b>出场顺序：</b>{state.entry_order?.length ? state.entry_order.join(' → ') : '未设定'}</div>
      {!!state.relationships?.length && <div><b>关系：</b>{state.relationships.join('；')}</div>}
      {!!state.scene_notes?.length && <div><b>场景补充：</b>{state.scene_notes.join('；')}</div>}
    </div>
  );
}

function RuntimeDirectorPanel({ sessionId, initialState }: { sessionId: string; initialState: DirectorState | null }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<DirectorState | null>(initialState);
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);

  useEffect(() => { setState(initialState); }, [initialState]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    setSending(true);
    setInput('');
    setLines(prev => [...prev, { role: 'user', content: text }]);
    try {
      const response = await directorApi.runtimeChat(sessionId, text);
      if (response.state) setState(response.state);
      if (response.reply) setLines(prev => [...prev, { role: 'assistant', content: response.reply! }]);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '主控请求失败';
      setLines(prev => [...prev, { role: 'assistant', content: `请求失败：${msg}` }]);
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <button
        className="btn2"
        onClick={() => setOpen(v => !v)}
        style={{ position: 'fixed', right: 18, bottom: 18, zIndex: 90, boxShadow: '0 8px 28px rgba(0,0,0,.28)' }}
      >
        🎬 主控
      </button>
      {open && (
        <div className="card2" style={{
          position: 'fixed', right: 18, bottom: 66, width: 'min(430px, calc(100vw - 36px))', maxHeight: '70vh',
          zIndex: 91, padding: 14, boxShadow: '0 18px 50px rgba(0,0,0,.38)', overflow: 'hidden',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <b>🎬 主控通道</b>
            <span className="hint">与角色聊天完全分离</span>
            <button className="btn2 btn2-ghost btn2-sm" style={{ marginLeft: 'auto' }} onClick={() => setOpen(false)}>✕</button>
          </div>
          <DirectorStateStrip state={state} />
          <div style={{ maxHeight: 220, overflowY: 'auto', display: 'grid', gap: 6, marginTop: 10 }}>
            {lines.map((line, index) => (
              <div key={`${line.role}-${index}`} style={{ fontSize: 13, whiteSpace: 'pre-wrap', lineHeight: 1.55 }}>
                <b>{line.role === 'user' ? '你' : '主控'}：</b>{line.content}
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 6, marginTop: 10 }}>
            <input
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') void send(); }}
              placeholder="例如：让鲸鱼离场 / 把鲸鱼拉进来 / 现在谁在场？"
              disabled={sending}
              style={{ flex: 1, minWidth: 0 }}
            />
            <button className="btn2 btn2-sm" disabled={sending || !input.trim()} onClick={() => void send()}>
              {sending ? '…' : '发送'}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
