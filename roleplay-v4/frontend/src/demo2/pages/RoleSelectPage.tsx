/**
 * RoleSelectPage.tsx — 角色选择（主页面 3，页 B）
 *
 * 顶部只显示剧本名；角色卡按游戏顺序：玩家角色 → 剧本默认角色 → 添加角色。
 * 角色卡只显示头像 + 名称。
 * 变体：
 *  - 剧本杀：默认角色（带秘密标记）+ 玩家角色 + 添加角色
 *  - 一般：同上 + 运行方式（自由聊天/2D探索）+ 场景历史记录
 *  - 狼人杀：狼人杀职业卡 + 玩家角色 + 联机房/快速开局
 * 自由角色可作为其他剧本的角色来源。
 */
import { useMemo, useState } from 'react';
import { useDemoStore, type SceneHistory } from '../store';
import {
  AVATARS, getGeneralScriptById, getMurderScriptById, uid, mockGenerateRole,
} from '../mockData';
import type { RoleCard } from '../types';
import { api } from '../../api/client';
import { v1RoleToRoleCard } from '../mappers';
import { RoleForm, formToRole, roleToForm, type RoleFormValues } from '../components/RoleForm';
import { AiGenBox, type AiGenResult } from '../components/AiGenBox';
import { ImportRolesModal } from '../components/ImportRolesModal';
import { PhaserScriptMapView } from '../../phaser/PhaserScriptMapView';
import type { ScriptMap } from '../../phaser/mapData';
import { LargeMapModal } from '../components/LargeMapModal';

/** 稳定空数组（zustand 选择器避免每次返回新引用引发无限重渲染） */
const EMPTY_ROLES: RoleCard[] = [];

const WW_ROLE_DEFS: { role: string; avatar: string; desc: string }[] = [
  { role: '狼人', avatar: '🐺', desc: '每晚刀杀一人，伪装成平民活到最后' },
  { role: '预言家', avatar: '🔮', desc: '每晚查验一名玩家是否为狼人' },
  { role: '女巫', avatar: '🧪', desc: '拥有解药与毒药，各一次' },
  { role: '猎人', avatar: '🏹', desc: '被放逐时可开枪带走一人' },
  { role: '平民', avatar: '👤', desc: '靠推理与发言找出狼人' },
];

export function RoleSelectPage() {
  const ctx = useDemoStore(s => s.selectCtx);
  const back = useDemoStore(s => s.back);
  const go = useDemoStore(s => s.go);
  const selectRole = useDemoStore(s => s.selectRole);
  const playerRole = useDemoStore(s => s.playerRole);
  const setPlayerRole = useDemoStore(s => s.setPlayerRole);
  const freeRoles = useDemoStore(s => s.freeRoles);
  const genRoles = useDemoStore(s => s.genRoles);
  const extraRoles = useDemoStore(s => s.extraRoles[ctx.scriptId ?? ''] || EMPTY_ROLES);
  const litRoles = useDemoStore(s => s.litRoles);
  const setLitRoles = useDemoStore(s => s.setLitRoles);
  const removeExtraRole = useDemoStore(s => s.removeExtraRole);
  const effectiveScriptRoles = useDemoStore(s => s.effectiveScriptRoles);
  const removedScriptRoles = useDemoStore(s => s.removedScriptRoles);
  const runMode = useDemoStore(s => s.runMode);
  const setRunMode = useDemoStore(s => s.setRunMode);
  const withPlayer = useDemoStore(s => s.withPlayer);
  const setWithPlayer = useDemoStore(s => s.setWithPlayer);
  const historyList = useDemoStore(s => s.historyList);
  const startGame = useDemoStore(s => s.startGame);
  const addHistory = useDemoStore(s => s.addHistory);
  const enterRoles = useDemoStore(s => s.enterRoles);

  const [playerPicker, setPlayerPicker] = useState(false);
  const [addModal, setAddModal] = useState<'menu' | 'new' | 'import' | 'ai' | null>(null);
  // P-0811-G：一般模式 LLM 地图预览（null=未打开；map 由后端 /api/scenes/map 生成）
  const [mapPreview, setMapPreview] = useState<{ open: boolean; busy: boolean; map: ScriptMap | null; error: string }>({ open: false, busy: false, map: null, error: '' });
  // P-0818-H：大型地图（原独立「大型结构」页迁入一般模式 2D 探索选项）
  const [largeMapOpen, setLargeMapOpen] = useState(false);
  const [newRole, setNewRole] = useState<RoleFormValues>(() => ({
    ...roleToForm({ id: '', name: '', avatar: AVATARS[0], intro: '', personality: '', talkStyle: '', hasSecret: false, source: 'free', homeScripts: [] }),
    ttsEnabled: false,
  }));
  const closeModal = () => setAddModal(null);

  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);
  // P-0816-L：后端场景剧本（source='backend'，GET /api/scenes 映射）——作为 mockData 预设 / 生成剧本之后的第三解析源
  const backendMurder = useDemoStore(s => s.backendMurder);
  const backendGeneral = useDemoStore(s => s.backendGeneral);
  // P-0811-E：剧本生成页 LLM 失败兜底提示（生成后跳转本页仍可见）
  const genNotice = useDemoStore(s => s.genNotice);
  const setGenNotice = useDemoStore(s => s.setGenNotice);

  const murder = (ctx.kind === 'murder' && ctx.scriptId)
    ? (getMurderScriptById(ctx.scriptId) ?? (generatedMurder?.id === ctx.scriptId ? generatedMurder : undefined) ?? backendMurder.find(x => x.id === ctx.scriptId))
    : undefined;
  const general = (ctx.kind === 'general' && ctx.scriptId)
    ? (getGeneralScriptById(ctx.scriptId) ?? (generatedGeneral?.id === ctx.scriptId ? generatedGeneral : undefined) ?? backendGeneral.find(x => x.id === ctx.scriptId))
    : undefined;

  const defaultRoles = useMemo(() => {
    if (ctx.kind === 'murder') return effectiveScriptRoles(ctx.scriptId ?? '', murder?.roles ?? []);
    if (ctx.kind === 'general') return effectiveScriptRoles(ctx.scriptId ?? '', general?.roles ?? []);
    return [];
  }, [ctx.kind, murder, general, ctx.scriptId, effectiveScriptRoles, removedScriptRoles]);

  const title = ctx.kind === 'murder' ? (murder?.title ?? '剧本') : ctx.kind === 'general' ? (general?.title ?? '场景') : '狼人杀';

  // 角色点亮态：未手动点过时默认全亮（不写 store，避免 effect 循环）
  const scriptKey = ctx.scriptId ?? '';
  const storedLit = litRoles[scriptKey];
  const lit = ctx.kind === 'werewolf' ? null : (storedLit ?? [...defaultRoles, ...extraRoles].map(r => r.id));

  /** 切换点亮：未 seed 过时先以「默认全亮」为底再切换（保证首次点击只熄一个） */
  const toggleRole = (r: RoleCard) => {
    if (lit === null) return;
    const base = storedLit ?? [...defaultRoles, ...extraRoles].map(x => x.id);
    const next = base.includes(r.id) ? base.filter(id => id !== r.id) : [...base, r.id];
    setLitRoles(scriptKey, next);
  };

  /** 新增/导入角色默认参与本局，避免角色卡已出现却仍停留在“点亮 0”的死路。 */
  const addRoleAndLight = (r: RoleCard, source: 'new' | 'import' = 'new') => {
    const store = useDemoStore.getState();
    if (source === 'import') store.addExternalRole(r);
    else store.addNewRoleToScript(r, ctx.scriptId);
    const base = storedLit ?? [...defaultRoles, ...extraRoles].map(x => x.id);
    setLitRoles(scriptKey, [...new Set([...base, r.id])]);
  };

  /** 可作为角色来源的其他剧本角色（供「添加角色」导入） */
  const run = (kind: 'murder' | 'general' | 'werewolf') => {
    // 开始对局时清除生成兜底提示
    setGenNotice('');
    // 点亮选中的角色才进游戏
    const candidates = [...defaultRoles, ...extraRoles];
    const litNames = candidates.filter(r => lit?.includes(r.id)).map(r => r.name);
    const rawPlayers = kind === 'werewolf'
      ? [playerRole?.name ?? '我']
      : kind === 'general' && !withPlayer
        ? litNames
        : [...litNames, playerRole?.name ?? ''].filter(Boolean);
    // P-0819-A：玩家角色本身通常已经在「点亮角色」列表里；不去重会把同名角色
    // 送给后端两次，后端随后只能再做一次身份分配，表现为开局后重复选角。
    const players = [...new Set(rawPlayers)];
    // P-0811-A：历史记录记所属剧本（scriptId），角色选择页按剧本隔离展示
    addHistory({ title, kind, scriptId: ctx.scriptId, roleName: playerRole?.name ?? '我', result: `开始对局（${players.length} 名角色${kind === 'general' && !withPlayer ? ' · 不带玩家' : ''}）` });
    startGame(kind, players);
  };

  // P-0811-G：一般模式 LLM 地图生成 + 预览（POST /api/scenes/map，theme=场景描述；
  // 成功缓存到 store（进入 2D 探索复用同一张地图），失败显示错误不中断）
  const generalMaps = useDemoStore(s => s.generalMaps);
  const setGeneralMap = useDemoStore(s => s.setGeneralMap);
  // P-0811-A：只展示当前剧本（kind+scriptId）的历史，不再串别的剧本/场景的记录
  const scopedHistory = useMemo(
    () => historyList.filter(h => h.kind === ctx.kind && (h.scriptId ?? '') === (ctx.scriptId ?? '')),
    [historyList, ctx.kind, ctx.scriptId],
  );
  const hasOtherHistory = historyList.length > 0 && scopedHistory.length === 0;

  /** P-0811-A：历史条目可点击进入对应剧本的角色选择页（剧本已被删除/替换则不可点） */
  const canEnterHistory = (h: SceneHistory): boolean => {
    if (h.kind === 'werewolf') return true;
    if (h.kind === 'murder' && h.scriptId) {
      return !!getMurderScriptById(h.scriptId) || generatedMurder?.id === h.scriptId || backendMurder.some(x => x.id === h.scriptId);
    }
    if (h.kind === 'general' && h.scriptId) {
      return !!getGeneralScriptById(h.scriptId) || generatedGeneral?.id === h.scriptId || backendGeneral.some(x => x.id === h.scriptId);
    }
    return false;
  };

  const isLit = (r: RoleCard) => lit === null || lit.includes(r.id);

  const renderCard = (r: RoleCard, isExtra: boolean) => (
    <div
      key={r.id}
      className={`role-chip ${isLit(r) ? 'selected' : ''}`}
      title="点击点亮 / 熄灭选中 · 右键查看详情"
      onClick={() => toggleRole(r)}
      onContextMenu={e => { e.preventDefault(); selectRole(r.id); go('role-detail'); }}
    >
      {r.avatar} {r.name}
      {r.hasSecret && <span className="rc-secret">🔒</span>}
      <span
        className="rc-x"
        title={isExtra ? '删除此角色' : '熄灭（不进游戏）'}
        onClick={e => {
          e.stopPropagation();
          if (isExtra) removeExtraRole(scriptKey, r.id);
          else toggleRole(r);
        }}
      >✕</span>
    </div>
  );

  // P-0810-24：点亮计数与「玩家是否真的进对局」一致 —— 一般模式勾选了「带玩家」且选了玩家角色才 +1
  // （用户语义：取消选择玩家角色 = 玩家不参与本局 = 导演模式；UI 与对局不再不一致）
  const playerJoins = !!playerRole && (ctx.kind !== 'general' || withPlayer);
  // 剧本杀的玩家身份必须是本局成员；同名角色不能重复计数。
  const litNames = (lit === null ? [] : [...defaultRoles, ...extraRoles].filter(r => lit.includes(r.id)).map(r => r.name));
  const effectivePlayers = new Set([...litNames, ...(playerJoins ? [playerRole!.name] : [])]);
  const litCount = effectivePlayers.size;
  const canStart = ctx.kind === 'werewolf'
    || (ctx.kind === 'murder' ? !!playerRole && litCount > 0 : litCount > 0);

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <div className="page-head">
        <button className="btn2 btn2-ghost" onClick={back}>← 剧本选择</button>
        <h2 style={{ margin: '0 auto' }}>🃏 {title}</h2>
        <span style={{ fontSize: 12, color: 'var(--color-text-dim2)' }}>{ctx.kind === 'general' ? '一般模式 · 角色选择' : ctx.kind === 'murder' ? '剧本杀模式 · 角色选择' : '狼人杀 · 角色选择'}</span>
      </div>

      <div className="panel-col roles-col">
        <div className="roles-script-name">📜 {title}</div>

        {genNotice && (
          <div className="gen-error-note" style={{ margin: '10px 14px 0' }}>{genNotice}</div>
        )}

        <div style={{ padding: '12px 14px' }}>
          {/* 第一张：玩家角色（P-0810-24：加 ✕ 取消选择入口 + 不参与态显示，非删除角色） */}
          <div style={{ marginBottom: 10 }}>
            <div
              className="role-chip"
              style={{
                borderColor: 'var(--phase-investigation)',
                color: 'var(--phase-investigation)',
                cursor: 'pointer',
                opacity: playerRole && (ctx.kind !== 'general' || withPlayer) ? 1 : 0.65,
              }}
              title="点击选择玩家角色 · ✕ 取消选择（不参与本局，该局为导演模式）"
              onClick={() => setPlayerPicker(true)}
            >
              {playerRole?.avatar ?? '🧑'} {playerRole?.name ?? '选择你的角色'}
              <span style={{ fontSize: 10.5, opacity: 0.85 }}>
                {ctx.kind === 'general' && (!playerRole || !withPlayer) ? '· 不参与本局（导演）' : '· 玩家角色'}
              </span>
              {playerRole && (
                <span
                  className="rc-x"
                  title="取消选择玩家角色（不参与本局，不是删除角色）"
                  onClick={e => {
                    e.stopPropagation();
                    setPlayerRole(null);
                    // 一般模式：取消玩家角色 = 不带玩家 = 导演模式（与后端 mode=director 语义一致）
                    if (ctx.kind === 'general') setWithPlayer(false);
                  }}
                >✕</span>
              )}
            </div>
          </div>

          <div className="role-chips">
            {/* 剧本默认角色（剧本杀/一般）；狼人杀显示职业卡 */}
            {ctx.kind === 'werewolf'
              ? WW_ROLE_DEFS.map(w => (
                  <span key={w.role} className="role-chip" title={w.desc} style={{ cursor: 'default' }}>
                    {w.avatar} {w.role}
                  </span>
                ))
              : defaultRoles.map(r => renderCard(r, false))}

            {extraRoles.map(r => renderCard(r, true))}

            {/* 最后：添加角色（狼人杀职业固定，不提供） */}
            {ctx.kind !== 'werewolf' && (
              <button className="role-chip role-chip-add" onClick={() => setAddModal('menu')}>＋ 添加角色</button>
            )}
          </div>
        </div>

        {/* 一般模式：运行方式选择 + 带玩家开关 */}
        {ctx.kind === 'general' && (
          <div className="roles-footer" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <span className="run-mode-label">运行方式</span>
              <button className={`chip2 ${runMode === 'chat' ? 'active' : ''}`} onClick={() => setRunMode('chat')}>💬 自由聊天模式</button>
              <button className={`chip2 ${runMode === 'explore' ? 'active' : ''}`} onClick={() => setRunMode('explore')}>🗺️ 2D 探索模式</button>
              {/* P-0820-M：普通地图与大型地图统一入口；尺寸/结构/模式等均从设置页读取 */}
              <button className="chip2" onClick={() => setLargeMapOpen(true)} title="按设置页的尺寸、结构和地图模式生成，并作为本场景 2D 探索地图">
                🗺️ 生成地图{general && ctx.scriptId && generalMaps[ctx.scriptId] ? '（已有地图）' : ''}
              </button>
            </div>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, fontSize: 13, color: 'var(--color-text-dim)', cursor: 'pointer' }}>
              <input type="checkbox" checked={withPlayer} onChange={e => setWithPlayer(e.target.checked)} style={{ width: 'auto' }} />
              带玩家（你的化身进入场景，可直接对话/移动）—— 取消则纯 AI 观看模式
            </label>
          </div>
        )}

        {/* 狼人杀：联机房 + 快速开局 */}
        {ctx.kind === 'werewolf' && (
          <div className="roles-footer" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
            <div className="ww-log" style={{ maxHeight: 140 }}>
              <div>🏠 联机房：可加入现有房间码（demo 展示），或直接快速开局。</div>
              <div>🤖 快速开局：AI 自动补满 8 名玩家并分配职业。</div>
              <div>🎲 职业随机发放：开局时从角色池随机分配（狼人×2 / 预言家 / 女巫 / 猎人 / 平民）。</div>
              <div>🕵️ 身份保密：对局中其他玩家职业显示 <b>？？？</b>，出局或终局才揭晓（仅主持人可见全员）。</div>
            </div>
            <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
              <input type="text" placeholder="输入房间码加入…" style={{ flex: 1 }} />
              <button className="btn2 btn2-ghost">加入房间</button>
            </div>
          </div>
        )}

        {/* 场景历史记录（P-0811-A：按当前剧本隔离展示 + 可点击进入对应剧本） */}
        <div className="roles-footer" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
          <span className="run-mode-label">📚 场景历史记录{ctx.scriptId ? `（《${title}》）` : ''}</span>
          {scopedHistory.length === 0 ? (
            <div style={{ fontSize: 12.5, color: 'var(--color-text-dim2)' }}>
              {hasOtherHistory ? '本剧本暂无历史记录（其他剧本/场景的记录已按剧本隔离）。' : '暂无历史记录，开始你的第一场对局吧。'}
            </div>
          ) : (
            <div className="ww-log" style={{ maxHeight: 140 }}>
              {scopedHistory.map(h => {
                const clickable = canEnterHistory(h);
                return (
                  <div
                    key={h.id}
                    onClick={clickable ? () => enterRoles({ kind: h.kind, scriptId: h.scriptId }) : undefined}
                    title={clickable ? `进入《${h.title}》角色选择` : '该剧本已不存在（已删除/被新生成剧本替换），无法进入'}
                    style={{ cursor: clickable ? 'pointer' : 'default', textDecoration: clickable ? 'underline dotted' : 'none', opacity: clickable ? 1 : 0.75 }}
                  >
                    <b style={{ color: 'var(--color-accent-gold)' }}>{h.title}</b> · {h.roleName} · {h.result} · <span style={{ color: 'var(--color-text-dim2)' }}>{h.time}</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="roles-footer" style={{ justifyContent: 'center', borderTop: '1px solid var(--line2)' }}>
          <button className="btn2 btn2-primary" style={{ padding: '12px 34px', fontSize: 15 }} disabled={!canStart} onClick={() => run(ctx.kind)}>
            {ctx.kind === 'werewolf' ? '🐺 开始狼人杀' : runMode === 'explore' && ctx.kind === 'general' ? '🗺️ 进入 2D 探索' : `🎮 进入对局（点亮 ${litCount}）`}
          </button>
          <span style={{ fontSize: 12, color: 'var(--color-text-dim2)' }}>
            {ctx.kind === 'werewolf' ? '职业随机发放 · 其他玩家身份保密' : ctx.kind === 'murder' ? '先选择你要扮演的角色；点亮角色才会进入本局 · 右键看详情 · ✕ 删除/熄灭' : '点击角色卡点亮/熄灭，点亮者才进游戏 · 右键看详情 · ✕ 删除/熄灭'}
          </span>
        </div>
      </div>

      {/* P-0820-M：统一地图生成结果预览弹窗 */}
      {mapPreview.open && (
        <div className="modal-mask" onClick={() => setMapPreview(p => ({ ...p, open: false }))}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 860, width: '94%' }}>
            <div className="modal-head">
              <div className="modal-title">🗺️ {general?.title || '一般模式'} · 2D 地图预览</div>
              <button className="modal-close" onClick={() => setMapPreview(p => ({ ...p, open: false }))}>✕</button>
            </div>
            {mapPreview.busy ? (
              <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-text-dim2)' }}>
                <div style={{ fontSize: 30, marginBottom: 10 }}>🔄</div>正在生成地图（LLM 全量生成，失败自动 BSP 兜底，约 10-60 秒）…
              </div>
            ) : mapPreview.error ? (
              <div style={{ padding: 30, textAlign: 'center', color: 'var(--color-danger)' }}>⚠️ {mapPreview.error}</div>
            ) : mapPreview.map ? (
              <div style={{ padding: 10 }}>
                <PhaserScriptMapView
                  map={mapPreview.map}
                  playerName=""
                  readOnly
                  fetchAssets
                  title={`🗺️ ${general?.title || '场景'} 地图（Phaser 渲染 · 只读预览）`}
                  aiCharacters={(defaultRoles || []).map(r => r.name)}
                />
                <div style={{ fontSize: 12, color: 'var(--color-text-dim2)', marginTop: 8, textAlign: 'center' }}>
                  WASD 移动 · 滚轮缩放 · 全屏探索；进入「2D 探索模式」将复用这张 LLM 生成的地图。
                </div>
              </div>
            ) : null}
          </div>
        </div>
      )}

      {/* P-0818-H：大型地图生成弹窗（一般模式 2D 探索的地图选项） */}
      {largeMapOpen && (
        <LargeMapModal
          defaultTheme={general?.title ?? ''}
          onClose={() => setLargeMapOpen(false)}
          onSave={(map) => {
            if (ctx.scriptId) setGeneralMap(ctx.scriptId, map);
          }}
          onUse={(map) => {
            // 缓存为本场景地图：进入 2D 探索时 GameBridge 直接复用（generalMaps[scriptId]）
            if (ctx.scriptId) setGeneralMap(ctx.scriptId, map);
            setLargeMapOpen(false);
            setMapPreview({ open: true, busy: false, map, error: '' });
          }}
        />
      )}

      {/* 玩家角色选择弹窗 */}
      {playerPicker && (
        <div className="modal-mask" onClick={() => setPlayerPicker(false)}>
            <div className="modal-box" onClick={e => e.stopPropagation()}>
              <div className="modal-head">
                <div className="modal-title">选择你的玩家角色</div>
                <button className="modal-close" onClick={() => setPlayerPicker(false)}>✕</button>
              </div>
              <div style={{ maxHeight: 340, overflowY: 'auto', padding: '4px 2px' }}>
                {/* P-0810-24：弹窗取消入口 —— 取消选择玩家角色（不参与本局 = 导演模式），非删除 */}
                <button
                  className="btn2 btn2-ghost btn2-sm"
                  style={{ width: '100%', marginBottom: 10 }}
                  onClick={() => {
                    setPlayerRole(null);
                    if (ctx.kind === 'general') setWithPlayer(false);
                    setPlayerPicker(false);
                  }}
                >
                  🚫 取消选择玩家角色（不参与本局，导演模式）
                </button>
                <div style={{ fontSize: 11.5, color: 'var(--color-text-dim2)', padding: '2px 4px', marginBottom: 8 }}>📚 角色库（剧本杀 / 一般模式互通）</div>
                <div className="role-chips">
                  {[...freeRoles, ...genRoles].map(r => (
                    <button key={r.id} className="role-chip" onClick={() => { setPlayerRole(r); setPlayerPicker(false); }}>
                      {r.avatar} {r.name}<span style={{ fontSize: 10.5, opacity: 0.7 }}>·{r.source === 'ai' ? 'AI' : '自由'}</span>
                    </button>
                  ))}
                </div>
                <div style={{ fontSize: 11.5, color: 'var(--color-text-dim2)', padding: '2px 4px', margin: '10px 0 8px' }}>📜 本剧本角色</div>
                <div className="role-chips">
                  {defaultRoles.map(r => (
                    <button key={r.id} className="role-chip" onClick={() => { setPlayerRole(r); setPlayerPicker(false); }}>
                      {r.avatar} {r.name}{r.hasSecret && <span className="rc-secret">🔒</span>}
                    </button>
                  ))}
                </div>
                <button className="btn2 btn2-sm" style={{ width: '100%', marginTop: 10 }} onClick={() => { setPlayerPicker(false); go('roles-lib'); }}>🎭 去角色库创建更多角色 →</button>
              </div>
            </div>
        </div>
      )}

      {/* 添加角色弹窗 */}
      {/* 添加角色：新增（手动） */}
      {addModal === 'new' && (
        <div className="modal-mask" onClick={closeModal}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720 }}>
            <div className="modal-head">
              <div className="modal-title">✨ 新增自定义角色</div>
              <button className="modal-close" onClick={closeModal}>✕</button>
            </div>
            <RoleForm values={newRole} onChange={setNewRole} showSecret={ctx.kind === 'murder'} />
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
              <button className="btn2 btn2-ghost" onClick={closeModal}>取消</button>
              <button className="btn2 btn2-primary" onClick={() => {
                if (!newRole.name.trim()) return;
                const r = formToRole(newRole, { id: uid('newrole'), source: 'free', homeScripts: ctx.scriptId ? [ctx.scriptId] : [] });
                addRoleAndLight(r);
                closeModal();
              }}>添加</button>
            </div>
          </div>
        </div>
      )}

      {/* 添加角色：AI 生成 */}
      {addModal === 'ai' && (
        <div className="modal-mask" onClick={closeModal}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 640 }}>
            <div className="modal-head">
              <div className="modal-title">🤖 AI 生成角色</div>
              <button className="modal-close" onClick={closeModal}>✕</button>
            </div>
            <AiGenBox
              placeholder="描述你想要的角色…"
              stages={['AI 正在构思角色设定…', '正在补充背景故事…']}
              generate={async (prompt): Promise<AiGenResult> => {
                // P-0811-E：AI 生成角色接真实 LLM（带当前剧本上下文 title+背景，拿不到就不传）
                try {
                  const sceneName = ctx.scriptId ? title : undefined;
                  const sceneDesc = ctx.scriptId
                    ? (ctx.kind === 'murder' ? murder?.background : general?.desc)
                    : undefined;
                  const r = await api.generateCharacter(prompt, sceneName, sceneDesc);
                  const name = String(r?.name || mockGenerateRole(prompt).name);
                  const role = v1RoleToRoleCard(
                    { id: `ai_${Date.now().toString(36)}`, name, intro: r?.summary || r?.appearance || '' },
                    0,
                    ctx.scriptId ? [ctx.scriptId] : [],
                  );
                  addRoleAndLight(role);
                  return { text: `已创建角色「${role.name}」并加入本剧本。` };
                } catch (e: any) {
                  const msg = String(e?.message || '生成失败');
                  // 撞名 409：提示换描述（不兜底 mock——mock 角色名同样可能撞名），弹窗保持打开
                  if (/已存在/.test(msg)) {
                    throw new Error(`${msg}。请换个描述（LLM 会生成不同的角色名），或直接使用现有角色。`);
                  }
                  // 其他失败（网络/超时/5xx）：回退本地 mock + 可见提示，不中断流程
                  const role = mockGenerateRole(prompt);
                  addRoleAndLight(role);
                  return { text: `LLM 生成失败，已用本地模板兜底：已创建角色「${role.name}」并加入本剧本。（原因：${msg}）` };
                }
              }}
              onResult={() => closeModal()}
            />
          </div>
        </div>
      )}

      {/* 添加角色：从其他剧本导入（左右结构） */}
      {addModal === 'import' && (
        <ImportRolesModal
          currentScriptId={ctx.scriptId ?? ''}
          currentKind={ctx.kind === 'general' ? 'general' : 'murder'}
          importedIds={extraRoles.map(r => r.id)}
          onImport={r => { addRoleAndLight({ ...r, source: 'import' }, 'import'); }}
          onClose={closeModal}
        />
      )}

      {/* 添加角色菜单 */}
      {addModal === 'menu' && (
        <div className="modal-mask" onClick={closeModal}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 440 }}>
            <div className="modal-head">
              <div className="modal-title">添加角色</div>
              <button className="modal-close" onClick={closeModal}>✕</button>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <button className="role-add" onClick={() => setAddModal('new')}>✨ 新增自定义角色（手动）</button>
              <button className="role-add" onClick={() => setAddModal('ai')}>🤖 AI 生成角色</button>
              <button className="role-add" onClick={() => setAddModal('import')}>📥 从其他剧本导入角色</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
