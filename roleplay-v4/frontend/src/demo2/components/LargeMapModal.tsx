/**
 * LargeMapModal.tsx — 一般模式 2D 探索的「大型地图」选项（P-0818-H）
 *
 * 原独立「大型结构」页（P-0817-L）迁入一般模式：角色选择页 2D 探索模式下，
 * 用此弹窗生成大型结构地图（城堡/庄园/街区/地牢/自定义），成功后把选中的
 * 地图缓存为当前场景的地图（generalMaps[scriptId]），进入 2D 探索直接复用。
 */
import { useMemo, useState } from 'react';
import { api } from '../../api/client';
import { PhaserScriptMapView } from '../../phaser/PhaserScriptMapView';
import type { ScriptMap } from '../../phaser/mapData';
import { useDemoStore } from '../store';

interface StructureResult {
  structure?: any;
  maps?: Record<string, any>;
  current_map_id?: string;
  connections?: Array<{ type: string; map_id?: string; from_map?: string; to_map?: string; exit?: any; warp?: any }>;
  generator?: { l0?: string; kind?: string; seed?: number; map_mode?: string; validation?: { ok?: boolean; errors?: string[]; warnings?: string[] } };
  fallback?: string[];
  audit?: { score?: number; issues?: Array<{ level?: string; what?: string; suggest?: string }>; rounds?: Array<{ round?: number; score?: number; issues?: Array<{ level?: string; what?: string; suggest?: string }>; error?: string }>; tweaks?: Record<string, number>; error?: string };
}

const KINDS: { key: string; label: string; icon: string; hint: string }[] = [
  { key: 'castle', label: '城堡', icon: '🏰', hint: '城门楼 → 外庭 → 大厅 → 两翼/塔楼/厨房/兵械库 → 花园' },
  { key: 'mansion', label: '庄园', icon: '🏡', hint: '门厅 → 客厅/书房/餐厅/厨房 → 卧室×3 → 后院 → 佣人房/储藏室' },
  { key: 'city_block', label: '城市街区', icon: '🏙️', hint: '主街 → 店铺×4/民居×4 → 广场 → 仓库（大图可自动拆多图）' },
  { key: 'dungeon', label: '地牢', icon: '⛓️', hint: '入口 → 大厅 → 监牢×4 → 储藏室 → 宝库 → 首领间' },
  { key: 'custom', label: '自定义（LLM）', icon: '✨', hint: '由 LLM 按主题生成结构树（语义蓝图），几何仍程序化生成' },
];

interface Props {
  defaultTheme: string;
  onClose: () => void;
  /** 把当前选中的地图持久化到当前一般模式场景，不改变当前页面。 */
  onSave: (map: ScriptMap) => void;
  /** 生成成功并选中一张地图后回调（父级缓存 generalMaps[scriptId] 并进 2D 探索） */
  onUse: (map: ScriptMap) => void;
}

export function LargeMapModal({ defaultTheme, onClose, onSave, onUse }: Props) {
  const mapGen = useDemoStore(s => s.settings.mapGen);
  const updateSettings = useDemoStore(s => s.updateSettings);
  const setMap = (patch: Partial<typeof mapGen>) => updateSettings({ mapGen: { ...mapGen, ...patch } });
  const kind = mapGen.kind || 'city_block';
  const [theme, setTheme] = useState(defaultTheme || '晨曦城堡');
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [result, setResult] = useState<StructureResult | null>(null);
  const [selMapId, setSelMapId] = useState('');

  const currentKind = KINDS.find(k => k.key === kind);

  const runGenerate = async () => {
    if (generating) return;
    if (!theme.trim()) {
      setError('请填写结构主题');
      return;
    }
    setError('');
    setSaved(false);
    setGenerating(true);
    try {
      const body: any = {
        theme: theme.trim(),
        kind,
        map_mode: mapGen.mapMode || 'single',
        width: Number(mapGen.width) > 0 ? Number(mapGen.width) : undefined,
        height: Number(mapGen.height) > 0 ? Number(mapGen.height) : undefined,
        style: mapGen.style === '随剧本风格' ? undefined : mapGen.style,
        audit: mapGen.audit,
      };
      const s = String(mapGen.seed || '').trim();
      if (s && !Number.isNaN(Number(s))) body.seed = Number(s);
      const r = await api.structureGenerate(body);
      setResult(r);
      const first = r?.current_map_id || Object.keys(r?.maps || {})[0] || '';
      setSelMapId(first);
    } catch (e: any) {
      setError(`大型地图生成失败：${String(e?.message || '未知错误')}`);
    } finally {
      setGenerating(false);
    }
  };

  const mapIds = result?.maps ? Object.keys(result.maps) : [];
  const selMap = result?.maps?.[selMapId] || result?.maps?.[mapIds[0]] as ScriptMap | undefined;
  const validation = result?.generator?.validation;
  const warps = (result?.connections || []).filter(c => c.type === 'warp');

  const hint = useMemo(() => currentKind?.hint, [currentKind]);

  return (
    <div className="modal-mask" onClick={onClose}>
      <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 880, width: '94%', maxHeight: '88vh', overflowY: 'auto' }}>
        <div className="modal-head">
          <div className="modal-title">🏰 大型地图（一般模式 2D 探索）</div>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        <div className="chip-row">
          {KINDS.map(k => (
            <button
              key={k.key}
              className={`chip2 ${kind === k.key ? 'active' : ''}`}
              onClick={() => { setMap({ kind: k.key }); setResult(null); setSelMapId(''); setSaved(false); }}
            >
              {k.icon} {k.label}
            </button>
          ))}
        </div>
        <div className="hint" style={{ marginTop: 6, marginBottom: 12 }}>{hint}</div>

        <div className="field"><label>🗺️ 结构主题 *</label>
          <input value={theme} onChange={e => setTheme(e.target.value)} placeholder="例如：晨曦城堡 / 雾隐研究所 / 边缘城老城区" />
        </div>
        <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <label style={{ margin: 0, minWidth: 90 }}>🎲 种子</label>
          <input style={{ flex: 1 }} value={mapGen.seed} onChange={e => setMap({ seed: e.target.value })} placeholder="留空 = 随机（响应回传种子可复现）" />
          <label style={{ margin: 0, minWidth: 90 }}>🎨 风格</label>
          <select style={{ flex: 1 }} value={mapGen.style} onChange={e => setMap({ style: e.target.value })}>
            <option value="随剧本风格">随剧本风格</option><option value="幻想">幻想</option><option value="现实">现实</option><option value="科幻">科幻</option><option value="古风">古风</option>
          </select>
        </div>
        <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <label style={{ margin: 0, minWidth: 90 }}>📐 地图尺寸</label>
          <input style={{ width: 90 }} type="number" min={16} max={256} value={mapGen.width} onChange={e => setMap({ width: Number(e.target.value) })} />
          <span>×</span>
          <input style={{ width: 90 }} type="number" min={16} max={256} value={mapGen.height} onChange={e => setMap({ height: Number(e.target.value) })} />
          <span className="hint" style={{ margin: 0 }}>单位：格，最大 256×256</span>
        </div>
        <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <label style={{ margin: 0, minWidth: 90 }}>🔀 地图模式</label>
          <select value={mapGen.mapMode} onChange={e => setMap({ mapMode: e.target.value })}>
            <option value="single">单图探索</option><option value="multi">多图 + 传送连接</option><option value="exterior">外部 + 内部</option>
          </select>
          <span className="hint">生成后可在结果中切换地图</span>
        </div>
        <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <input type="checkbox" checked={mapGen.audit} onChange={e => setMap({ audit: e.target.checked })} style={{ width: 'auto' }} />
          <label style={{ margin: 0 }}>🔍 视觉审核（AI 看图检查布局，发现问题自动调整房间间距后重生成）</label>
        </div>

        <div style={{ marginTop: 10 }}>
          <button className="btn2 btn2-primary" onClick={runGenerate} disabled={generating}>
            {generating ? '🔄 大型地图生成中…' : '✨ 生成大型地图'}
          </button>
          {generating && <span className="hint" style={{ marginLeft: 10 }}>L0 语义 → L1 布局 → 校验（约 1-45 秒）</span>}
        </div>
        {error && <div className="gen-error-note" style={{ marginTop: 8 }}>⚠️ {error}</div>}

        {result && (
          <>
            <div className="gen-step-head" style={{ marginTop: 16 }}>
              <span className="gen-step-icon">🗺️</span>
              <span className="gen-step-title">生成结果 · {result.structure?.name || theme}</span>
              <div className="chip-row" style={{ marginLeft: 'auto', marginBottom: 0 }}>
                <button className="chip2 active">kind={result.generator?.kind}</button>
                <button className="chip2 active">l0={result.generator?.l0}</button>
                <button className="chip2 active">模式={result.generator?.map_mode}</button>
                <button className="chip2 active">seed={result.generator?.seed}</button>
              </div>
            </div>

            {result.fallback && result.fallback.length > 0 && (
              <div className="gen-error-note">已降级 BSP 兜底：{result.fallback.join('；')}</div>
            )}
            {validation && !validation.ok && (
              <div className="gen-error-note">校验失败：{(validation.errors || []).join('；')}</div>
            )}

            {mapIds.length > 0 && (
              <div className="chip-row" style={{ marginTop: 10 }}>
                {mapIds.map(id => (
                  <button
                    key={id}
                    className={`chip2 ${selMapId === id ? 'active' : ''}`}
                    onClick={() => setSelMapId(id)}
                  >
                    🗺️ {id}
                  </button>
                ))}
                {validation?.ok && (
                  <span className="hint" style={{ marginLeft: 'auto', marginBottom: 0 }}>
                    ✅ 校验通过 · {mapIds.length} 图 · {warps.length} 传送
                  </span>
                )}
              </div>
            )}
            {selMap && (
              <div style={{ padding: 10 }}>
                <PhaserScriptMapView
                  map={selMap}
                  playerName=""
                  readOnly
                  fetchAssets
                  title={`🗺️ ${result.structure?.name || theme} · ${selMapId || mapIds[0]}（Phaser 渲染 · 只读预览）`}
                  aiCharacters={[]}
                  maps={result.maps}
                  onMapChange={id => setSelMapId(id)}
                />
              </div>
            )}

            <div style={{ marginTop: 12, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
              <button
                className="btn2"
                disabled={!selMap}
                onClick={() => { if (selMap) { onSave(selMap as ScriptMap); setSaved(true); } }}
              >
                💾 保存地图
              </button>
              <button
                className="btn2 btn2-primary"
                disabled={!selMap || mapIds.length !== 1}
                onClick={() => { if (selMap) onUse(selMap as ScriptMap); }}
              >
                ✅ 保存并进入 2D 探索
              </button>
              {saved && <span className="hint" style={{ margin: 0, color: 'var(--color-accent-cyan)' }}>已保存到当前场景，之后进入 2D 不会重新生成。</span>}
              <span className="hint" style={{ margin: 0 }}>
                {mapIds.length === 1 ? '将缓存当前单图，进入 2D 探索时直接复用。' : '当前结果包含多张地图，暂不能进入一般模式 2D；请提高单图预算或选择较小结构。'}
              </span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
