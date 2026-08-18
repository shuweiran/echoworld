/* cdp_p0817a.mjs — P-0817-A 前端接入验证（CDP 走查）
 * 前置：static_proxy_p0817a.mjs（4499，含 tts mock）；CDP 9222。
 * 验证点：
 *  A1 首页渲染 + console 无新增错误
 *  A2 角色库 → 添加角色弹窗 → 「🎙️ MiMo 声线」模块渲染（checkbox/select/input/试听）
 *  A3 勾选声线 → 模式切换 placeholder 联动
 *  A4 试听按钮点击 → mock 合成 → 状态流转（loading → playing/idle，AudioContext 播放）
 *  A5 保存角色 → 角色入库（localStorage）→ 声线字段落卡
 *  A6 再次编辑该角色 → 声线回填（voiceMode/voiceData 保留）
 * 输出：tmp/p0817a/（截图 + result.json + progress.log）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4499/';
const OUT = 'D:/roleplay-java/tmp/p0817a';
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
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); return true; } } catch (e) { } return false; }
}

async function newTab(url) {
  let tab;
  try {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
    tab = await r.json();
  } catch (e) {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`);
    tab = await r.json();
  }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  return cdp;
}

async function waitFor(cdp, expr, t = 30000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(500);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}

const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);

const main = async () => {
  // A1 首页
  const cdp = await newTab(APP);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '首页顶栏');
  check('A1 首页渲染', true, '顶栏存在');
  // 清理上次运行遗留的测试数据（demo2 localStorage 仅存生成/额外角色，清空安全）
  await cdp.eval(`localStorage.clear(); location.reload(); true`);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, 'reload 后首页');
  await cdp.shot('01-home.png');

  // A2 角色库 → 添加角色弹窗
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('角色库'))`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡管理')`, 20000, '角色库页');
  await click(cdp, `[...document.querySelectorAll('.role-chip-add')][0]`);
  await waitFor(cdp, `!!document.querySelector('.modal-box') && document.body.textContent.includes('🎙️ MiMo 声线')`, 20000, '声线模块');
  check('A2 添加角色弹窗含 MiMo 声线模块', true, 'checkbox 文案存在');
  await cdp.shot('02-roleform-mimo.png');

  // A3 勾选 → select/input/试听出现 + placeholder 联动
  await click(cdp, `[...document.querySelectorAll('.modal-box input[type=checkbox]')].find(i => i.closest('label').textContent.includes('MiMo 声线'))`);
  await waitFor(cdp, `!![...document.querySelectorAll('.modal-box select')].find(s => [...s.options].some(o => o.value === 'clone'))`, 10000, '声线 select');
  const modeSel = await cdp.eval(`(() => {
    const s = [...document.querySelectorAll('.modal-box select')].find(s => [...s.options].some(o => o.value === 'clone'));
    return s ? { count: document.querySelectorAll('.modal-box select').length, hasClone: [...s.options].some(o => o.value === 'clone') } : null;
  })()`);
  check('A3 声线模式 select 含 basic/clone/design', !!modeSel && modeSel.hasClone, JSON.stringify(modeSel));
  const ph1 = await cdp.eval(`(() => {
    const s = [...document.querySelectorAll('.modal-box select')].find(s => [...s.options].some(o => o.value === 'clone'));
    s.value = 'clone'; s.dispatchEvent(new Event('change', { bubbles: true }));
    const inputs = [...document.querySelectorAll('.modal-box input')];
    return inputs.map(i => i.placeholder).filter(Boolean);
  })()`);
  await sleep(300);
  const ph2 = await cdp.eval(`(() => [...document.querySelectorAll('.modal-box input')].map(i => i.placeholder).filter(Boolean))()`);
  check('A3b clone 模式 placeholder 联动', ph2.some(p => String(p).includes('参考音频')), JSON.stringify(ph2));
  await cdp.shot('03-mimo-clone.png');

  // A4 试听按钮 → mock 合成状态流转（mock 延迟 1.5s，可捕获 loading）
  // 注意：试听按钮在角色名为空时 disabled（防合成无主语），先填名字
  await cdp.eval(`(() => {
    const nameInput = [...document.querySelectorAll('.modal-box input')].find(i => i.placeholder && i.placeholder.includes('林晚秋'));
    if (nameInput) {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(nameInput, '声线测试员');
      nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return true;
  })()`);
  await sleep(300);
  const previewEnabled = await cdp.eval(`(() => {
    const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听'));
    return b ? !b.disabled : false;
  })()`);
  check('A4 试听按钮存在且可点（已填角色名）', previewEnabled);
  await click(cdp, `[...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听'))`);
  await sleep(400);
  const loadingState = await cdp.eval(`(() => {
    const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('合成中'));
    return !!b;
  })()`);
  check('A4b 合成中 loading 状态', loadingState, '按钮文案=合成中');
  await cdp.shot('04-preview-loading.png');
  // 合成 1.5s + 播放 0.4s，等 3s 应回到试听态
  await sleep(3200);
  const finalState = await cdp.eval(`(() => {
    const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听') || b.textContent.includes('停止') || b.textContent.includes('失败'));
    return b ? b.textContent.trim() : 'none';
  })()`);
  check('A4c 试听完成回到试听态', finalState.includes('试听') || finalState.includes('停止'), '当前按钮=' + finalState);
  await cdp.shot('05-preview-done.png');

  // A5 填写声线数据保存 → 角色入库（名字已填，此处补 voiceData，clone 模式 placeholder=参考音频）
  await cdp.eval(`(() => {
    const dataInput = [...document.querySelectorAll('.modal-box input')].find(i => i.placeholder && String(i.placeholder).includes('参考音频'));
    if (dataInput) {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(dataInput, 'https://example.com/ref.wav');
      dataInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return { data: dataInput ? true : false };
  })()`);
  await sleep(300);
  await click(cdp, `[...document.querySelectorAll('.modal-box button')].find(b => b.textContent.trim() === '添加')`);
  await sleep(800);
  const saved = await cdp.eval(`(() => {
    try {
      const hits = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        const v = localStorage.getItem(k) || '';
        if (v.includes('声线测试员')) hits.push({ k, hasData: v.includes('https://example.com/ref.wav') });
      }
      return { hits };
    } catch (e) { return { hits: [], err: String(e) }; }
  })()`);
  check('A5 角色保存成功（含声线字段）', saved.hits.length > 0 && saved.hits.some(h => h.hasData), JSON.stringify(saved));
  await cdp.shot('06-saved.png');

  // A6 重进编辑回填验证：打开该角色详情 → 编辑 → 声线回填
  await click(cdp, `[...document.querySelectorAll('.role-chip')].find(c => c.textContent.includes('声线测试员'))`);
  await waitFor(cdp, `document.body.textContent.includes('角色卡详情')`, 15000, '详情页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('编辑设定'))`);
  await waitFor(cdp, `!!document.querySelector('.modal-box') && document.body.textContent.includes('MiMo 声线')`, 10000, '编辑弹窗');
  const backfill = await cdp.eval(`(() => {
    const modal = document.querySelector('.modal-box');
    if (!modal) return null;
    const checked = [...modal.querySelectorAll('input[type=checkbox]')].find(i => i.closest('label').textContent.includes('MiMo 声线'));
    const sel = [...modal.querySelectorAll('select')].find(s => [...s.options].some(o => o.value === 'clone'));
    const inputs = [...modal.querySelectorAll('input')].map(i => i.value);
    return { mimoChecked: checked ? checked.checked : null, mode: sel ? sel.value : null, hasData: inputs.some(v => v === 'https://example.com/ref.wav') };
  })()`);
  check('A6 编辑弹窗声线回填（勾选+模式+数据）', !!backfill && backfill.mimoChecked === true && backfill.mode === 'clone' && backfill.hasData === true, JSON.stringify(backfill));
  await cdp.shot('07-backfill.png');

  // console 错误汇总
  const errs = cdp.consoleLogs.filter(l => !/favicon|icon\.svg|assets\/.*\.png/i.test(l));
  check('A8 console 无新增错误', errs.length === 0, errs.slice(0, 5).join(' | '));

  const result = { pass, fail, errors: cdp.consoleLogs.slice(0, 10) };
  writeFileSync(`${OUT}/result.json`, JSON.stringify(result, null, 2));
  log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
  try { await cdp.send('Page.close'); } catch { }
  process.exit(fail > 0 ? 1 : 0);
};

main().catch(e => { console.error('FATAL', e); process.exit(2); });
