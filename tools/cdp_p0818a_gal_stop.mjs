/* cdp_p0818a_gal_stop.mjs — P-0818-A 补充：Gal 界面 TTS「点击播放 → 再点停止」真机验证
 * 路径：首页 → 剧本选择 → 一般模式 → 街角咖啡馆 → 进入对局（真实后端 + 真实 LLM）
 * 验证：Gal 视图 AI 消息 🎙 → 点击 → loading → playing（⏹ 停止播放）→ 再点 → 立即回 idle（🎙 播放语音）
 * 输出：tmp/p0818a_gal_stop/
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9249;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/echoworld/tmp/p0818a_gal_stop';
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
    '--user-data-dir=C:\\Temp\\edge-p0818a-stop-' + Date.now(), 'about:blank',
  ], { stdio: 'ignore', detached: true });
  child.unref();
  for (let i = 0; i < 40; i++) {
    try { const r = await fetch(`${BASE}/json/version`); if (r.ok) return; } catch { }
    await sleep(500);
  }
  throw new Error('Edge CDP not ready');
}

const btnState = (cdp) => cdp.eval(`(() => {
  const b = document.querySelector('.tts-btn.tts-btn-gal') || document.querySelector('.tts-btn');
  if (!b) return null;
  return { cls: b.className, title: b.title || '', text: (b.textContent || '').trim() };
})()`);

const main = async () => {
  await launchEdge();
  const tab = await (await fetch(`${BASE}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json();
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 30000, '首页');
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('一般模式'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('街角咖啡馆'))`, 30000, '一般模式列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('街角咖啡馆'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 120000, 'Gal 视图');
  await waitFor(cdp, `!!document.querySelector('.tts-btn.tts-btn-gal')`, 180000, 'Gal AI 消息完成态按钮');
  const idle0 = await btnState(cdp);
  check('S1 初始为可播放态（🎙 播放语音）', String(idle0?.title).includes('播放语音') && String(idle0?.text) === '🎙', JSON.stringify(idle0));
  await cdp.shot('s1-idle.png');

  // 第一次点击 → 等待 playing
  await click(cdp, `document.querySelector('.tts-btn.tts-btn-gal')`);
  const loading = await waitFor(cdp, `(() => { const b = document.querySelector('.tts-btn.tts-btn-gal'); return b && b.className.includes('tts-loading') ? { cls: b.className, title: b.title } : null; })()`, 10000, 'loading');
  check('S2 点击后进入合成中（⏳ tts-loading）', !!loading, JSON.stringify(loading));
  await waitFor(cdp, `(() => { const b = document.querySelector('.tts-btn.tts-btn-gal'); return b && b.className.includes('tts-playing') ? { cls: b.className, title: b.title } : null; })()`, 45000, 'playing');
  const playing = await btnState(cdp);
  check('S3 合成完成进入播放中（⏹ 停止播放）', String(playing?.title).includes('停止') && String(playing?.text) === '⏹', JSON.stringify(playing));
  await cdp.shot('s2-playing.png');

  // 第二次点击 → 应立即回 idle（停止输出）
  await click(cdp, `document.querySelector('.tts-btn.tts-btn-gal')`);
  await sleep(700);
  const after = await btnState(cdp);
  check('S4 再点立即停止（回 🎙 播放语音，无 tts-playing）', String(after?.title).includes('播放语音') && !String(after?.cls).includes('tts-playing') && String(after?.text) === '🎙', JSON.stringify(after));
  await cdp.shot('s3-stopped.png');

  const errs = cdp.consoleLogs.filter(l => !/favicon|icon\.svg|\.png|net::ERR/i.test(l));
  check('S5 console 无新增错误', errs.length === 0, errs.slice(0, 5).join(' | '));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, errors: cdp.consoleLogs.slice(0, 10) }, null, 2));
  log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
  try { await cdp.send('Page.close'); } catch { }
  process.exit(fail > 0 ? 1 : 0);
};

main().catch(e => { console.error('FATAL', e); process.exit(2); });
