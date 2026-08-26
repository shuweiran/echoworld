/* cdp2_p0817a.mjs — 消息流播放按钮端到端验证（全 mock：proxy 提供一般模式对局 + TTS）
 * 路径：首页 → 剧本选择 → 一般模式 → 街角咖啡馆 → 进入对局（mock startScene/state 预置 AI 消息）
 *   → ChatPage 经典视图渲染 mock 消息 → 检查 🎙 播放按钮 → 点击 → loading → 播放 → 完成
 * 输出：tmp/p0817a/msg/（截图 + progress.log + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4499/';
const OUT = 'D:/echoworld/tmp/p0817a/msg';
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
  await cdp.eval(`(() => { window.__ttsReq = []; const of = window.fetch.bind(window); window.fetch = (...a) => { const u = String(a[0] || ''); if (u.includes('/api/tts/mimo')) window.__ttsReq.push(u); return of(...a); }; return true; })()`);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '首页');
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `document.body.textContent.includes('剧本选择')`, 20000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('button, .chip')].find(b => b.textContent.includes('一般模式') && b.textContent.length < 30)`);
  await sleep(1200);
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('街角咖啡馆'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 20000, '角色选择页');
  await cdp.shot('M2-roles.png');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  // 诊断：等 8s 后 dump 页面状态
  await sleep(8000);
  const state = await cdp.eval(`(() => {
    const t = document.body.textContent;
    return {
      text: t.slice(0, 600).replace(/\s+/g, ' '),
      ttsBtns: document.querySelectorAll('.tts-btn').length,
      messages: document.querySelectorAll('.message').length,
      gal: !!document.querySelector('.gal-dialog'),
      chatMain: !!document.querySelector('.chat-main'),
      conv: document.querySelectorAll('.conversation').length,
    };
  })()`);
  log('post-start state: ' + JSON.stringify(state));
  await cdp.shot('M3-diag.png');
  // 一般模式默认进 Gal 视图 → 切经典视图（消息列表渲染 MessageView）
  const hasClassicBtn = await cdp.eval(`(() => {
    const el = [...document.querySelectorAll('button, [class*=view]')].find(b => b.textContent.trim().includes('经典视图'));
    if (el) { el.click(); return true; } return false;
  })()`);
  log('classic view click: ' + hasClassicBtn);
  await sleep(3000);
  const state2 = await cdp.eval(`(() => ({
    ttsBtns: document.querySelectorAll('.tts-btn').length,
    messages: document.querySelectorAll('.message').length,
    chatMain: !!document.querySelector('.chat-main'),
    text: document.body.textContent.slice(0, 300).replace(/\s+/g, ' '),
  }))()`);
  log('after classic switch: ' + JSON.stringify(state2));
  await cdp.shot('M3-classic.png');
  // 等消息流播放按钮
  await waitFor(cdp, `document.querySelectorAll('.tts-btn').length >= 2`, 40000, '消息流播放按钮');
  const btnInfo = await cdp.eval(`(() => {
    const btns = [...document.querySelectorAll('.tts-btn')];
    return { count: btns.length, title: btns[0]?.title || '', className: btns[0]?.className || '', meta: (btns[0]?.closest('.message-meta') || {})?.textContent?.trim() || '' };
  })()`);
  check('B1 消息流渲染 🎙 播放按钮（≥2，AI 消息各一）', btnInfo.count >= 2, JSON.stringify(btnInfo));
  await cdp.shot('M3-messages-with-btn.png');

  // B2 点击第一条 AI 消息的播放按钮 → loading → 播放 → 完成
  await click(cdp, `document.querySelectorAll('.tts-btn')[0]`);
  await sleep(500);
  const loading = await cdp.eval(`(() => {
    const b = document.querySelectorAll('.tts-btn')[0];
    return { cls: b?.className || '', title: b?.title || '' };
  })()`);
  check('B2 点击后进入 loading（tts-loading 类）', String(loading.cls).includes('tts-loading'), JSON.stringify(loading));
  await cdp.shot('M4-loading.png');
  await sleep(3200);
  const after = await cdp.eval(`(() => {
    const b = document.querySelectorAll('.tts-btn')[0];
    const req = window.__ttsReq.slice(-1)[0] || '';
    return { cls: b?.className || '', title: b?.title || '', req };
  })()`);
  check('B3 合成请求已发出（/api/tts/mimo/synthesize）', after.req.includes('/api/tts/mimo/synthesize'), after.req);
  check('B4 播放完成回到 idle（🎙 播放语音）', !String(after.cls).includes('tts-loading') && !String(after.cls).includes('tts-playing') && after.title.includes('播放语音'), JSON.stringify(after));
  await cdp.shot('M5-done.png');

  // B5 播放中状态验证：连点两次（第二次点击时若在播放中 → 停止；mock 1.5s 合成+0.4s 播放，点两次间隔 300ms 应能捕捉 playing）
  await click(cdp, `document.querySelectorAll('.tts-btn')[1]`);
  await sleep(1700); // 合成 1.5s 完成，进入播放
  const playing = await cdp.eval(`(() => {
    const b = document.querySelectorAll('.tts-btn')[1];
    return { cls: b?.className || '', title: b?.title || '' };
  })()`);
  check('B5 播放中状态（tts-playing + ⏹ 停止）', String(playing.cls).includes('tts-playing') && playing.title.includes('停止'), JSON.stringify(playing));
  await cdp.shot('M6-playing.png');
  // 再点 → 停止
  await click(cdp, `document.querySelectorAll('.tts-btn')[1]`);
  await sleep(400);
  const stopped = await cdp.eval(`(() => {
    const b = document.querySelectorAll('.tts-btn')[1];
    return { cls: b?.className || '', title: b?.title || '' };
  })()`);
  check('B6 再点停止播放（回 idle）', !String(stopped.cls).includes('tts-playing') && stopped.title.includes('播放语音'), JSON.stringify(stopped));

  const errs = cdp.consoleLogs.filter(l => !/favicon|icon\.svg|\.png/i.test(l));
  check('B7 console 无新增错误', errs.length === 0, errs.slice(0, 5).join(' | '));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, errors: cdp.consoleLogs.slice(0, 10) }, null, 2));
  log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
  try { await cdp.send('Page.close'); } catch { }
  process.exit(fail > 0 ? 1 : 0);
};
main().catch(e => { console.error('FATAL', e); process.exit(2); });
