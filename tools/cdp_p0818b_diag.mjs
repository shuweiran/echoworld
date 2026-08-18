import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const PORT = 9252;
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const EDGE = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
].find(p => existsSync(p));

const child = spawn(EDGE, [
  '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
  `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
  '--user-data-dir=C:\\Temp\\edge-p0818b-diag-' + Date.now(), 'about:blank',
], { stdio: 'ignore', detached: true });
child.unref();
for (let i = 0; i < 40; i++) {
  try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { }
  await sleep(500);
}
const tab = await (await fetch(`${BASE}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json();
const ws = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0;
const pending = new Map();
const errors = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
    errors.push((m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
  }
  if (m.method === 'Runtime.exceptionThrown') {
    errors.push('EXC: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
  }
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); reject(new Error('timeout ' + method)); }, 20000);
  pending.set(i, (m) => { clearTimeout(t); resolve(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
const evalv = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.result?.exceptionDetails) return 'EVAL_EXC: ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 200);
  return r.result?.result?.value;
};
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: 'http://127.0.0.1:8000/' });
await sleep(8000);
console.log('url:', await evalv('location.href'));
console.log('ready:', await evalv('document.readyState'));
console.log('topbar:', await evalv("!!document.querySelector('.app2-topbar')"));
console.log('home:', await evalv("!!document.querySelector('.home-page')"));
console.log('rootText:', await evalv("(document.body && document.body.textContent || '').slice(0, 300)"));
console.log('errors:', JSON.stringify(errors.slice(0, 8), null, 2));
process.exit(0);
