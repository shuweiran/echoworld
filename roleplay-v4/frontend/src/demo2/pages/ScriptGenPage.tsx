/**
 * ScriptGenPage.tsx — 剧本生成（主页面 4）
 *
 * 顶部：剧本杀模式 / 一般模式 切换。
 * 剧本杀：AI 生成剧本（主题/世界背景/人数/剧情方向/类型 + 同步生成角色开关）、导入剧本。
 * 一般：自己描述 / AI 生成描述 + 同步生成 2D 地图 + 同步生成角色。
 */
import { useState } from 'react';
import { useDemoStore } from '../store';
import {
  mockGenerateMurder, mockGenerateGeneral, mockGenerateSceneDesc, parseImportText,
} from '../mockData';
import { api } from '../../api/client';
import { assembleGeneralScript, v1RoleToRoleCard, v1ScriptToMurder } from '../mappers';
import { AiGenBox, type AiGenResult } from '../components/AiGenBox';

export function ScriptGenPage() {
  const mode = useDemoStore(s => s.mode);
  const setMode = useDemoStore(s => s.setMode);
  const setGeneratedMurder = useDemoStore(s => s.setGeneratedMurder);
  const setGeneratedGeneral = useDemoStore(s => s.setGeneratedGeneral);
  const addGenRoles = useDemoStore(s => s.addGenRoles);
  const enterRoles = useDemoStore(s => s.enterRoles);

  const [tab, setTab] = useState<'ai' | 'import'>('ai');
  const [theme, setTheme] = useState('民国旧宅凶案');
  const [background, setBackground] = useState('');
  const [playerCount, setPlayerCount] = useState(5);
  const [direction, setDirection] = useState('查明真相');
  const [genre, setGenre] = useState('悬疑');
  const [syncRoles, setSyncRoles] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [progress, setProgress] = useState('');
  // P-0811-E：LLM 生成失败回退 mock 时的可见提示（存 store，跳转角色选择页后仍可见）
  const genNotice = useDemoStore(s => s.genNotice);
  const setGenNotice = useDemoStore(s => s.setGenNotice);

  // 一般模式
  const [genMode, setGenMode] = useState<'manual' | 'ai'>('manual');
  const [sceneName, setSceneName] = useState('');
  const [descText, setDescText] = useState('');
  const [syncMap, setSyncMap] = useState(true);
  const [syncRolesGen, setSyncRolesGen] = useState(true);

  const [importText, setImportText] = useState('');
  const [importErr, setImportErr] = useState('');

  // P-0811-E：剧本杀「AI 生成剧本」接真实后端 LLM（POST /api/session/script/generate，Schema v1），
  // 失败/超时回退 mockGenerateMurder + 可见提示；成功映射为 demo2 MurderScript 并进入角色选择。
  const runMurderGen = async () => {
    if (generating) return;
    setGenerating(true);
    setProgress('AI 正在构思剧情大纲…');
    setGenNotice('');
    let s: ReturnType<typeof mockGenerateMurder>;
    try {
      const v1 = await api.scriptGen(theme, Array.from({ length: playerCount }, (_, i) => `玩家${i + 1}`));
      s = v1ScriptToMurder(v1, { background, direction, genre });
    } catch (e: any) {
      s = mockGenerateMurder({ theme, background, playerCount, direction, genre });
      setGenNotice(`LLM 生成失败，已用本地模板兜底。（原因：${String(e?.message || '未知错误')}）`);
    }
    setGenerating(false);
    setProgress('');
    setGeneratedMurder(s);
    if (syncRoles) addGenRoles(s.roles.map(r => ({ ...r, source: 'ai' as const })));
    // 生成成功直接进入角色选择页
    enterRoles({ kind: 'murder', scriptId: s.id });
  };

  const runImport = () => {
    setImportErr('');
    const r = parseImportText(importText);
    if (!r.ok) { setImportErr(r.error); return; }
    setGeneratedMurder(r.script);
    // 导入成功直接进入角色选择页
    enterRoles({ kind: 'murder', scriptId: r.script.id });
  };

  // P-0811-E：一般模式「AI 生成场景」接真实后端 LLM（POST /api/scenes/generate →
  // {name, description, roles[]}——场景+配套角色一次生成，角色后端已自动落库），
  // 失败兜底 mockGenerateGeneral；成功：LLM 场景名/描述 + 配套角色装配 GeneralScript（角色为真实 LLM 产物）。
  const generalAiGen = async (prompt: string): Promise<AiGenResult> => {
    let g: ReturnType<typeof mockGenerateGeneral>;
    let fellBack = false;
    let roleCount = 0;
    try {
      const r = await api.generateScene(prompt.trim() || '自定义世界');
      const name = String(r?.name || prompt.trim() || '自定义世界');
      const desc = String(r?.description || mockGenerateSceneDesc(prompt));
      // P-0811-E（追加）：场景配套角色（后端已自动落库）→ 映射 RoleCard → 作为新场景角色列表（替代预置占位角色）
      const roleList = Array.isArray(r?.roles) ? r.roles : [];
      roleCount = roleList.length;
      const llmRoles = roleList.map((x: any, i: number) => v1RoleToRoleCard(x, i, []));
      g = assembleGeneralScript(name, desc, llmRoles);
    } catch (e: any) {
      fellBack = true;
      g = mockGenerateGeneral(prompt.trim() || '自定义世界', mockGenerateSceneDesc(prompt));
      setGenNotice(`LLM 生成失败，已用本地模板兜底。（原因：${String(e?.message || '未知错误')}）`);
    }
    setGeneratedGeneral(g);
    if (syncRolesGen) addGenRoles(g.roles.map(r => ({ ...r, source: 'ai' as const })));
    // 生成成功直接进入角色选择页
    enterRoles({ kind: 'general', scriptId: g.id });
    return {
      text: `已生成「${g.title}」世界：世界背景、场景介绍、角色关系${roleCount > 0 ? `、${roleCount} 个配套角色` : ''}${syncMap ? '、2D 地图' : ''}${syncRolesGen ? '、角色已同步' : ''}。${fellBack ? '（LLM 生成失败，已用本地模板兜底）' : ''}`,
      data: g,
    };
  };

  // P-0811-E：手动输入路径——用户自写场景（导入逻辑保留：用户描述不被 LLM 覆盖）；
  // 仅当描述留空时调 api.generateScene 补一段 LLM 描述（失败回退模板）。
  const runManualGeneralGen = async () => {
    const desc = sceneName.trim() || '自定义场景';
    let text = descText.trim();
    if (!text) {
      try {
        const r = await api.generateScene(desc);
        text = String(r?.description || `「${desc}」场景已创建。`);
      } catch (e: any) {
        text = `「${desc}」场景已创建。`;
        setGenNotice(`LLM 生成失败，已用本地模板兜底。（原因：${String(e?.message || '未知错误')}）`);
      }
    }
    const g = assembleGeneralScript(desc, text);
    setGeneratedGeneral(g);
    if (syncRolesGen) addGenRoles(g.roles.map(r => ({ ...r, source: 'ai' as const })));
    // 生成成功直接进入角色选择页
    enterRoles({ kind: 'general', scriptId: g.id });
  };

  return (
    <div>
      <div className="page-head">
        <h2>🪄 剧本生成</h2>
        <span className="page-sub">创造新的世界与冒险。</span>
        <div className="chip-row" style={{ marginLeft: 'auto', marginBottom: 0 }}>
          <button className={`chip2 ${mode === 'murder' ? 'active' : ''}`} onClick={() => { setMode('murder'); setTab('ai'); }}>🕵️ 剧本杀模式</button>
          <button className={`chip2 ${mode === 'general' ? 'active' : ''}`} onClick={() => { setMode('general'); setTab('ai'); }}>🌄 一般模式</button>
        </div>
      </div>

      <div style={{ maxWidth: 720 }}>
        <div className="card2">
          {mode === 'murder' ? (
            <>
              <div className="chip-row">
                <button className={`chip2 ${tab === 'ai' ? 'active' : ''}`} onClick={() => setTab('ai')}>✨ AI 生成剧本</button>
                <button className={`chip2 ${tab === 'import' ? 'active' : ''}`} onClick={() => setTab('import')}>📥 导入剧本</button>
              </div>

              {tab === 'ai' ? (
                <div>
                  <div className="gen-step-head"><span className="gen-step-icon">🌌</span><span className="gen-step-title">AI 生成剧本</span></div>
                  <div className="field"><label>🎭 剧本主题</label><input value={theme} onChange={e => setTheme(e.target.value)} /></div>
                  <div className="field"><label>🌍 世界背景</label><textarea rows={3} value={background} onChange={e => setBackground(e.target.value)} placeholder="可选：描述这个世界…" /></div>
                  <div className="field"><label>👥 人数要求</label>
                    <select value={playerCount} onChange={e => setPlayerCount(Number(e.target.value))}>
                      {[4, 5, 6, 7, 8].map(n => <option key={n} value={n}>{n} 人</option>)}
                    </select>
                  </div>
                  <div className="field"><label>🧭 剧情方向</label><input value={direction} onChange={e => setDirection(e.target.value)} placeholder="例如：查明真相 / 找到卧底 / 阻止阴谋" /></div>
                  <div className="field"><label>📖 类型</label><input value={genre} onChange={e => setGenre(e.target.value)} placeholder="例如：悬疑 / 古风 / 科幻" /></div>
                  <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={syncRoles} onChange={e => setSyncRoles(e.target.checked)} style={{ width: 'auto' }} />
                    <label style={{ margin: 0 }}>同步生成角色（生成完成后角色直接进入角色库）</label>
                  </div>
                  <div style={{ marginTop: 8 }}>
                    <button className="btn2 btn2-primary" onClick={runMurderGen} disabled={generating}>
                      {generating ? '生成中…' : '✨ 生成剧本'}
                    </button>
                    {generating && <span className="hint" style={{ marginLeft: 10 }}>{progress}</span>}
                    {genNotice && <div className="gen-error-note" style={{ marginTop: 8 }}>{genNotice}</div>}
                  </div>
                  <div className="hint" style={{ marginTop: 12 }}>AI 将生成：剧本名称 / 世界观 / 剧情流程 / 角色关系 / 角色信息 / 线索 / 结局。</div>
                </div>
              ) : (
                <div>
                  <div className="gen-step-head"><span className="gen-step-icon">📥</span><span className="gen-step-title">导入剧本</span></div>
                  <div className="field">
                    <label>粘贴剧本内容（文本格式）</label>
                    <textarea rows={9} value={importText} onChange={e => setImportText(e.target.value)} placeholder={'示例：\n标题: 我的剧本\n背景: 城郊别墅发生命案…\n剧情: 搜证→讨论→指认\n真相: 管家所为\n凶手: 张三\n角色: 张三|管家|偷了主人的怀表\n角色: 李四|访客|与死者有旧怨'} />
                  </div>
                  {importErr && <div className="gen-error-note">{importErr}</div>}
                  <button className="btn2 btn2-primary" onClick={runImport}>🔍 解析剧本</button>
                </div>
              )}
            </>
          ) : (
            <>
              <div className="gen-step-head">
                <span className="gen-step-icon">🖋️</span>
                <span className="gen-step-title">一般模式生成（场景）</span>
                <div className="chip-row" style={{ marginLeft: 'auto', marginBottom: 0 }}>
                  <button className={`chip2 ${genMode === 'manual' ? 'active' : ''}`} onClick={() => setGenMode('manual')}>✍️ 手动输入</button>
                  <button className={`chip2 ${genMode === 'ai' ? 'active' : ''}`} onClick={() => setGenMode('ai')}>✨ AI 生成</button>
                </div>
              </div>

              {genMode === 'manual' ? (
                <div>
                  <div className="field"><label>🏷️ 场景名称 *</label>
                    <input value={sceneName} onChange={e => setSceneName(e.target.value)} placeholder="例如：雨夜的旧书店" />
                  </div>
                  <div className="field"><label>🌍 场景描述 *</label>
                    <textarea rows={4} value={descText} onChange={e => setDescText(e.target.value)} placeholder="描述这个世界：场景、氛围、人物关系…" />
                  </div>
                  <button className="btn2 btn2-primary" disabled={!sceneName.trim()} onClick={runManualGeneralGen}>🪄 生成世界</button>
                </div>
              ) : (
                <div>
                  <AiGenBox
                    title="🤖 AI 生成场景"
                    placeholder="输入主题，例如：校园 / 科幻 / 奇幻 / 古代 / 民国咖啡馆…"
                    hint="AI 将生成世界背景 / 场景介绍 / 角色关系，并可视开关同步 2D 地图与角色。"
                    stages={['AI 正在生成场景与配套角色…', '正在生成场景介绍…', '正在编排角色关系…']}
                    generate={generalAiGen}
                    onResult={() => { /* 结果已直接生成世界 */ }}
                  />
                </div>
              )}

              <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 14 }}>
                <input type="checkbox" checked={syncMap} onChange={e => setSyncMap(e.target.checked)} style={{ width: 'auto' }} />
                <label style={{ margin: 0 }}>同步生成 2D 地图（自动创建地图区域/建筑/场景布局/NPC 位置并绑定该世界）</label>
              </div>
              <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" checked={syncRolesGen} onChange={e => setSyncRolesGen(e.target.checked)} style={{ width: 'auto' }} />
                <label style={{ margin: 0 }}>同步生成角色（角色直接进入角色库，剧本杀/一般互通）</label>
              </div>
              {genNotice && <div className="gen-error-note" style={{ marginTop: 8 }}>{genNotice}</div>}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
