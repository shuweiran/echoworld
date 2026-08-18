/* cdp_p0818a_tts.mjs — P-0818-A 局内 MiMo TTS 运行验证（真实后端 8000 + 真实 LLM 对局）
 * 路径：首页 → 剧本选择 → 一般模式 → 街角咖啡馆 → 进入对局
 *   → Gal 视图（默认呈现）：AI 消息完成态出现 🎙 → 点击 → loading → playing → idle
 *   → 切经典视图：消息流按钮 → 点击 → loading → playing → idle → 再点停止
 *   → 全局静音按钮：静音 → 全部按钮 🔇 灰显禁用 → 恢复
 * 输出：tmp/p0818a/（截图 + progress.log + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9248;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/roleplay-java/tmp/p0818a';
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
      else if (m.method === 'Runtime.consoleAPICalled') {
        if (m.params.type === 'error') c.consoleLogs.push('ERROR: ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 300));
      }
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

async function waitFor(cdp, expr, t = 30000, label = '', step = 700) {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(step);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}
const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);

/** 轮询记录 .tts-btn 的状态序列（观察 loading/playing/idle 流转），返回观察到的时间线。 */
async function observeTtsStates(cdp, totalMs, stepMs = 250) {
  const timeline = [];
  const t0 = Date.now();
  while (Date.now() - t0 < totalMs) {
    const st = await cdp.eval(`(() => {
      const b = document.querySelector('.tts-btn');
      if (!b) return null;
      return { cls: b.className, title: b.title || '', text: (b.textContent || '').trim() };
    })()`).catch(() => null);
    if (st) {
      const last = timeline[timeline.length - 1];
      if (!last || last.cls !== st.cls || last.title !== st.title) {
        timeline.push({ t: Date.now() - t0, ...st });
      }
    }
    await sleep(stepMs);
  }
  return timeline;
}

async function launchEdge() {
  const edge = EDGE_CANDIDATES.find(p => { try { return existsSync(p); } catch { return false; } });
  if (!edge) throw new Error('Edge not found');
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-p0818a-' + Date.now(), 'about:blank',
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
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 30000, '首页顶栏');
  // 页面加载完成后再注入 fetch 钩子（导航会重建 window 上下文，提前注入会被清掉）
  await cdp.eval(`(() => {
    window.__ttsReq = [];
    const of = window.fetch.bind(window);
    window.fetch = (...a) => { const u = String(a[0] || ''); if (u.includes('/api/tts/mimo')) window.__ttsReq.push(u); return of(...a); };
    return true;
  })()`);

  // ── 导航进局 ──────────────────────────────────────────────
  check('T1 首页渲染', true);
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('一般模式'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('街角咖啡馆'))`, 30000, '一般模式列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('街角咖啡馆'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 120000, 'Gal 视图挂载');
  check('T2 进入对局 → Gal 视图挂载', true);
  await cdp.shot('01-gal-view.png');

  // ── Gal 视图：AI 消息完成态 TTS 按钮 → 点击 → 状态流转 ────────
  await waitFor(cdp, `!!document.querySelector('.tts-btn')`, 180000, 'Gal AI 消息完成态 TTS 按钮', 300);
  check('T3 Gal 视图 AI 消息渲染 🎙 播放按钮', true);
  await cdp.shot('02-gal-message-btn.png');
  const btn0 = await cdp.eval(`(() => {
    const b = document.querySelector('.tts-btn');
    return { cls: b.className, title: b.title, text: b.textContent.trim(), hint: (b.closest('.gal-dialog-hint') || {}).textContent || '' };
  })()`);
  log('Gal TTS btn: ' + JSON.stringify(btn0));
  await click(cdp, `document.querySelector('.tts-btn')`);
  const galTimeline = await observeTtsStates(cdp, 60000);
  log('Gal TTS timeline: ' + JSON.stringify(galTimeline));
  const galCls = galTimeline.map(x => x.cls).join(' | ');
  check('T4 Gal 点击后进入 loading（tts-loading）', /tts-loading/.test(galCls), galCls);
  check('T5 Gal 播放中（tts-playing）', /tts-playing/.test(galCls), galCls);
  check('T6 Gal 播放完成回 idle（🎙 播放语音）', /播放语音/.test(galTimeline[galTimeline.length - 1]?.title || ''), galTimeline[galTimeline.length - 1]?.title || '');
  await cdp.shot('03-gal-tts-done.png');

  // ── 切经典视图：消息流按钮全流转 + 停止 ─────────────────────
  await click(cdp, `document.querySelector('.galg-classic-btn')`);
  await waitFor(cdp, `!!document.querySelector('.chat-main') || document.querySelectorAll('.tts-btn').length >= 1`, 60000, '经典视图');
  await waitFor(cdp, `document.querySelectorAll('.tts-btn').length >= 2`, 90000, '经典视图消息流 ≥2 个播放按钮', 500);
  check('T7 经典视图消息流渲染 🎙 播放按钮（≥2）', true);
  await cdp.shot('04-classic-messages.png');

  await click(cdp, `document.querySelectorAll('.tts-btn')[0]`);
  const c1 = await observeTtsStates(cdp, 60000);
  log('Classic #1 timeline: ' + JSON.stringify(c1));
  const c1Cls = c1.map(x => x.cls).join(' | ');
  check('T8 经典 #1 loading', /tts-loading/.test(c1Cls), c1Cls);
  check('T9 经典 #1 playing', /tts-playing/.test(c1Cls), c1Cls);
  check('T10 经典 #1 回 idle', /播放语音/.test(c1[c1.length - 1]?.title || ''), c1[c1.length - 1]?.title || '');
  await cdp.shot('05-classic-tts-done.png');

  // 第二条：播放中再点 = 停止
  await click(cdp, `document.querySelectorAll('.tts-btn')[1]`);
  await waitFor(cdp, `(() => { const b = document.querySelectorAll('.tts-btn')[1]; return b && b.className.includes('tts-playing'); })()`, 45000, '经典 #2 播放中', 300);
  check('T11 经典 #2 进入播放中（⏹ 停止）', true);
  await cdp.shot('06-classic-playing.png');
  await click(cdp, `document.querySelectorAll('.tts-btn')[1]`);
  await sleep(600);
  const stopped = await cdp.eval(`(() => { const b = document.querySelectorAll('.tts-btn')[1]; return { cls: b?.className || '', title: b?.title || '' }; })()`);
  check('T12 再点停止 → 回 idle', !String(stopped.cls).includes('tts-playing') && String(stopped.title).includes('播放语音'), JSON.stringify(stopped));

  // ── 全局静音 ──────────────────────────────────────────────
  const hasMute = await cdp.eval(`!!document.querySelector('.tts-mute-btn')`);
  check('T13 顶栏全局静音按钮存在', hasMute);
  if (hasMute) {
    await click(cdp, `document.querySelector('.tts-mute-btn')`);
    await sleep(500);
    const muted = await cdp.eval(`(() => {
      const btns = [...document.querySelectorAll('.tts-btn')];
      return { allMuted: btns.length > 0 && btns.every(b => b.className.includes('tts-muted') && b.disabled),
               icon: btns[0]?.textContent?.trim() || '', muteText: document.querySelector('.tts-mute-btn')?.textContent?.trim() || '' };
    })()`);
    check('T14 静音后消息按钮 🔇 灰显禁用', muted.allMuted, JSON.stringify(muted));
    await cdp.shot('07-muted.png');
    await click(cdp, `document.querySelector('.tts-mute-btn')`);
    await sleep(500);
    const unmuted = await cdp.eval(`(() => {
      const btns = [...document.querySelectorAll('.tts-btn')];
      return { anyMuted: btns.some(b => b.className.includes('tts-muted')) };
    })()`);
    check('T15 恢复后按钮可点（不再 🔇）', !unmuted.anyMuted, JSON.stringify(unmuted));
  }

  const reqs = await cdp.eval(`window.__ttsReq || []`);
  check('T16 真实 TTS 合成请求已发出（/api/tts/mimo/synthesize）', reqs.filter(u => u.includes('/synthesize')).length >= 3, JSON.stringify(reqs.slice(0, 8)));

  const errs = cdp.consoleLogs.filter(l => !/favicon|icon\.svg|\.png|net::ERR/i.test(l));
  check('T17 console 无新增错误', errs.length === 0, errs.slice(0, 6).join(' | '));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, ttsReqs: reqs, errors: cdp.consoleLogs.slice(0, 10), galTimeline, classic1: c1 }, null, 2));
  log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
  try { await cdp.send('Page.close'); } catch { }
  process.exit(fail > 0 ? 1 : 0);
};

main().catch(e => { console.error('FATAL', e); process.exit(2); });
