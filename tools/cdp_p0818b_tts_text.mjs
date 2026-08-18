/* cdp_p0818b_tts_text.mjs — P-0818-B 端到端：Gal 界面点击 🎙 后，TTS 合成文本只含语句不含括号内容
 * 前置：node tools/static_proxy_p0818b.mjs（4194 伺服新 dist + /api 透传 8000 + 捕获合成请求体）；
 *       8000 后端运行中。
 * 路径：首页 → 剧本选择 → 一般模式 → 街角咖啡馆 → 进入对局 → Gal AI 消息 🎙 → 点击 → 播放完成
 * 断言：/__tts_bodies 至少 1 条 synthesize 请求，其 body.text 不含 （）【】且非空。
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9250;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/roleplay-java/tmp/p0818b';
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

async function waitFor(cdp, expr, t = 30000, label = '', step = 300) {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(step);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}
const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);

async function launchEdge() {
  const edge = EDGE_CANDIDATES.find(p => { try { return existsSync(p); } catch { return false; } });
  if (!edge) throw new Error('Edge not found');
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-p0818b-' + Date.now(), 'about:blank',
  ], { stdio: 'ignore', detached: true });
  child.unref();
  for (let i = 0; i < 40; i++) {
    try { const r = await fetch(`${BASE}/json/version`); if (r.ok) return; } catch { }
    await sleep(500);
  }
  throw new Error('Edge CDP not ready');
}

const main = async () => {
  await launchEdge();
  const tab = await (await fetch(`${BASE}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json();
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 30000, '首页');
  // 页面加载后注入 fetch 钩子，捕获 TTS 合成请求体（SPA 后续无整页导航，钩子保留）
  await cdp.eval(`(() => {
    window.__ttsBodies = [];
    const of = window.fetch.bind(window);
    window.fetch = (...a) => {
      const u = String(a[0] || '');
      if (u.includes('/api/tts/mimo/synthesize')) {
        try { window.__ttsBodies.push({ url: u, body: (a[1] && a[1].body) || '' }); } catch { }
      }
      return of(...a);
    };
    return true;
  })()`);
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('一般模式'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('街角咖啡馆'))`, 30000, '一般模式列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('街角咖啡馆'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 120000, 'Gal 视图');
  // 等「对话框文本完整（>5 字）且 TTS 按钮存在」——避开流式切换瞬间（按钮与文本同属当前消息）
  await waitFor(cdp, `(() => {
    const t = document.querySelector('.gal-dialog-text');
    const b = document.querySelector('.tts-btn.tts-btn-gal');
    return b && t && (t.textContent || '').trim().length > 5;
  })()`, 180000, 'Gal AI 消息完成态（完整文本 + 按钮）');
  const msgText = await cdp.eval(`(() => {
    const t = document.querySelector('.gal-dialog-text');
    const b = document.querySelector('.tts-btn.tts-btn-gal');
    return { text: t ? t.textContent.trim() : '', btnTitle: b?.title || '', btnCls: b?.className || '' };
  })()`);
  log('Gal 消息原文: ' + JSON.stringify(msgText.text));
  log('按钮态: ' + JSON.stringify(msgText.btnTitle) + ' / ' + JSON.stringify(msgText.btnCls));
  await click(cdp, `document.querySelector('.tts-btn.tts-btn-gal')`);
  await waitFor(cdp, `(() => { const b = document.querySelector('.tts-btn.tts-btn-gal'); return b && b.className.includes('tts-playing'); })()`, 60000, '播放中');
  // 等播放完成（idle）后读捕获体
  await waitFor(cdp, `(() => { const b = document.querySelector('.tts-btn.tts-btn-gal'); return b && !b.className.includes('tts-playing') && !b.className.includes('tts-loading'); })()`, 60000, '播放完成');
  await cdp.shot('01-tts-done.png');

  const bodies = await cdp.eval(`window.__ttsBodies || []`);
  log('捕获合成请求数: ' + bodies.length);
  const req = bodies[bodies.length - 1];
  let parsed = null;
  try { parsed = req ? JSON.parse(req.body) : null; } catch { }
  const speechText = parsed?.text ?? '';
  log('合成文本: ' + JSON.stringify(speechText));
  const original = msgText.text || '';
  const onlyBrackets = /^[（）()【】[\]]*\s*$/.test(original) || original.trim() === '';
  if (onlyBrackets) {
    // 原文只含括号/无语句 → 正确行为是不合成（B1）且无请求（B2）
    check('B1 纯括号消息不朗读（0 条合成请求）', bodies.length === 0, 'count=' + bodies.length + ' last=' + JSON.stringify(speechText));
    check('B2 无残留括号文本进合成', bodies.length === 0, JSON.stringify(bodies.map(b => b.body)));
  } else {
    check('B1 已捕获 ≥1 条 TTS 合成请求', bodies.length >= 1, JSON.stringify(bodies.map(b => b.url)));
    check('B2 合成文本非空', speechText.length > 0, 'len=' + speechText.length);
    check('B3 合成文本不含全角圆括号', !/[（）]/.test(speechText), JSON.stringify(speechText));
    check('B4 合成文本不含全角方括号', !/[【】]/.test(speechText), JSON.stringify(speechText));
    check('B5 合成文本不含半角括号组', !/\([^)]*\)/.test(speechText), JSON.stringify(speechText));
  }
  // 原文含括号内容（且有语句）→ 合成文本应比原文短（确实被剥离）
  if (!onlyBrackets && /[（【(]/.test(original)) {
    check('B6 原文含括号内容 → 合成文本已剥离变短', speechText.length < original.length,
      'msg=' + original.length + ' speech=' + speechText.length);
  } else {
    check('B6 原文无括号/纯括号（跳过剥离长度比较）', true, 'msg=' + JSON.stringify(original));
  }

  const errs = cdp.consoleLogs.filter(l => !/favicon|icon\.svg|\.png|net::ERR/i.test(l));
  check('B7 console 无新增错误', errs.length === 0, errs.slice(0, 5).join(' | '));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, msgText, speechText, bodies, errors: cdp.consoleLogs.slice(0, 10) }, null, 2));
  log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
  try { await cdp.send('Page.close'); } catch { }
  process.exit(fail > 0 ? 1 : 0);
};

main().catch(e => { console.error('FATAL', e); process.exit(2); });
