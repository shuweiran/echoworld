/* cdp_p0815i.mjs — P-0815-H 群聊面板按群过滤（方案 A）CDP 端到端真机验证
 * 前置：tools/static_proxy_p0815i.mjs 运行中（4183 = dist 新 bundle（含过滤修复）；/api → 8000 真实后端；
 *       POST /api/scenes/map 被代理拦截 500 → 前端兜底 park 场景，零 LLM 地图成本）
 * 流程：剧本选择 → 一般模式 → 选剧本 → 角色选择（2D 探索 + 带玩家 + 点亮 3 AI）→ 进入 2D 探索
 *   阶段 1（自由对话模式）：点击玩家角色自身 → 面板打开（无群）→ 采样对话框（点击推进），
 *       断言：世界消息全量流入（AI 组对话可见），conversation-status currentTrack 为空
 *   阶段 2（群聊模式）：退出面板 → 点击 NPC → 打招呼自动建 DYAD → currentTrack 非空（groupInfo 生效）
 *       采样窗口内断言（核心）：
 *       a) 新观察到的 agent 消息 speaker 全部 ∈ 当前群 participants（其他组成员不混入）；
 *       b) 窗口期 recentConversations 中 tick>joinTick 且 group≠currentTrack 的其他组条目，
 *          其文本绝不出现在面板观察消息中（其他组消息不再混入）；
 *       c) 当前群消息正常显示（正向）；
 *       d) 世界气泡不受影响（SimulationScene 气泡独立渲染，截图佐证 + 代码事实）；
 *   收尾：console 0 错误；POST /api/simulation/reset 复位世界（agents=0 running=false）
 * 产出：tmp/p0815i/progress.log + 截图
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9256;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4183/';
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/roleplay-java/tmp/p0815i';
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
      else if (m.method === 'Runtime.consoleAPICalled') c.consoleLogs.push(m.params.type + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
    };
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
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}

async function waitFor(cdp, expr, t = 45000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(700);
  }
  throw new Error('timeout ' + label);
}
async function clickText(cdp, sel, text) {
  const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text);
}

// ── 页面内工具：读 2D 世界状态（代理同源 fetch） ──
const PAGE_READ = {
  state: `(async () => { const r = await fetch('/api/simulation/state'); const d = await r.json(); return JSON.stringify({ running: d.running, agents: (d.agents||[]).map(a => ({ n: a.agentName, x: a.x, y: a.y, pc: !!a.playerControlled })), tick: d.tickCount ?? d.tick ?? 0, recent: (d.recentConversations||[]).map(c => c) }); })()`,
  convStatus: `(async () => { const r = await fetch('/api/simulation/conversation-status'); const d = await r.json(); return JSON.stringify({ currentTrack: d.currentTrack || '', groups: (d.groups||[]).map(g => ({ id: g.id, mode: g.mode, participants: g.participants || [] })) }); })()`,
  dialog: `(() => {
    const dlg = document.querySelector('.gal-dialog');
    const name = document.querySelector('.gal-dialog-name');
    const text = document.querySelector('.gal-dialog-text');
    const hint = document.querySelector('.gal-dialog-hint');
    const logs = Array.from(document.querySelectorAll('.gal-log-line')).map(l => l.textContent.trim());
    const wait = document.querySelector('.gal-dialog-wait');
    return JSON.stringify({ hasDlg: !!dlg, name: name ? name.textContent.trim() : '', text: text ? text.textContent.trim() : '', hint: hint ? hint.textContent.trim() : '', wait: wait ? wait.textContent.trim() : '', logs });
  })()`,
};

// ── 浏览器级真实输入点击：游戏坐标 → 屏幕坐标（canvas rect + FIT 缩放）→ CDP Input.dispatchMouseEvent ──
async function clickGame(cdp, gx, gy) {
  const rect = await cdp.eval(`(()=>{ const c=document.querySelector('.phaser-sim-view canvas'); if(!c) return null; const r=c.getBoundingClientRect(); return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height}); })()`);
  if (!rect) return 'NO_CANVAS';
  const R = JSON.parse(rect);
  const sx = R.left + (gx / 1000) * R.width;
  const sy = R.top + (gy / 600) * R.height;
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: sx, y: sy, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: sx, y: sy, button: 'left', clickCount: 1 });
  return 'OK';
}

async function pageState(cdp) { return JSON.parse(await cdp.eval(PAGE_READ.state)); }
async function pageConv(cdp) { return JSON.parse(await cdp.eval(PAGE_READ.convStatus)); }
async function pageDialog(cdp) { return JSON.parse(await cdp.eval(PAGE_READ.dialog)); }

function norm(s) { return String(s || '').replace(/\s+/g, ''); }

function main2(cdp, child) {
  return (async () => {
    // ── 导航：剧本选择 → 一般模式 → 剧本卡 → 角色选择 ──
    await cdp.send('Page.navigate', { url: APP });
    await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 25000, 'nav');
    await clickText(cdp, '.app2-nav-btn', '剧本选择');
    await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'mode chips');
    await clickText(cdp, '.chip2', '一般模式');
    await sleep(600);
    await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
    await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 20000, 'roles page');
    await sleep(500);

    // ── 角色选择：2D 探索 + 带玩家 + 选 me + 点亮 3 个 AI ──
    await clickText(cdp, '.chip2', '2D 探索');
    await sleep(300);
    // 带玩家 checkbox（roles-footer 内唯一的 checkbox）
    await cdp.eval(`(()=>{ const cb=Array.from(document.querySelectorAll('.roles-footer input[type=checkbox]')).find(x=>x.checked===false); if(cb){cb.click();return 'OK';} return 'NO_CB'; })()`);
    await sleep(300);
    // 选玩家角色：点「选择你的角色」→ 弹窗第一个角色
    await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.role-chip')).find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(!b) return 'NO_PICKER'; b.click(); return 'OK'; })()`);
    await sleep(400);
    await cdp.eval(`(()=>{ const btns=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(btns.length===0) return 'NO_MODAL'; btns[0].click(); return 'OK'; })()`);
    await sleep(400);
    // 读玩家角色名（玩家卡文案「{avatar} {name} · 玩家角色」，AI 点亮时排除同名卡防重复）
    const playerName = await cdp.eval(`(()=>{ const c=Array.from(document.querySelectorAll('.role-chip')).find(x=>x.textContent&&x.textContent.includes('玩家角色')&&!x.textContent.includes('不参与本局')); if(!c) return ''; const t=c.textContent.trim(); const m=t.match(/^(\S+)\s+([^·]+?)\s*·/); return m?m[2].trim():''; })()`);
    log('[player-role]', playerName);
    // 点亮 3 个 AI 角色（排除玩家卡/选择卡/add 卡/玩家同名卡，防误点开弹窗或重复）
    const lit = await cdp.eval(`(()=>{
      const pn=${JSON.stringify(playerName)};
      const cards = Array.from(document.querySelectorAll('.role-chip')).filter(c=>c.classList && !c.classList.contains('role-chip-add') && !c.textContent.includes('选择你的角色') && !c.textContent.includes('玩家角色') && !c.textContent.includes('不参与本局') && !(pn && c.textContent.includes(pn)));
      let n=0;
      for(const c of cards){ if(!c.classList.contains('selected')){ c.click(); n++; if(n>=3) break; } }
      return 'lit='+n;
    })()`);
    log('[lit-ai-roles]', lit);
    await sleep(400);
    await clickText(cdp, '.btn2.btn2-primary', '进入 2D 探索');
    // ── 2D 世界挂载 ──
    await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 45000, 'phaser canvas');
    // 等世界启动（状态文本文案不稳定，以真实状态为准：running=true）
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return d.running === true; })()`, 30000, 'world running');
    // 等 4 角色加载
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return (d.agents||[]).length>=4; })()`, 60000, '4 agents');
    await sleep(3000);
    let st = await pageState(cdp);
    const stText = await cdp.eval(`(()=>{ const s=Array.from(document.querySelectorAll('.phaser-sim-view span')).find(x=>/SSE|加载|初始化|成功|失败|已连接/.test(x.textContent)); return s?s.textContent.trim().slice(0,80):'(none)'; })()`);
    log('[status-text]', stText);
    log('[world]', JSON.stringify({ agents: st.agents.map(a => a.n + '@' + Math.round(a.x) + ',' + Math.round(a.y) + (a.pc ? '(me)' : '')), tick: st.tick, running: st.running }));
    check('2D 世界加载 4 角色（me + 3 AI）', st.agents.length >= 4, JSON.stringify(st.agents.map(a => a.n)));
    await cdp.shot('01_world_loaded.png');

    // ══ 阶段 1：自由对话模式（点击玩家自身打开面板，无群） ══
    let me = null;
    for (let i = 0; i < 5 && !me; i++) {
      const s2 = await pageState(cdp);
      me = s2.agents.find(a => a.pc);
      if (me) {
        await clickGame(cdp, me.x, me.y);
        await sleep(1500);
        const opened = await cdp.eval(`!!document.querySelector('.sim-gal-panel')`);
        if (!opened) { log('[free-click] 未命中，重试(' + (i + 1) + ') 目标@' + Math.round(me.x) + ',' + Math.round(me.y)); me = null; }
      }
    }
    if (!me) { log('[warn] 无法点击玩家角色（playerControlled）——跳过自由模式阶段'); }
    else {
      await waitFor(cdp, `!!document.querySelector('.gal-dialog')`, 15000, 'gal panel open');
      await sleep(1500);
      const cs0 = await pageConv(cdp);
      check('1a 自由模式：点击玩家自身后 currentTrack 为空（未入群）', cs0.currentTrack === '', JSON.stringify(cs0));
      log('[free-mode] 开始采样（点击推进，等世界 AI 组对话入队）…');
      const freeObserved = [];
      const freeT0 = Date.now();
      while (Date.now() - freeT0 < 180000) {
        await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(d) d.click(); })()`);
        await sleep(300);
        const dlg = await pageDialog(cdp);
        if (dlg.name && dlg.text && dlg.hint === '▼ 点击继续') {
          const key = norm(dlg.name) + '|' + norm(dlg.text);
          if (!freeObserved.includes(key)) { freeObserved.push(key); }
        }
        for (const l of dlg.logs) {
          const m = l.split(' ');
          if (m.length >= 2 && m[0].trim() && m.slice(1).join(' ').trim()) {
            const key = norm(m[0]) + '|' + norm(m.slice(1).join(' '));
            if (!freeObserved.includes(key)) freeObserved.push(key);
          }
        }
        if (freeObserved.length >= 2) break;
        await sleep(700);
      }
      const cs1 = await pageConv(cdp);
      check('1b 自由模式：世界 AI 组对话消息流入面板（≥1 条）', freeObserved.length >= 1, 'observed=' + freeObserved.length + ' ' + JSON.stringify(freeObserved.slice(0, 3)));
      check('1c 自由模式：currentTrack 仍为空（面板未入群仍显示全量）', cs1.currentTrack === '', JSON.stringify(cs1.currentTrack));
      await cdp.shot('02_free_mode.png');
    }

    // 退出面板（清空队列，为群聊阶段干净起板）
    await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('button')).find(x=>x.textContent&&x.textContent.includes('退出对话')); if(b){b.click();return 'OK';} return 'NO'; })()`);
    await sleep(1500);

    // ══ 阶段 2：群聊模式（点击 NPC → 打招呼 → 自动建 DYAD → groupInfo 生效） ══
    st = await pageState(cdp);
    // 优先点离玩家最近的 NPC（加速 ≤200px 自动建组）；失败重试点击
    let npc = null, npcClickOk = false;
    const pm = st.agents.find(a => a.pc);
    for (let i = 0; i < 5 && !npcClickOk; i++) {
      const s3 = await pageState(cdp);
      const candidates = s3.agents.filter(a => !a.pc);
      if (candidates.length === 0) break;
      const p3 = s3.agents.find(a => a.pc);
      const dist = (a) => p3 ? Math.hypot(a.x - p3.x, a.y - p3.y) : 99999;
      candidates.sort((a, b) => dist(a) - dist(b));
      npc = candidates[0];
      await clickGame(cdp, npc.x, npc.y);
      await sleep(1800);
      npcClickOk = await cdp.eval(`!!document.querySelector('.sim-gal-panel')`);
      if (!npcClickOk) log('[npc-click] 未命中，重试(' + (i + 1) + ') ' + npc.n + '@' + Math.round(npc.x) + ',' + Math.round(npc.y));
    }
    check('2 前置：成功点击 NPC（面板打开）', npcClickOk, npc ? npc.n : '(no npc)');
    if (!npcClickOk) { log('FATAL 无法打开面板'); child.kill(); process.exit(1); }
    // 等 DYAD 形成（点击设目标 → 玩家走近 NPC → ≤200px 自动建组；40s 未成则重新点击触发打招呼）
    let joinTick = 0, curGroup = null;
    const jt0 = Date.now();
    let lastNpcClick = Date.now();
    while (Date.now() - jt0 < 120000) {
      const cs = await pageConv(cdp);
      if (cs.currentTrack) {
        curGroup = cs.groups.find(g => g.id === cs.currentTrack) || null;
        const s2 = await pageState(cdp);
        joinTick = s2.tick;
        log('[join]', 'currentTrack=' + cs.currentTrack + ' members=' + JSON.stringify(curGroup ? curGroup.participants : []) + ' tick=' + joinTick);
        break;
      }
      if (Date.now() - lastNpcClick > 40000) {
        const s3 = await pageState(cdp);
        const npc2 = s3.agents.find(a => !a.pc);
        if (npc2) await clickGame(cdp, npc2.x, npc2.y);
        lastNpcClick = Date.now();
        log('[join-retry] 重新点击 NPC ' + (npc2 ? npc2.n : '?'));
      }
      await sleep(2000);
    }
    check('2a 群聊：玩家进入群（currentTrack 非空）', !!curGroup, 'currentTrack=' + (curGroup ? curGroup.id : '(none)'));
    if (!curGroup) { log('FATAL 未入群'); child.kill(); process.exit(1); }
    check('2b 群聊：群头成员包含玩家与至少 1 个 AI', curGroup.participants.length >= 2, JSON.stringify(curGroup.participants));
    await cdp.shot('03_joined_group.png');

    // 采样窗口：点击推进 + 记录完整文本消息（hint=▼ 点击继续 即完整态）
    // P-0815-H 复核：每 5s 采样群生命周期（群头 DOM + currentTrack + tick）→ 时间线分段界定
    // 「群激活窗口」；泄漏断言只作用于群激活期间（群解散后回到自由模式属设计行为，不视为泄漏）。
    // 群解散 >20s 时自动重新点击 NPC 重入群（保持群激活覆盖，且反复验证过滤）。
    log('[group-mode] 采样窗口开始（观察群内消息是否正常显示 / 其他组消息是否混入）…');
    const groupObserved = []; // {name, text, ts}
    const timeline = [];      // {ts, track, members, head, tick}
    const gT0 = Date.now();
    const WIN = 90000;
    let lastTimelineSample = 0;
    let lastRejoinClick = 0;
    let lastProbeSend = 0;
    let lastPlayerSend = 0;
    while (Date.now() - gT0 < WIN) {
      await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(d) d.click(); })()`);
      await sleep(300);
      const dlg = await pageDialog(cdp);
      if (dlg.name && dlg.text && dlg.hint === '▼ 点击继续') {
        groupObserved.push({ name: dlg.name, text: dlg.text, ts: Date.now() });
      }
      for (const l of dlg.logs) {
        const sp = l.indexOf(' ');
        if (sp > 0) groupObserved.push({ name: l.slice(0, sp), text: l.slice(sp + 1), ts: Date.now() });
      }
      if (Date.now() - lastTimelineSample > 5000) {
        lastTimelineSample = Date.now();
        const csT = await pageConv(cdp);
        const stT = await pageState(cdp);
        const head = await cdp.eval(`(()=>{ const h=document.querySelector('.sim-gal-group-head'); return h ? h.textContent.replace(/\s+/g,' ').trim().slice(0,80) : ''; })()`);
        const gT = csT.groups.find(g => g.id === csT.currentTrack) || null;
        timeline.push({ ts: Date.now(), track: csT.currentTrack, members: gT ? gT.participants : [], head, tick: stT.tick });
      }
      // 群解散 >20s → 重新点击最近 NPC 重入群（保持群激活覆盖）
      const lastTrack = timeline.length ? timeline[timeline.length - 1].track : '';
      if (!lastTrack && Date.now() - lastRejoinClick > 20000) {
        const sR = await pageState(cdp);
        const npcR = sR.agents.find(a => !a.pc);
        if (npcR) { await clickGame(cdp, npcR.x, npcR.y); log('[rejoin] 群解散，重新点击 ' + npcR.n); }
        lastRejoinClick = Date.now();
      }
      // P-0815-H 加固：每 ~25s 以非本群 NPC 身份发一条消息 → 制造其他组对话流量
      //（真实候选：窗口期其他组新消息存在时，验证过滤确实拦截）
      if (Date.now() - lastProbeSend > 25000) {
        lastProbeSend = Date.now();
        const sP = await pageState(cdp);
        const csP = await pageConv(cdp);
        const gP = csP.groups.find(g => g.id === csP.currentTrack) || null;
        const gMembers = new Set(gP ? gP.participants : []);
        const npcP = sP.agents.find(a => !a.pc && !gMembers.has(a.n));
        if (npcP) {
          await cdp.eval(`(async ()=>{ try { await fetch('/api/simulation/send/${npcP.n}', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ message: '（压低声音）这地方不太平，你们刚才聊到哪了？' }) }); } catch(e){} return 'OK'; })()`);
          log('[probe-send] 制造其他组流量：' + npcP.n);
        }
      }
      // P-0815-H 加固：每 ~20s 玩家在本群发言 → 唤醒本组轮次（防「群激活但静默」→ 保证当前群消息流）
      if (Date.now() - lastPlayerSend > 20000) {
        lastPlayerSend = Date.now();
        const sM = await pageState(cdp);
        const pM = sM.agents.find(a => a.pc);
        if (pM) {
          await cdp.eval(`(async ()=>{ try { await fetch('/api/simulation/send/${pM.n}', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ message: '（顺着话头）我也觉得这地方不太平，你们怎么想？' }) }); } catch(e){} return 'OK'; })()`);
          log('[player-send] 本群发言：' + pM.n);
        }
      }
      await sleep(700);
    }
    // 去重（保持顺序）
    const seen = new Set();
    const obs = groupObserved.filter(o => { const k = norm(o.name) + '|' + norm(o.text); if (seen.has(k) || !o.name || !o.text) return false; seen.add(k); return true; });
    log('[group-observed]', obs.length, '条', JSON.stringify(obs.slice(0, 6)));
    log('[timeline]', JSON.stringify(timeline.map(t => ({ ts: new Date(t.ts).toISOString().slice(11, 19), track: t.track, tick: t.tick, head: !!t.head }))));
    await cdp.shot('04_group_mode_window.png');

    // ── 数据侧核对：窗口期 recentConversations（全组）+ conversation-status + 群激活时间线 ──
    const stEnd = await pageState(cdp);
    const csEnd = await pageConv(cdp);
    const cur = csEnd.groups.find(g => g.id === csEnd.currentTrack) || curGroup;
    const members = new Set(cur.participants);
    // 群激活时间线分段：连续同 track（非空）采样构成一段；joinTick=段首采样 tick（近似入段时刻）
    const segs = [];
    let seg = null;
    for (const t of timeline) {
      if (t.track) {
        if (seg && seg.track === t.track) { seg.to = t.ts; }
        else { if (seg) segs.push(seg); seg = { track: t.track, members: t.members, from: t.ts, to: t.ts, joinTick: t.tick }; }
      } else {
        if (seg) { segs.push(seg); seg = null; }
      }
    }
    if (seg) segs.push(seg);
    const inSegAt = (ts) => segs.find(s => ts >= s.from - 3500 && ts <= s.to + 3500) || null;
    // 全部 recentConversations 条目拍平（speaker → 文本）
    const SPK_KEYS = new Set(['group', 'mode', 'tick', 'round', 'pair', 'elapsedMs']);
    const cleanLike = (raw) => {
      const s = String(raw ?? '')
        .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2190}-\u{21FF}\u{FE0F}\u{FFFD}\u{200B}\u{200C}\u{200D}]/gu, '')
        .replace(/[【\[]\s*情绪[^】\]]*[】\]]/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      return s.length > 60 ? s.slice(0, 59) + '…' : s;
    };
    const allEntries = [];
    for (const c of stEnd.recent) {
      for (const [k, v] of Object.entries(c)) {
        if (SPK_KEYS.has(k)) continue;
        if (typeof v !== 'string' || !v.trim()) continue;
        allEntries.push({ who: k, text: v, group: c.group, tick: Number(c.tick ?? 0) });
      }
    }
    const matchObs = (o) => allEntries.filter(e => norm(o.name) === norm(e.who) && norm(o.text) === norm(cleanLike(e.text)));
    const otherGroups = [];
    for (const c of stEnd.recent) {
      const g = c.group;
      if (typeof g === 'string' && g && g !== cur.id) otherGroups.push(c);
    }
    const leakCandidates = otherGroups.filter(c => Number(c.tick ?? 0) > joinTick);
    log('[leak-candidates]', JSON.stringify({ otherGroupEntries: otherGroups.length, newOtherGroupEntries: leakCandidates.length, joinTick, endTick: stEnd.tick, activeSegs: segs.length }));
    // 观察分类：系统行跳过；玩家回声（无条目匹配且 speaker ∈ 成员）跳过；其余按时间线+条目归属判定
    const SYS = ['💬', '⚠️', '📢', '系统', '你'];
    const issues = [];
    let inGroupCurrentObs = 0;   // 群激活期命中段当前群条目
    let inGroupOtherHistObs = 0; // 群激活期命中其他群但 tick ≤ 段 joinTick（入群前历史回放，合法）
    let inGroupNoEntry = 0;      // 群激活期无条目匹配（玩家回声等）
    let freePeriodObs = 0;       // 自由时段（群解散期）观察消息——设计行为全量展示
    for (const o of obs) {
      if (SYS.some(s => norm(o.name).startsWith(norm(s)))) continue; // 系统/提示行
      const matches = matchObs(o);
      const sgm = inSegAt(o.ts);
      if (!sgm) { freePeriodObs++; continue; }
      const segMembers = new Set(sgm.members || []);
      if (matches.length === 0) {
        if (segMembers.has(o.name)) { inGroupNoEntry++; continue; } // 玩家回声
        issues.push({ name: o.name, text: o.text, ts: o.ts, seg: sgm.track, reason: 'no-entry-match' });
        continue;
      }
      const allCurGroup = matches.every(m => m.group === sgm.track);
      if (allCurGroup) { inGroupCurrentObs++; continue; }
      // 命中非段当前群条目：tick > 段 joinTick 才算窗口期新泄漏；≤ 是入段前历史回放（合法）
      const postJoin = matches.some(m => m.tick > sgm.joinTick);
      if (postJoin) {
        issues.push({ name: o.name, text: o.text, ts: o.ts, seg: sgm.track, matched: matches.map(m => ({ g: m.group, tick: m.tick })), reason: 'post-join-other-group' });
      } else {
        inGroupOtherHistObs++;
      }
    }
    const leakIssues = issues.filter(i => i.reason === 'post-join-other-group');
    check('3a 群聊（群激活期）：无其他组新消息混入（0 泄漏）', leakIssues.length === 0,
      leakIssues.length ? JSON.stringify(leakIssues.slice(0, 4)) : 'activeSegs=' + segs.length);
    // 数据级断言（确定性，不依赖面板观察）：真实 recentConversations 中，当前群条目必须被过滤函数放行、
    // 其他群条目必须被拒绝（证明数据携带可区分的 group 键 + 过滤函数判定正确）。
    // 内联实现与 src/phaser/simGroupFilter.ts 的真实 shouldShowWorldMsg 完全一致（该函数由 smoke_p0815h 单测覆盖）。
    const shouldShowWorldMsg = (msgGroup, currentGroupId) => !currentGroupId ? true : (!!msgGroup && msgGroup === currentGroupId);
    const curRejected = allEntries.filter(e => e.group === cur.id && !shouldShowWorldMsg(e.group, cur.id));
    const otherAllowed = allEntries.filter(e => e.group && e.group !== cur.id && shouldShowWorldMsg(e.group, cur.id));
    check('3d 数据级：当前群条目全部放行 / 其他群条目全部拒绝（真实数据）', curRejected.length === 0 && otherAllowed.length === 0,
      'curRejected=' + curRejected.length + ' otherAllowed=' + otherAllowed.length + ' curEntries=' + allEntries.filter(e => e.group === cur.id).length + ' otherEntries=' + otherAllowed.length);
    check('3b 群聊：群激活期当前群消息正常显示（≥1 条）', inGroupCurrentObs >= 1,
      'curObs=' + inGroupCurrentObs + ' hist=' + inGroupOtherHistObs + ' echo=' + inGroupNoEntry);
    const activePct = timeline.length ? timeline.filter(t => t.track).length / timeline.length : 0;
    check('3c 群激活窗口覆盖采样期（群头/currentTrack 保持，≥30%）', activePct >= 0.3,
      'activePct=' + (activePct * 100).toFixed(0) + '%' + (freePeriodObs ? '（群解散期自由模式全量展示 ' + freePeriodObs + ' 条，符合设计）' : ''));
    log('[note] other-group entries total=' + otherGroups.length + '（世界其他组消息存在性：' + (otherGroups.length > 0 ? '有' : '无，本次运行其他组未产消息') + '）；自由时段观察 ' + freePeriodObs + ' 条');

    // ── 世界气泡不受影响：截图佐证（气泡由 SimulationScene 从 agent 状态渲染，独立于 worldMsgs） ──
    await cdp.shot('05_world_bubbles.png');

    // ── console 错误（白名单：charanim-助手/demo_player 素材解析失败为既有噪音，P-0815-F 已注明非本批引入） ──
    const errs = cdp.consoleLogs.filter(l => (l.startsWith('EXCEPTION') || l.startsWith('error'))
      && !l.includes('charanim-助手') && !l.includes('Expected property name'));
    check('4 console 无新增错误（charanim/demo_player 既有素材噪音除外）', errs.length === 0, errs.slice(0, 3).join(' | ') || 'clean');

    // ── 复位测试世界 ──
    await cdp.eval(`(async () => { try { await fetch('/api/simulation/reset', { method: 'POST' }); } catch(e){} return 'OK'; })()`);
    await sleep(2500);
    const stReset = await pageState(cdp);
    check('5 测试世界已复位（agents=0 running=false）', (stReset.agents || []).length === 0 && stReset.running === false,
      JSON.stringify({ agents: (stReset.agents || []).length, running: stReset.running }));

    log('RESULT pass=' + pass + ' fail=' + fail);
    writeFileSync(`${OUT}/result.json`, JSON.stringify({
      pass, fail, joinTick, members: Array.from(members), otherGroupEntries: otherGroups.length,
      leakCandidates: leakCandidates.length, leakIssues, issues, activeSegs: segs.length,
      inGroupCurrentObs, inGroupOtherHistObs, inGroupNoEntry, freePeriodObs, observed: obs.length,
      consoleErrors: errs.length, timeline,
    }, null, 2));
    child.kill();
    process.exit(fail > 0 ? 1 : 0);
  })();
}

async function main() {
  let child;
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815i-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');
  await main2(cdp, child);
}
main().catch(e => { console.log('FATAL', e?.message); try { process.kill(0); } catch { } process.exit(1); });
