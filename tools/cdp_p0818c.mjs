/**
 * cdp_p0818c.mjs — P-0818-C 角色卡「TTS 与设置」隐藏栏 + 右侧详细设置面板 CDP 真机走查
 *
 * 目标：localhost:8000（真实后端 + 新 bundle index-BI-dxbse.js）
 * 步骤：角色库 → 自由角色卡详情 → 隐藏栏收起态 → 展开隐藏栏 → 打开 TTS →
 *       右侧面板展开（卡片右侧）→ 配置 basic 声线 → 保存 → ✕ 收起 → 详细设置重开 →
 *       TTS 关闭自动收起 → 窄屏面板排到卡片下方 → console 0 错误。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const chrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const url = 'http://localhost:8000/';
const port = 9335;
const shotDir = 'D:\\roleplay-java\\tmp\\p0818c';
mkdirSync(shotDir, { recursive: true });

const proc = spawn(chrome, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--force-device-scale-factor=1',
  '--window-size=1280,900',
  '--remote-debugging-port=' + port,
  '--user-data-dir=D:\\roleplay-java\\tmp\\p0818c_chrome_clean',
  url
], { stdio: 'ignore' });

async function getTarget() {
  for (let i = 0; i < 80; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json`);
      const list = await r.json();
      const page = list.find((t) => t.type === 'page');
      if (page) return page.webSocketDebuggerUrl;
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('no target');
}

const ws = new WebSocket(await getTarget());
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0;
const pending = new Map();
const errors = [];
const failedUrls = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m);
    pending.delete(m.id);
  }
  if (m.method === 'Network.responseReceived' && m.params.response.status >= 400) {
    failedUrls.push(m.params.response.url);
  }
  if (m.method === 'Runtime.exceptionThrown') {
    errors.push('exception: ' + (m.params.exceptionDetails?.text || ''));
  }
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
    errors.push('log: ' + (m.params.entry.text || ''));
  }
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
    errors.push('console: ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '));
  }
};
function send(method, params = {}) {
  return new Promise((res) => {
    const i = ++id;
    pending.set(i, res);
    ws.send(JSON.stringify({ id: i, method, params }));
  });
}
async function evalJs(expr) {
  const m = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (m.result.exceptionDetails) throw new Error('eval: ' + JSON.stringify(m.result.exceptionDetails));
  return m.result.result.value;
}
async function waitFor(expr, timeout = 10000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    try { if (await evalJs(expr)) return true; } catch {}
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('timeout: ' + expr);
}
async function shot(name) {
  const m = await send('Page.captureScreenshot', { format: 'png' });
  if (m.result?.data) writeFileSync(`${shotDir}\\${name}.png`, Buffer.from(m.result.data, 'base64'));
}

await send('Runtime.enable');
await send('Page.enable');
await send('Log.enable');
await send('Network.enable');

const results = {};

// 1) 等待应用壳
await waitFor(`!!document.querySelector('.app2-topbar')`, 20000);
results.appLoaded = true;

// 2) 角色库
await evalJs(`[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('角色库')).click()`);
await waitFor(`!!document.querySelector('.role-chips .role-chip:not(.role-chip-add)')`);
results.roleLibLoaded = true;

// 3) 进入第一个自由角色详情
await evalJs(`document.querySelector('.role-chips .role-chip:not(.role-chip-add)').click()`);
await waitFor(`!!document.querySelector('.rd-tts-bar') && !!document.querySelector('.rd-tts-panel')`);
await new Promise((r) => setTimeout(r, 350));

// 4) 初始收起态
results.initial = await evalJs(`(() => {
  const bar = document.querySelector('.rd-tts-bar');
  const panel = document.querySelector('.rd-tts-panel');
  return {
    barOpen: bar.getAttribute('data-open'),
    panelOpen: panel.getAttribute('data-open'),
    ttsChecked: document.querySelector('.rd-tts-bar .tts-switch').checked,
    containerWide: document.querySelector('.role-detail').classList.contains('rd-with-panel')
  };
})()`);
await shot('1-initial-collapsed');

// 5) 展开隐藏栏
await evalJs(`document.querySelector('.rd-tts-handle').click()`);
await new Promise((r) => setTimeout(r, 400));
results.bar = await evalJs(`(() => {
  const bar = document.querySelector('.rd-tts-bar');
  const inner = document.querySelector('.rd-tts-content-inner');
  return {
    barOpen: bar.getAttribute('data-open'),
    innerH: Math.round(inner.getBoundingClientRect().height),
    hasSwitch: !!document.querySelector('.rd-tts-bar .tts-switch'),
    hasSettingsBtn: [...document.querySelectorAll('.rd-tts-bar button')].some(b => b.textContent.includes('详细设置'))
  };
})()`);
await shot('2-bar-expanded');

// 6) 打开 TTS → 右侧面板展开
await evalJs(`document.querySelector('.rd-tts-bar .tts-switch').click()`);
await new Promise((r) => setTimeout(r, 450));
results.panelOpen = await evalJs(`(() => {
  const panel = document.querySelector('.rd-tts-panel');
  const card = document.querySelector('.rd-card').getBoundingClientRect();
  const pr = panel.getBoundingClientRect();
  return {
    panelOpen: panel.getAttribute('data-open'),
    panelRightOfCard: pr.left >= card.right - 1,
    hasModeSelect: !!panel.querySelector('select'),
    containerWide: document.querySelector('.role-detail').classList.contains('rd-with-panel'),
    switchChecked: document.querySelector('.rd-tts-bar .tts-switch').checked
  };
})()`);
await shot('3-panel-open-right');

// 7) 配置 basic 声线（数据输入 + 试听按钮出现）
await evalJs(`(() => {
  const sel = document.querySelector('.rd-tts-panel select');
  sel.value = 'basic';
  sel.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await new Promise((r) => setTimeout(r, 250));
await evalJs(`(() => {
  const inp = document.querySelector('.rd-tts-panel input[placeholder*="内置音色名"]');
  if (!inp) return;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(inp, '女声温柔');
  inp.dispatchEvent(new Event('input', { bubbles: true }));
})()`);
await new Promise((r) => setTimeout(r, 250));
results.voiceConfig = await evalJs(`(() => ({
  dataInputVisible: !!document.querySelector('.rd-tts-panel input[placeholder*="内置音色名"]'),
  hasPreview: [...document.querySelectorAll('.rd-tts-panel button')].some(b => b.textContent.includes('试听')),
  hasSave: [...document.querySelectorAll('.rd-tts-panel button')].some(b => b.textContent.includes('保存声线'))
}))()`);

// 8) 保存声线（本地角色卡 + 后端角色库同步）
await evalJs(`[...document.querySelectorAll('.rd-tts-panel button')].find(b => b.textContent.includes('保存声线')).click()`);
// 等待保存动作结束（按钮从「保存中…」恢复）再读取最终结果提示
await waitFor(`[...document.querySelectorAll('.rd-tts-panel button')].find(b => b.textContent.includes('保存中')) === undefined`, 20000);
await new Promise((r) => setTimeout(r, 300));
results.saveMsg = await evalJs(`(() => {
  const h = [...document.querySelectorAll('.rd-tts-panel .hint')].find(x => x.textContent.includes('已保存'));
  return h ? h.textContent : '';
})()`);

// 9) ✕ 收起面板（TTS 保持开启）
await evalJs(`document.querySelector('.rd-tts-panel .modal-close').click()`);
await new Promise((r) => setTimeout(r, 300));
results.closedByX = await evalJs(`(() => ({
  panelOpen: document.querySelector('.rd-tts-panel').getAttribute('data-open'),
  switchStillOn: document.querySelector('.rd-tts-bar .tts-switch').checked
}))()`);

// 10) 详细设置按钮重开
await evalJs(`[...document.querySelectorAll('.rd-tts-bar button')].find(b => b.textContent.includes('详细设置')).click()`);
await waitFor(`document.querySelector('.rd-tts-panel').getAttribute('data-open') === 'true'`);
results.reopened = true;

// 11) 关闭 TTS → 面板自动收起
await evalJs(`document.querySelector('.rd-tts-bar .tts-switch').click()`);
await waitFor(`document.querySelector('.rd-tts-panel').getAttribute('data-open') === 'false'`);
results.closedByTtsOff = true;

// 12) 窄屏：面板排到卡片下方 + 无横向溢出
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: false });
await evalJs(`document.querySelector('.rd-tts-bar .tts-switch').click()`);
await new Promise((r) => setTimeout(r, 500));
results.narrow = await evalJs(`(() => {
  const card = document.querySelector('.rd-card').getBoundingClientRect();
  const panel = document.querySelector('.rd-tts-panel').getBoundingClientRect();
  return {
    panelBelowCard: panel.top >= card.bottom - 1,
    overflow: document.documentElement.scrollWidth - innerWidth,
    panelW: Math.round(panel.width)
  };
})()`);
await shot('4-narrow-stacked');

results.errors = errors;
results.http4xx = failedUrls;
const unexpected4xx = failedUrls.filter((u) => !u.includes('/api/characters/'));
console.log(JSON.stringify(results, null, 2));

const unexpectedErrors = errors.filter((e) => !(e.includes('404') && e.includes('Failed to load resource')));

const pass =
  results.appLoaded &&
  results.roleLibLoaded &&
  results.initial.barOpen === 'false' &&
  results.initial.panelOpen === 'false' &&
  results.bar.barOpen === 'true' &&
  results.bar.innerH > 20 &&
  results.panelOpen.panelOpen === 'true' &&
  results.panelOpen.panelRightOfCard &&
  results.panelOpen.hasModeSelect &&
  results.voiceConfig.dataInputVisible &&
  results.voiceConfig.hasSave &&
  results.saveMsg.includes('已保存') &&
  results.closedByX.panelOpen === 'false' &&
  results.closedByX.switchStillOn &&
  results.reopened &&
  results.closedByTtsOff &&
  results.narrow.panelBelowCard &&
  results.narrow.overflow <= 0 &&
  unexpectedErrors.length === 0 &&
  unexpected4xx.length === 0 &&
  results.saveMsg.includes('后端角色库');

console.log('RESULT=' + (pass ? 'PASS' : 'FAIL'));
ws.close();
proc.kill();
process.exit(pass ? 0 : 1);
