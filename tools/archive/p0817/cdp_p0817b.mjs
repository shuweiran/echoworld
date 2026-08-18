/* cdp_p0817b.mjs — 角色卡详情页 TTS 声线设置走查（P-0817-B）
 * 前置：python http.server 4499 伺服 frontend/dist（无后端 → TTS 合成/角色更新走失败降级路径验证 wiring）
 *       Edge CDP 9222 已启动（user-data-dir tmp/edge-p0817b）
 * 主流程（editable）：首页 → 角色库 → 一般模式剧本 → 角色卡 → 详情页
 *    T1-T5 区块渲染 / T6-T7 模式切换 placeholder / T8 保存降级提示 / T9 localStorage 持久化 / T10 试听 wiring
 * 副流程（readonly）：返回 → 剧本杀剧本 → 默认角色卡 → 详情页 R1-R2 只读摘要
 * 输出：tmp/p0817b/（截图 + progress.log + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4499/';
const OUT = 'D:/roleplay-java/tmp/p0817b';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
      else if (m.method === 'Runtime.consoleAPICalled') { if (m.params.type === 'error') c.consoleLogs.push('ERROR: ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 300)); }
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 300));
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 30000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}
async function newTab(url) {
  let tab;
  try { const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' }); tab = await r.json(); }
  catch (e) { const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`); tab = await r.json(); }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  return cdp;
}
async function waitFor(cdp, expr, t = 30000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(700);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}
const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);

const main = async () => {
  const cdp = await newTab(APP);
  // 清 localStorage（防上轮残留）后重载
  await cdp.eval(`localStorage.clear(); true`);
  await cdp.eval(`location.reload(); true`);
  await sleep(2000);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '首页');
  // 拦截 fetch 记录角色更新/TTS 请求（无后端 → 预期失败降级）—— 必须在 reload 后注入
  await cdp.eval(`(() => { window.__p0817b = { charUpd: [], tts: [] }; const of = window.fetch.bind(window); window.fetch = (...a) => { const u = String(a[0] || ''); const m = String((a[1]?.method) || 'GET'); if (u.includes('/api/characters/')) window.__p0817b.charUpd.push(m + ' ' + u); if (u.includes('/api/tts/mimo')) window.__p0817b.tts.push(m + ' ' + u); return of(...a); }; return true; })()`);

  // 进入角色库
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('角色库'))`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡管理')`, 20000, '角色库页');
  await cdp.shot('S0-roles-lib.png');

  // 选一般模式第一个剧本（label 的 nextElementSibling 即第一个 general script-chip 按钮）→ 点第一个角色卡（general 默认角色 = editable）
  const genClicked = await click(cdp, `[...document.querySelectorAll('.lib-mode-label')].find(l => l.textContent.includes('一般模式')).nextElementSibling`);
  log('general chip clicked: ' + genClicked);
  await sleep(1000);
  const libState = await cdp.eval(`(() => ({ title: document.querySelector('.roles-col .panel-col-title')?.textContent?.trim() || '', chips: [...document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)')].map(c => c.textContent.trim()).slice(0, 5) }))()`);
  log('lib state after general click: ' + JSON.stringify(libState));
  await click(cdp, `document.querySelector('.role-chips .role-chip:not(.role-chip-add)')`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡详情')`, 20000, '详情页');
  await sleep(500);
  const detailKind = await cdp.eval(`(() => ({ hasSel: !!document.querySelector('.role-detail select'), readonlyNote: document.body.textContent.includes('设定只读') }))()`);
  log('detail kind: ' + JSON.stringify(detailKind));
  await cdp.shot('S1-detail-general.png');

  // T1 区块标题
  const t1 = await cdp.eval(`(() => { const el = [...document.querySelectorAll('div')].find(d => d.textContent.trim() === '🎙️ 声线设置（MiMo TTS）'); return !!el; })()`);
  check('T1 详情页渲染「声线设置（MiMo TTS）」区块', t1);

  // T2 select 三模式
  const t2 = await cdp.eval(`(() => { const sel = [...document.querySelectorAll('.role-detail select')].find(s => [...s.options].some(o => o.value === 'basic')); if (!sel) return null; return { count: sel.options.length, opts: [...sel.options].map(o => o.value) }; })()`);
  check('T2 声线模式 select 含 basic/clone/design', !!t2 && t2.count === 3 && t2.opts.join(',') === 'basic,clone,design', JSON.stringify(t2));

  // T3 input + basic placeholder
  const t3 = await cdp.eval(`(() => { const inp = [...document.querySelectorAll('.role-detail input')].find(i => i.placeholder.includes('内置音色名')); return inp ? inp.placeholder : ''; })()`);
  check('T3 声线数据 input（basic 占位提示）', !!t3 && t3.includes('内置音色名'), t3);

  // T4 试听按钮
  const t4 = await cdp.eval(`(() => { const btns = [...document.querySelectorAll('.role-detail button')].map(b => b.textContent.trim()); return btns.filter(t => t.includes('试听')).length > 0; })()`);
  check('T4 🔊 试听按钮存在', t4);

  // T5 保存按钮
  const t5 = await cdp.eval(`(() => [...document.querySelectorAll('.role-detail button')].some(b => b.textContent.includes('保存声线')))()`);
  check('T5 💾 保存声线按钮存在', t5);

  // T6 切 clone → placeholder 变
  await cdp.eval(`(() => { const sel = [...document.querySelectorAll('.role-detail select')].find(s => [...s.options].some(o => o.value === 'basic')); if (!sel) return false; sel.value = 'clone'; sel.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
  await sleep(400);
  const t6 = await cdp.eval(`(() => { const inp = [...document.querySelectorAll('.role-detail input')].find(i => i.placeholder.includes('参考音频')); return !!inp; })()`);
  check('T6 切换 clone 后 placeholder 变参考音频', t6);

  // T7 切 design → placeholder 变
  await cdp.eval(`(() => { const sel = [...document.querySelectorAll('.role-detail select')].find(s => [...s.options].some(o => o.value === 'basic')); if (!sel) return false; sel.value = 'design'; sel.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
  await sleep(400);
  const t7 = await cdp.eval(`(() => { const inp = [...document.querySelectorAll('.role-detail input')].find(i => i.placeholder.includes('音色描述')); return !!inp; })()`);
  check('T7 切换 design 后 placeholder 变音色描述', t7);
  await cdp.shot('S2-design-mode.png');

  // T8 输入 voice_data → 保存 → 降级提示（后端角色库无此角色）
  await cdp.eval(`(() => { const inp = [...document.querySelectorAll('.role-detail input')].find(i => i.placeholder.includes('音色描述')); if (!inp) return false; const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set; setter.call(inp, '低沉磁性男声，略带沙哑'); inp.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await sleep(300);
  await click(cdp, `[...document.querySelectorAll('.role-detail button')].find(b => b.textContent.includes('保存声线'))`);
  await waitFor(cdp, `document.body.textContent.includes('后端角色库无此角色')`, 15000, '保存降级提示');
  const t8 = await cdp.eval(`(() => { const req = window.__p0817b.charUpd.filter(u => u.includes('PUT')); return { hit: req.length, req: req[0] || '' }; })()`);
  check('T8 保存：本地保存 + 后端角色库降级提示', t8.hit >= 1, JSON.stringify(t8));

  // T9 localStorage 持久化（自由角色库写入 voice_mode/voice_data）
  const t9 = await cdp.eval(`(() => { try { const raw = localStorage.getItem('roleplay_demo2_free_roles_v1') || ''; return raw.includes('voice_mode') && raw.includes('voice_data') && raw.includes('低沉磁性男声'); } catch (e) { return 'ERR ' + e.message; } })()`);
  check('T9 localStorage 已持久化 voice_mode/voice_data', t9 === true, String(t9));
  await cdp.shot('S3-saved.png');

  // T10 试听 wiring：点击 → 请求发出 → 无后端立即失败 → ⚠️ 失败（按钮状态流转证明调用链通）
  const t10Before = await cdp.eval(`(() => [...document.querySelectorAll('.role-detail button')].find(b => b.textContent.includes('试听'))?.textContent.trim() || '')()`);
  await click(cdp, `[...document.querySelectorAll('.role-detail button')].find(b => b.textContent.includes('试听'))`);
  await waitFor(cdp, `window.__p0817b.tts.length >= 1`, 8000, 'TTS 请求发出');
  await waitFor(cdp, `[...document.querySelectorAll('.role-detail button')].some(b => b.textContent.includes('失败'))`, 15000, '试听失败降级');
  const t10 = await cdp.eval(`(() => { const btns = [...document.querySelectorAll('.role-detail .btn2')].map(b => b.textContent.trim()); return { ttsReq: window.__p0817b.tts.length, hasFail: btns.some(t => t.includes('失败')), hasPlay: btns.some(t => t.includes('▶ 试听')) }; })()`);
  check('T10 试听：点击发出 TTS 请求 → 无后端失败降级（⚠️ 失败）', t10.ttsReq >= 1 && t10.hasFail && !t10.hasPlay, JSON.stringify({ before: t10Before, ...t10 }));

  // 副流程：返回 → 剧本杀第一个剧本 → 默认角色卡 → 只读摘要
  await click(cdp, `[...document.querySelectorAll('.role-detail button')].find(b => b.textContent.includes('返回'))`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡管理')`, 20000, '回角色库');
  await click(cdp, `[...document.querySelectorAll('.lib-mode-label')].find(l => l.textContent.includes('剧本杀模式')).nextElementSibling.querySelector('button.script-chip')`);
  await sleep(800);
  await click(cdp, `document.querySelector('.role-chips .role-chip:not(.role-chip-add)')`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡详情')`, 20000, '详情页(murder)');
  const r1 = await cdp.eval(`(() => ({ hasSel: [...document.querySelectorAll('.role-detail select')].some(s => [...s.options].some(o => o.value === 'basic')), hasSave: [...document.querySelectorAll('.role-detail button')].some(b => b.textContent.includes('保存声线')), cur: document.body.textContent.includes('当前声线'), unset: document.body.textContent.includes('未配置（使用默认音色）') }))()`);
  check('R1 剧本杀默认角色只读：无编辑控件', !r1.hasSel && !r1.hasSave, JSON.stringify(r1));
  check('R2 只读摘要：当前声线 + 未配置提示', r1.cur && r1.unset, JSON.stringify(r1));
  await cdp.shot('S5-detail-murder-readonly.png');

  // E1 console 新增错误（预期无；fetch 404 均被业务 catch）
  const errs = cdp.consoleLogs.filter(l => !l.includes('Failed to load resource') && !l.includes('favicon'));
  check('E1 console 无新增错误/异常', errs.length === 0, JSON.stringify(errs.slice(0, 5)));

  // 收尾
  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, at: new Date().toISOString() }, null, 2));
  log(`\n==== RESULT: ${pass} PASS / ${fail} FAIL ====`);
  try { await cdp.send('Page.close'); } catch { }
};

main().then(() => process.exit(fail > 0 ? 1 : 0)).catch(e => { console.error('FATAL', e); process.exit(2); });
