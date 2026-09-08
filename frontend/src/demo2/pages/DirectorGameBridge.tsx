import { useEffect, useMemo, useRef, useState } from 'react';
import { directorApi, type DirectorState, type DirectorStoryPlan } from '../../api/director';
import { useAppStore } from '../../store/appStore';
import { GalGeneralView } from '../../gal/GalGeneralView';
import { useGalStore } from '../../gal/GalStore';
import { getGeneralScriptById } from '../mockData';
import { useDemoStore } from '../store';
import type { GeneralScript, RoleCard } from '../types';

type Phase = 'preflight-loading' | 'preflight' | 'launching' | 'ready' | 'error';
type ChatLine = { role: 'user' | 'assistant'; content: string };

const AUTO_PLAN_PROMPT = '请根据当前场景和角色，先给我编排一套完整开局方案：确定故事前提与基调、开场局势、人物关系、每个人的场景身份与目标，需要的话安排角色秘密，再给出2到4个剧情拍点和开场触发事件。先完成导演台设定，不要直接开始正文剧情。';

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
  const [storyPlan, setStoryPlan] = useState<DirectorStoryPlan | null>(null);
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
        const goals = Object.fromEntries(names
          .map(name => [name, roleByName.get(name)?.motive || ''] as const)
          .filter(([, value]) => !!value));
        const secrets = Object.fromEntries(names
          .map(name => [name, roleByName.get(name)?.secret || ''] as const)
          .filter(([, value]) => !!value));
        const response = await directorApi.createPreflight({
          scene_id: g.title,
          scene_description: sceneDescription,
          player,
          characters: names.map(fullRole),
          relationships: g.relations || [],
          entry_order: names,
          onstage: names,
          story_premise: g.desc || '',
          story_tone: [g.theme, ...(g.tags || [])].filter(Boolean).join(' / '),
          opening_situation: g.opening || '',
          world_facts: g.background ? [g.background] : [],
          character_goals: goals,
          character_secrets: secrets,
        });
        const state = response.state;
        if (!state?.preflight_id) throw new Error('主控开局响应缺少 preflight_id');
        setPreflightId(state.preflight_id);
        setDirectorState(state);
        setStoryPlan(response.story_plan || null);
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
      if (response.story_plan) setStoryPlan(response.story_plan);
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
      if (start.story_plan) setStoryPlan(start.story_plan);
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
        <RuntimeDirectorPanel sessionId={sessionId} initialState={directorState} initialPlan={storyPlan} />
      </div>
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <button className="btn2 btn2-ghost btn2-sm" onClick={back}>← 返回角色选择</button>
        <b style={{ fontSize: 15 }}>🎬 开局导演台 · {title}</b>
        <button className="btn2 btn2-sm" style={{ marginLeft: 'auto' }} onClick={() => go('home')}>🏠 模式选择</button>
      </div>

      {(phase === 'preflight-loading' || phase === 'launching') && (
        <div className="card2" style={{ textAlign: 'center', padding: 40 }}>
          <div className="loading-dots" style={{ fontSize: 30 }}>🔄</div>
          <div style={{ marginTop: 12 }}>{phase === 'launching' ? '正在按导演台方案进入场景…' : '正在建立开局导演台…'}</div>
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
        <div style={{ maxWidth: 980, margin: '0 auto', display: 'grid', gap: 12 }}>
          <div className="card2" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <b>导演台 · 剧本编排</b>
              <span className="hint">这里的设定会真正进入角色上下文，不是临时聊天备注</span>
              <button
                className="btn2 btn2-ghost btn2-sm"
                style={{ marginLeft: 'auto' }}
                disabled={sending}
                onClick={() => void sendPreflight(AUTO_PLAN_PROMPT)}
              >
                ✨ 让主控先编一套开局
              </button>
            </div>
            <DirectorStoryPlanStrip plan={storyPlan} revealPrivate />
          </div>

          <div className="card2" style={{ padding: 16 }}>
            <b>角色与舞台状态</b>
            <DirectorStateStrip state={directorState} />
            <div className="hint" style={{ marginTop: 10 }}>
              出场顺序只是最后一步。你可以直接说： “故事改成雨夜失踪案；兔子是记者、鲸鱼是目击者；我和兔子表面合作但互不信任；鲸鱼知道一个不能主动说出的秘密；先让兔子和我在场。”
            </div>
          </div>

          <div className="card2" style={{ padding: 16 }}>
            <div style={{ maxHeight: 380, overflowY: 'auto', display: 'grid', gap: 8 }}>
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
                placeholder="和主控编排剧本、关系、身份、目标、秘密、剧情拍点、触发事件或出场安排…"
                disabled={sending}
                style={{ flex: 1, minWidth: 0 }}
              />
              <button className="btn2 btn2-ghost" disabled={sending || !input.trim()} onClick={() => void sendPreflight(input)}>
                {sending ? '处理中…' : '发送'}
              </button>
              <button className="btn2" disabled={sending} onClick={() => void confirmAndStart()}>
                确认方案并开局
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
      {!!state.relationships?.length && <div><b>关系事实：</b>{state.relationships.join('；')}</div>}
      {!!state.scene_notes?.length && <div><b>公开场景事实：</b>{state.scene_notes.join('；')}</div>}
    </div>
  );
}

function DirectorStoryPlanStrip({ plan, revealPrivate = false }: { plan: DirectorStoryPlan | null; revealPrivate?: boolean }) {
  if (!plan) return <div className="hint" style={{ marginTop: 8 }}>剧本编排尚未载入</div>;
  const identities = Object.entries(plan.scene_identities || {});
  const relations = plan.structured_relationships || [];
  const goals = revealPrivate ? Object.entries(plan.character_goals || {}) : [];
  const secrets = revealPrivate ? Object.entries(plan.character_secrets || {}) : [];
  const beats = revealPrivate ? (plan.story_beats || []) : [];
  const events = revealPrivate ? (plan.opening_events || []) : [];
  const hasAny = !!plan.premise || !!plan.tone || !!plan.opening_situation || !!plan.stakes
    || identities.length > 0 || relations.length > 0 || goals.length > 0 || secrets.length > 0
    || beats.length > 0 || events.length > 0 || !!plan.your_goal || !!plan.your_secret;
  if (!hasAny) return <div className="hint" style={{ marginTop: 8 }}>还没有额外编排。可以让主控先生成一套开局方案。</div>;
  return (
    <div style={{ marginTop: 10, display: 'grid', gap: 7, fontSize: 13, lineHeight: 1.6 }}>
      {!!plan.premise && <div><b>剧本前提：</b>{plan.premise}</div>}
      {!!plan.tone && <div><b>基调：</b>{plan.tone}</div>}
      {!!plan.opening_situation && <div><b>开场局势：</b>{plan.opening_situation}</div>}
      {!!plan.stakes && <div><b>核心冲突 / 代价：</b>{plan.stakes}</div>}
      {!!plan.world_facts?.length && <div><b>世界事实：</b>{plan.world_facts.join('；')}</div>}
      {identities.length > 0 && <div><b>场景身份：</b>{identities.map(([n, v]) => `${n}＝${v}`).join('；')}</div>}
      {relations.length > 0 && <div><b>结构化关系：</b>{relations.map(r => `${r.from}→${r.to}：${r.relation}${r.detail ? `（${r.detail}）` : ''}`).join('；')}</div>}
      {goals.length > 0 && <div><b>角色目标：</b>{goals.map(([n, v]) => `${n}＝${v}`).join('；')}</div>}
      {secrets.length > 0 && <div><b>角色秘密：</b>{secrets.map(([n, v]) => `${n}＝${v}`).join('；')}</div>}
      {beats.length > 0 && <div><b>剧情拍点：</b>{beats.map((v, i) => `${i + 1}. ${v}`).join('；')}</div>}
      {events.length > 0 && <div><b>触发事件：</b>{events.join('；')}</div>}
      {!revealPrivate && !!plan.your_goal && <div><b>你的目标：</b>{plan.your_goal}</div>}
      {!revealPrivate && !!plan.your_secret && <div><b>你的秘密：</b>{plan.your_secret}</div>}
      {!revealPrivate && plan.hidden_plan && <div className="hint">其余角色目标、秘密和未来剧情拍点由主控隐藏管理。</div>}
    </div>
  );
}

function RuntimeDirectorPanel({
  sessionId,
  initialState,
  initialPlan,
}: {
  sessionId: string;
  initialState: DirectorState | null;
  initialPlan: DirectorStoryPlan | null;
}) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<DirectorState | null>(initialState);
  const [plan, setPlan] = useState<DirectorStoryPlan | null>(initialPlan);
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);

  useEffect(() => { setState(initialState); }, [initialState]);
  useEffect(() => { setPlan(initialPlan); }, [initialPlan]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    setSending(true);
    setInput('');
    setLines(prev => [...prev, { role: 'user', content: text }]);
    try {
      const response = await directorApi.runtimeChat(sessionId, text);
      if (response.state) setState(response.state);
      if (response.story_plan) setPlan(response.story_plan);
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
          position: 'fixed', right: 18, bottom: 66, width: 'min(460px, calc(100vw - 36px))', maxHeight: '76vh',
          zIndex: 91, padding: 14, boxShadow: '0 18px 50px rgba(0,0,0,.38)', overflowY: 'auto',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <b>🎬 主控通道</b>
            <span className="hint">舞台控制 + 剧本调整</span>
            <button className="btn2 btn2-ghost btn2-sm" style={{ marginLeft: 'auto' }} onClick={() => setOpen(false)}>✕</button>
          </div>
          <DirectorStoryPlanStrip plan={plan} />
          <div style={{ marginTop: 10, paddingTop: 8, borderTop: '1px solid rgba(255,255,255,.08)' }}>
            <DirectorStateStrip state={state} />
          </div>
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
              placeholder="改关系/身份/目标/剧情拍点，或让角色进出场…"
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
