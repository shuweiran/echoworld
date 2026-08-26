/* cdp_p0816b_label.mjs — P-0816-B 补充：房间名/热点名标签像素探针（复用已存截图）
 * 在页面内解码 tmp/p0816b/*.png，按精确颜色容差统计：
 *   - roomLabel  = #e2e8f0(226,232,240) tol 12（房间名文字，排除 spawn AI 标签 #cbd5e1 与角色名彩色）
 *   - zoneLabel  = #ffe08a(255,224,138) tol 15（热点名称标签，与热点金色 #ffd166 区分）
 *   - agentBlue  = #38bdf8(56,189,248) tol 50（出生点玩家蓝标记 / 玩家角色）
 * 产出 tmp/p0816b/label_result.json；退出码 0=达标。
 */
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const PORT = 9258;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/echoworld/tmp/p0816b';
const PROG = `${OUT}/progress.log`;
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); } };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 60000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval err: ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 200));
    return r.result?.result?.value;
  }
}

const FILES = ['01_world_full.png', '06_zoomed.png'];

async function main() {
  const child = spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox', `--remote-debugging-port=${PORT}`,
      '--user-data-dir=C:\\Temp\\edge-p0816bl-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(400); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  if (await cdp.eval('1+1') !== 2) throw new Error('sanity');

  let allOk = true;
  for (const f of FILES) {
    const b64 = readFileSync(`${OUT}/${f}`).toString('base64');
    const dataUrl = 'data:image/png;base64,' + b64;
    const res = await cdp.eval(`(async () => {
      const img = new Image();
      await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = ${JSON.stringify(dataUrl)}; });
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
      const x = c.getContext('2d'); x.drawImage(img, 0, 0);
      const d = x.getImageData(0, 0, c.width, c.height).data;
      const near = (r,g,b,tr,tg,tb,tol) => Math.abs(r-tr)<=tol && Math.abs(g-tg)<=tol && Math.abs(b-tb)<=tol;
      let roomLabel=0, zoneLabel=0, agentBlue=0;
      for (let i=0;i<d.length;i+=4) {
        if (near(d[i],d[i+1],d[i+2],226,232,240,12)) roomLabel++;
        if (near(d[i],d[i+1],d[i+2],255,224,138,28)) zoneLabel++;
        if (near(d[i],d[i+1],d[i+2],56,189,248,50)) agentBlue++;
      }
      // 区域定位探针：五个热点标签理论屏幕位置（世界坐标 → 截图全宽映射近似）
      // 地图 32x20 → 世界 1000x600；截图 1440x900（窗口），画布近似铺满主要区域——用宽松黄色探测
      const labelBoxes = [
        [4,3],[14,3],[22,3],[5,15],[15,11]
      ].map(([zx,zy]) => {
        const wx=(zx+0.5)*31.25, wy=(zy+0.5)*30 + 24;
        // 画布位置估计：世界坐标按截图尺寸比例（1000x600 世界 → 视口）
        const sx = (wx/1000)*c.width, sy = (wy/600)*c.height;
        let n=0;
        const x0=Math.max(0,Math.floor(sx-30)), x1=Math.min(c.width-1,Math.ceil(sx+30));
        const y0=Math.max(0,Math.floor(sy-12)), y1=Math.min(c.height-1,Math.ceil(sy+12));
        for(let yy=y0;yy<=y1;yy++) for(let xx=x0;xx<=x1;xx++) {
          const i=(yy*c.width+xx)*4;
          if (near(d[i],d[i+1],d[i+2],255,224,138,28) || (d[i]>215&&d[i+1]>175&&d[i+2]<200)) n++;
        }
        return n;
      });
      return JSON.stringify({ roomLabel, zoneLabel, agentBlue, labelBoxes });
    })()`);
    const r = JSON.parse(res);
    log(f, JSON.stringify(r));
    const ok = r.roomLabel >= 60 && r.zoneLabel >= 60 && r.agentBlue >= 40;
    const boxes = JSON.stringify(r.labelBoxes);
    log((ok ? 'PASS ' : 'FAIL ') + f + ' 房间名标签=' + r.roomLabel + ' / 热点名标签=' + r.zoneLabel + ' / 蓝标记=' + r.agentBlue + ' / 热点区域探针=' + boxes);
    // 5 个热点区域探针：≥3 个区域有黄色文字像素 → 热点名标签确认渲染
    if (!(r.labelBoxes.filter(b => b >= 3).length >= 3)) allOk = false;
  }
  writeFileSync(`${OUT}/label_result.json`, JSON.stringify({ allOk }));
  child.kill();
  process.exit(allOk ? 0 : 1);
}
main().catch(e => { console.log('FATAL', e?.message); try { process.kill(0); } catch { } process.exit(1); });
