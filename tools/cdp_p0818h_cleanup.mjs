/**
 * cdp_p0818h_cleanup.mjs — P-0818-H 前端清理走查
 *
 * 验证：①顶栏导航不再含「Gal Demo」「大型结构」；②首页卡片也不含这两入口；
 * ③一般模式角色选择页出现「🏰 大型地图」选项；④console 0 新增错误。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const chrome = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const url = 'http://localhost:8000/';
const port = 9357;
const shotDir = 'D:\\roleplay-java\\tmp\\p0818h';
mkdirSync(shotDir, { recursive: true });

const proc = spawn(chrome, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--force-device-scale-factor=1',
  '--window-size=1280,900',
  '--remote-debugging-port=' + port,
  '--user-data-dir=D:\\roleplay-java\\tmp\\p0818h_chrome_clean',
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
};

function send(method, params = {}) {
  return new Promise((resolve) => {
    const mid = ++id;
    pending.set(mid, resolve);
    ws.send(JSON.stringify({ id: mid, method, params }));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function evalJs(expr) {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  return r.result?.result?.value;
}

const results = [];
const check = (name, pass, extra = '') => {
  results.push({ name, pass: !!pass, extra });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`);
};

await send('Runtime.enable');
await send('Log.enable');
await send('Network.enable');
await sleep(1500);

// ①顶栏导航按钮文案
const navTexts = await evalJs(`[...document.querySelectorAll('.app2-nav-btn')].map(b => b.textContent.trim())`);
check('顶栏无 Gal Demo', !(navTexts || []).some(t => t.includes('Gal Demo')), JSON.stringify(navTexts));
check('顶栏无 大型结构', !(navTexts || []).some(t => t.includes('大型结构')), JSON.stringify(navTexts));
check('顶栏含核心入口', (navTexts || []).some(t => t.includes('剧本选择')) && (navTexts || []).some(t => t.includes('设置')));

// ②首页卡片文案
const cardTexts = await evalJs(`[...document.querySelectorAll('.home-card')].map(b => b.textContent.trim())`);
check('首页无 Gal Demo', !(cardTexts || []).some(t => t.includes('Gal Demo')));
check('首页无 大型结构', !(cardTexts || []).some(t => t.includes('大型结构')));

// 截图：首页
await send('Page.captureScreenshot', { format: 'png' }).then((r) => {
  writeFileSync(shotDir + '\\01-home.png', Buffer.from(r.result.data, 'base64'));
});

// ③一般模式角色选择页：先点剧本选择 → 一般模式 → 第一个剧本 → 检查「🏰 大型地图」
await evalJs(`document.querySelector('.app2-nav-btn:nth-child(2)')?.click()`);
await sleep(800);
const modeChips = await evalJs(`[...document.querySelectorAll('.chip2')].map(b => b.textContent.trim())`);
const generalChip = modeChips.findIndex(t => t.includes('一般模式'));
if (generalChip >= 0) {
  await evalJs(`document.querySelectorAll('.chip2')[${generalChip}]?.click()`);
  await sleep(800);
  const items = await evalJs(`[...document.querySelectorAll('.script-item')].map(b => b.textContent.trim())`);
  if (items.length > 0) {
    await evalJs(`document.querySelectorAll('.script-item')[0]?.click()`);
    await sleep(900);
    const exploreChip = await evalJs(`[...document.querySelectorAll('.chip2')].map(b => b.textContent.trim()).findIndex(t => t.includes('2D 探索'))`);
    if (exploreChip >= 0) {
      await evalJs(`document.querySelectorAll('.chip2')[${exploreChip}]?.click()`);
      await sleep(500);
      const largeBtn = await evalJs(`[...document.querySelectorAll('button')].some(b => b.textContent.includes('大型地图'))`);
      check('一般模式 2D 探索出现「大型地图」', largeBtn);
      await send('Page.captureScreenshot', { format: 'png' }).then((r) => {
        writeFileSync(shotDir + '\\02-role-select.png', Buffer.from(r.result.data, 'base64'));
      });
    } else {
      check('找到 2D 探索 chip', false, JSON.stringify(modeChips));
    }
  } else {
    check('一般模式剧本列表非空', false, JSON.stringify(items));
  }
} else {
  check('找到一般模式 chip', false, JSON.stringify(modeChips));
}

check('console 0 新增错误', errors.length === 0, errors.slice(0, 3).join(' | '));

writeFileSync(shotDir + '\\result.json', JSON.stringify({ results, errors, failedUrls }, null, 2));
console.log('FAILED_URLS=' + JSON.stringify(failedUrls));

ws.close();
proc.kill();
process.exit(results.every(r => r.pass) ? 0 : 1);
