/**
 * GameBridge.tsx — 真实对局启动器（接后端游玩）
 *
 * 按「对局沿用整机版前端（接后端）」决策：进入对局时调用真实后端初始化对应模式，
 * 然后挂载整机版游玩 UI（ChatPage / PhaserSimulationView）。
 *
 * 模式/人数差异：
 *  - 剧本杀(murder)：POST /api/script/init {theme, players(点亮角色名), mode=full} → ChatPage(ScriptStatePanel)
 *  - 一般·自由聊天(general+chat)：POST /api/scenes/{id}/start（agents=点亮角色，带玩家时含玩家）→ ChatPage(自由对话)
 *  - 一般·2D探索(general+explore)：LLM 生成地图（POST /api/scenes/map，theme=场景描述；复用角色选择页缓存）→ 注入 /api/simulation 动态模拟（角色自动移动/对话）
 *  - 狼人杀(werewolf)：POST /api/werewolf/init（玩家 + AI 补满 8 人）→ ChatPage(狼人杀面板)
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useDemoStore } from '../store';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { getGeneralScriptById, getMurderScriptById } from '../mockData';
import { ChatPage } from '../../components/ChatPage/ChatPage';
import { GalGeneralView } from '../../gal/GalGeneralView';
import { useGalStore } from '../../gal/GalStore';
import { PhaserSimulationView } from '../../phaser/PhaserSimulationView';
import type { ScriptMap } from '../../phaser/mapData';
import type { GeneralScript, RoleCard } from '../types';

const WW_AI_NAMES = ['AI·白', 'AI·青', 'AI·玄', 'AI·墨', 'AI·雪', 'AI·枫', 'AI·岚', 'AI·渊'];

type Phase = 'launching' | 'ready' | 'error';

export function GameBridge() {
  const gameMode = useDemoStore(s => s.gameMode);
  const gamePlayers = useDemoStore(s => s.gamePlayers);
  const runMode = useDemoStore(s => s.runMode);
  const withPlayer = useDemoStore(s => s.withPlayer);
  const selectCtx = useDemoStore(s => s.selectCtx);
  const playerRole = useDemoStore(s => s.playerRole);
  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);
  // P-0816-L：后端场景剧本（source='backend'）——mockData 预设 / 生成剧本之后的第三解析源
  const backendMurder = useDemoStore(s => s.backendMurder);
  const backendGeneral = useDemoStore(s => s.backendGeneral);
  const freeRoles = useDemoStore(s => s.freeRoles);
  const genRoles = useDemoStore(s => s.genRoles);
  const extraRoles = useDemoStore(s => s.extraRoles);
  const go = useDemoStore(s => s.go);
  const back = useDemoStore(s => s.back);

  const [phase, setPhase] = useState<Phase>('launching');
  const [step, setStep] = useState('正在初始化…');
  const [error, setError] = useState('');
  const startedRef = useRef(false);
  // P-0810-08：一般模式 chat 默认 Gal 视图；经典视图（ChatPage 同会话）回退开关
  const [galClassic, setGalClassic] = useState(false);
  // startScene 返回的独立会话 session_id（与 appStore 显式 session 状态同步）。
  const [chatSessionId, setChatSessionId] = useState('');
  // P-0820-M：一般模式 2D 探索统一使用设置页的结构地图配置（复用角色选择页缓存；无缓存则生成）
  const [exploreMap, setExploreMap] = useState<ScriptMap | null>(null);
  const generalSessionId = useAppStore(s => s.sessionId);
  const getGeneralMap = useDemoStore(s => s.getGeneralMap);
  const setGeneralMap = useDemoStore(s => s.setGeneralMap);
  // P-0817-D：地图尺寸读设置（与角色选择页预览同源；超 40×24 自动走后端 BSP 确定性大图）
  const mapGen = useDemoStore(s => s.settings.mapGen);

  const script = useMemo(() => {
    if (gameMode === 'murder') {
      return selectCtx.scriptId
        ? (getMurderScriptById(selectCtx.scriptId) ?? (generatedMurder?.id === selectCtx.scriptId ? generatedMurder : undefined) ?? backendMurder.find(x => x.id === selectCtx.scriptId))
        : undefined;
    }
    if (gameMode === 'general') {
      return selectCtx.scriptId
        ? (getGeneralScriptById(selectCtx.scriptId) ?? (generatedGeneral?.id === selectCtx.scriptId ? generatedGeneral : undefined) ?? backendGeneral.find(x => x.id === selectCtx.scriptId))
        : undefined;
    }
    return undefined;
  }, [gameMode, selectCtx, generatedMurder, generatedGeneral, backendMurder, backendGeneral]);

  /** 角色名 → 完整角色（供 characterDetails 传 persona/voice/background） */
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
    const app = useAppStore.getState();

    const launch = async () => {
      try {
        if (!useAppStore.getState().initialized) {
          setStep('正在连接后端…');
          await app.loadState();
        }
        if (gameMode === 'murder') {
          // P-0819-P：浏览器刷新/进程重启后优先恢复已有剧本杀，禁止再次 POST init 生成第二局。
          // session_id 与 role_key 均由 appStore setter 镜像到 localStorage；后端 /resume
          // 会从快照恢复当前 phase，ENDED 也保留终局结果。
          const savedSessionId = localStorage.getItem('scriptSessionId') || '';
          const savedRoleKey = localStorage.getItem('scriptRoleKey') || '';
          if (savedSessionId && savedRoleKey) {
            setStep('正在恢复剧本杀对局…');
            const resumed = await api.scriptResume({ game_id: savedSessionId, player_key: savedRoleKey });
            if (resumed?.error) throw new Error(String(resumed.error));
            const resumedPlayer = String(resumed?.player || app.currentPlayer || '');
            if (!resumedPlayer || !resumed?.session_id) throw new Error('恢复响应缺少玩家或 session_id');
            app.setScriptSessionId(String(resumed.session_id));
            app.setScriptRoleKey(savedRoleKey);
            app.setScriptState(resumed);
            useAppStore.setState({ mode: 'script', currentPlayer: resumedPlayer, boundCharacterName: resumedPlayer });
            setPhase('ready');
            return;
          }
          setStep('正在生成剧本并分发角色…');
          if (!script) throw new Error('剧本数据缺失，请返回重新选择。');
          const selectedPlayer = playerRole?.name;
          if (!selectedPlayer) throw new Error('请先在角色选择页选择你要扮演的角色。');
          // init 只向 players[0] 发放 role_key，因此把真人放在首位，保证身份和令牌一一对应。
          const players = [selectedPlayer, ...gamePlayers.filter(name => name !== selectedPlayer)];
          const resp = await api.scriptInit(script.title, players, 'full', undefined, selectedPlayer);
          const sid = resp?.session_id;
          if (sid) useAppStore.getState().setScriptSessionId(String(sid));
          // [NEEDS CHECK-2 核实结论]：init 响应含 role_key（ScriptGameService.initGame 返回
          // game.toMap(playerNames[0]) —— toMap 对本人暴露 role_key 键），原 GameBridge 未回写；
          // ChatPage 3s 轮询 setScriptState 也会回写，但首屏/讨论进行中不等轮询——此处补回写，
          // ScriptGalChatPanel 的 liveSay → scriptDiscussionSay 带 key 身份校验立即可用（纯前端 store 层）。
          if (resp?.role_key) useAppStore.getState().setScriptRoleKey(String(resp.role_key));
          useAppStore.setState({ mode: 'script', currentPlayer: selectedPlayer, boundCharacterName: selectedPlayer });
          // P-0815-F（方向1，根因 A）：init 为两阶段生成（outline_only 缺省 true，对局停在 SETUP），
          // 此处自动触发完整剧本生成（POST /api/script/generate_full，后端异步）——
          // fire-and-forget：不阻塞 ready 进入对局；失败仅 warn 不阻断（玩家仍可在右侧面板手动点「🔄 生成完整剧本」）。
          if (sid) {
            api.scriptGenerateFull(sid).catch((e: any) =>
              console.warn('[GameBridge] generate_full 触发失败（可在状态面板手动重试）：', e));
          }
        } else if (gameMode === 'werewolf') {
          const playerName = playerRole?.name ?? (gamePlayers[0] || '我');
          const savedSessionId = localStorage.getItem('werewolfSessionId') || '';
          const savedRoleKey = localStorage.getItem('werewolfRoleKey') || '';
          const savedPlayer = localStorage.getItem('werewolfPlayer') || playerName;
          // 刷新/进程恢复优先恢复原局；凭证存在但恢复失败时明确报错，禁止静默 init 第二局。
          if (savedSessionId && savedRoleKey && savedPlayer) {
            setStep('正在恢复狼人杀对局…');
            const resumed = await api.werewolfResume({
              session_id: savedSessionId,
              player: savedPlayer,
              player_key: savedRoleKey,
            });
            if (resumed?.error) throw new Error(String(resumed.error));
            const resumedSessionId = String(resumed?.session_id || savedSessionId);
            app.setWerewolfSessionId(resumedSessionId);
            app.setWerewolfRoleKey(savedRoleKey);
            app.setCurrentPlayer(savedPlayer);
            localStorage.setItem('werewolfPlayer', savedPlayer);
            useAppStore.setState({
              mode: 'werewolf',
              ...(resumed?.phase ? { werewolfPhase: resumed.phase } : {}),
              ...(typeof resumed?.round === 'number' ? { werewolfRound: resumed.round } : {}),
            });
            setPhase('ready');
            return;
          }
          setStep('正在创建狼人杀对局（AI 补满 8 人）…');
          const aiFill = WW_AI_NAMES.filter(n => !gamePlayers.includes(n));
          // 后端 init 只向 players[0] 返回本人 role_key；把当前真人固定在首位，避免拿到他人令牌。
          const players = [playerName, ...gamePlayers.filter(n => n !== playerName), ...aiFill.filter(n => n !== playerName)].slice(0, 8);
          const resp = await api.werewolfInit(playerName, players);
          const sid = resp?.session_id;
          const roleKey = resp?.role_key;
          if (!sid || !roleKey) throw new Error('狼人杀初始化响应缺少 session_id 或 role_key');
          app.setWerewolfSessionId(String(sid));
          app.setWerewolfRoleKey(String(roleKey));
          app.setCurrentPlayer(playerName);
          localStorage.setItem('werewolfPlayer', playerName);
          useAppStore.setState({
            mode: 'werewolf',
          });
        } else if (gameMode === 'general' && (runMode === 'explore' || selectCtx.scriptId === 'g_dawn_social')) {
          // 2D 探索：LLM 瓦片地图（P-0811-G：复用角色选择页缓存；无缓存进入时生成）
          setStep('正在加载 2D 世界（生成地图）…');
          const g = script as (GeneralScript | undefined);
          if (selectCtx.scriptId === 'g_dawn_social') {
            // 晨雾镇直接走一般模式的 Phaser 2D 主链路：地图、碰撞、坐标、移动与对话
            // 都使用 SimulationService 的真实状态。PhaserSimulationView 是唯一的加载/启动入口，
            // 避免 GameBridge 与地图组件重复 init 导致坐标和对话被重置两次。
            setExploreMap((g as any)?.map ?? null);
            setPhase('ready');
            return;
          }
          const savedMap = getGeneralMap(selectCtx.scriptId);
          if (savedMap) {
            setExploreMap(savedMap);
          } else {
            try {
              if (g && g.desc?.trim()) {
                const seedText = String(mapGen.seed || '').trim();
                const structure = await api.structureGenerate({
                  theme: g.desc.trim(),
                  kind: mapGen.kind || 'city_block',
                  map_mode: mapGen.mapMode || 'single',
                  width: mapGen.width,
                  height: mapGen.height,
                  style: mapGen.style === '随剧本风格' ? undefined : mapGen.style,
                  audit: mapGen.audit,
                  ...(seedText && !Number.isNaN(Number(seedText)) ? { seed: Number(seedText) } : {}),
                });
                const mapId = structure?.current_map_id || Object.keys(structure?.maps || {})[0] || '';
                const generatedMap = (structure?.maps && mapId ? structure.maps[mapId] : undefined)
                  || Object.values(structure?.maps || {})[0];
                if (generatedMap) {
                  if (selectCtx.scriptId) setGeneralMap(selectCtx.scriptId, generatedMap as ScriptMap);
                  setExploreMap(generatedMap as ScriptMap);
                } else {
                  // P-0820-M 兜底：统一结构生成失败 → 用场景自带默认地图，不让对局空场景
                  setExploreMap((g as any)?.map ?? null);
                }
              } else {
                // 无描述 → 用场景自带默认地图
                setExploreMap((g as any)?.map ?? null);
              }
            } catch {
              // 生成失败 → 用场景自带默认地图兜底（仍能进地图，不空场景）
              setExploreMap((g as any)?.map ?? null);
            }
          }
        } else if (gameMode === 'general') {
          setStep('正在进入场景（自由对话）…');
          if (!script) throw new Error('场景数据缺失，请返回重新选择。');
          const aiNames = gamePlayers.filter(n => n !== playerRole?.name);
          // P-0810-24：取消选择玩家角色（✕/弹窗取消）→ 不带玩家 → 导演语义；
          // withPlayer 为真但 playerRole 为空（极端态）也视为不带玩家，避免空角色名进对局
          const playerName = withPlayer && playerRole ? playerRole.name : undefined;
          const agents = playerName ? [...aiNames, playerName] : aiNames;
          if (agents.length === 0) throw new Error('至少需要一名角色（请点亮角色卡）。');
          const charDetails = agents.map(n => {
            const r = roleByName.get(n);
            return r ? { name: r.name, persona: r.personality, voice: r.tts?.voice || '', background: r.background || '' } : { name: n };
          });
          const startResp = await api.startScene(script.title, agents, playerName, charDetails);
          // P-0810-16：startScene 响应 goals（enabled=true + player_goal 明文 + AI ??）→ 场景卡即时渲染
          useGalStore.getState().setLiveGoals(startResp?.goals);
          // 使用 startScene 返回的独立会话 session_id，确保 Gal 与通用状态请求指向同一会话。
          const scid = startResp?.session_id || '';
          setChatSessionId(scid);
          useAppStore.setState({ sessionId: scid });
          useAppStore.setState({ mode: 'free', currentPlayer: playerRole?.name ?? '我' });
          await useAppStore.getState().loadState(scid || undefined);
        }
        setPhase('ready');
      } catch (e: any) {
        console.warn('[GameBridge] 对局启动失败：', e);
        setError(e?.message || '对局启动失败（请确认后端 8000 已运行）');
        setPhase('error');
      }
    };
    void launch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameMode]);

  const title = gameMode === 'murder' ? '剧本杀对局' : gameMode === 'werewolf' ? '狼人杀' : (runMode === 'explore' ? '一般模式 · 2D 探索' : '一般模式 · 自由聊天');

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <button className="btn2 btn2-ghost btn2-sm" onClick={back}>← 返回角色选择</button>
        <b style={{ fontSize: 15 }}>🎮 {title}</b>
        <span style={{ fontSize: 12, color: 'var(--color-text-dim2)' }}>
          {gamePlayers.length} 名角色{gameMode === 'general' ? (withPlayer ? ' · 带玩家' : ' · 不带玩家（观看）') : ''}
        </span>
        <button className="btn2 btn2-sm" style={{ marginLeft: 'auto' }} onClick={() => go('home')}>🏠 模式选择</button>
      </div>

      {phase === 'launching' && (
        <div className="card2" style={{ textAlign: 'center', padding: 40 }}>
          <div className="loading-dots" style={{ fontSize: 30 }}>🔄</div>
          <div style={{ marginTop: 12, color: 'var(--color-text-dim)' }}>{step}</div>
          <div className="hint" style={{ marginTop: 8 }}>需后端 8000 运行；首次启动 LLM 生成可能需要 30-60 秒。</div>
        </div>
      )}

      {phase === 'error' && (
        <div className="card2" style={{ maxWidth: 520, margin: '40px auto', textAlign: 'center' }}>
          <div style={{ fontSize: 34 }}>⚠️</div>
          <div style={{ marginTop: 12, color: 'var(--color-danger)', fontWeight: 700 }}>对局启动失败</div>
          <div className="hint" style={{ marginTop: 8, lineHeight: 1.8 }}>{error}</div>
          <div style={{ marginTop: 16, display: 'flex', gap: 10, justifyContent: 'center' }}>
            <button className="btn2" onClick={() => { startedRef.current = false; setPhase('launching'); setError(''); window.location.reload(); }}>重试</button>
            <button className="btn2 btn2-ghost" onClick={() => go('roles')}>返回角色选择</button>
          </div>
        </div>
      )}

      {phase === 'ready' && (
        <>
          {gameMode === 'general' && (runMode === 'explore' || selectCtx.scriptId === 'g_dawn_social') ? (
            /* P-0811-G：一般模式 2D 探索 = LLM 瓦片背景 + 双主控动态模拟（WorldDirector/TrackDirector 调控
               角色移动对话）；玩家角色点击地图可控制移动（SimulationScene.playerName 绑定）；LLM 地图瓦片
               渲染为背景 + 注入障碍。 */
            <PhaserSimulationView
              characters={gamePlayers.map(n => {
                const r = roleByName.get(n);
                return { name: n, persona: r?.personality || '', voice: r?.tts?.voice || '', background: r?.background || '' };
              })}
              scene={exploreMap ? 'custom' : 'park'}
              map={exploreMap ?? undefined}
              // P-0816-D：不传固定 height → 自适应模式（地图填满视口剩余高度，Phaser FIT 随容器放大）
              playerName={withPlayer && playerRole ? playerRole.name : undefined}
              galChat
            />
          ) : gameMode === 'general' && runMode === 'chat' ? (
            // P-0810-08：一般模式会话呈现入口 = Gal 界面（默认）；右上「经典视图」回退 ChatPage 同会话
            galClassic ? (
              <div style={{ position: 'relative' }}>
                <button
                  className="btn2 btn2-ghost btn2-sm"
                  style={{ position: 'fixed', top: 74, right: 14, zIndex: 2000 }}
                  onClick={() => setGalClassic(false)}
                >
                  ← 返回 Gal 视图
                </button>
                <ChatPage />
              </div>
            ) : (
              <GalGeneralView
                sessionId={chatSessionId || generalSessionId}
                playerName={withPlayer && playerRole ? playerRole.name : undefined}
                onBack={back}
                onClassic={() => setGalClassic(true)}
              />
            )
          ) : (
            <ChatPage />
          )}
        </>
      )}
    </div>
  );
}
