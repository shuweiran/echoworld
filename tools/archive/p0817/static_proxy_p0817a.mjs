/* static_proxy_p0817a.mjs — P-0817-A 前端接入验证用静态代理
 * 服务 roleplay-v4/frontend/dist（本批次新构建产物）于 4499；
 * /api/tts/mimo/** 由本代理 mock（8000 未部署 P-0817-A 后端，端点 502）——
 *   返回与 MimoTtsController 契约一致的 JSON（audio_base64 为真实 WAV，前端可解码播放）；
 * 其余 /api/** + /ai-images/** + /assets/** 透传 http://localhost:8000（剔除 Origin 头避 CORS 白名单）。
 * 用法：node tools/static_proxy_p0817a.mjs
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/roleplay-java/roleplay-v4/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4499);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};

// mock WAV（0.4s 8kHz 16bit 静音，真实可解码）
const MOCK_WAV_B64 = readFileSync('D:/roleplay-java/tools/mock_tts_wav.b64', 'utf8').trim();

// ── 一般模式对局 mock（8000 未运行时的前端链路验证） ──────────────
// 预置会话：3 AI 角色 + 2 条历史 AI 消息（MessageView 渲染 + 播放按钮验证用）
const MOCK_AGENTS = ['沈墨', '苏浅浅', '白露'];
const MOCK_MESSAGES = [
  { role: 'agent', name: '沈墨', content: '欢迎光临街角咖啡馆，今天要喝点什么？', timestamp: '2026-08-17T03:00:00.000Z', track_id: 'main', visible_to: [], round_number: 1, track_label: '主轨道', track_mode: 'merged' },
  { role: 'agent', name: '苏浅浅', content: '听说这家的拿铁很出名，我要一杯，多加一份肉桂。', timestamp: '2026-08-17T03:00:05.000Z', track_id: 'main', visible_to: [], round_number: 1, track_label: '主轨道', track_mode: 'merged' },
  { role: 'user', name: '主控', content: '那我先来一杯手冲，大家慢慢聊。', timestamp: '2026-08-17T03:00:10.000Z', track_id: 'main', visible_to: [], round_number: 1 },
];
let mockSession = '';

function generalMock(url, method, req, res) {
  const path = url.split('?')[0];
  const json = (obj, code = 200) => {
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' });
    res.end(JSON.stringify(obj));
  };
  if (path === '/api/events' || path === '/api/stream' || path === '/api/sse') {
    // SSE 保持连接 + 延迟推送 2 条 agent_output（前端 useSSE → store.addAgentMsg → 消息流渲染）
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', 'access-control-allow-origin': '*' });
    res.write(': keepalive\n\n');
    const mk = (name, content, t) => 'event: agent_output\n' + 'data: ' + JSON.stringify({
      type: 'agent_output', agent_name: name, content, track_id: 'main', track_label: '主轨道', track_mode: 'merged', ts: t,
    }) + '\n\n';
    setTimeout(() => { try { res.write(mk('沈墨', '欢迎光临街角咖啡馆，今天要喝点什么？', Date.now())); } catch {} }, 2500);
    setTimeout(() => { try { res.write(mk('苏浅浅', '听说这家的拿铁很出名，我要一杯，多加一份肉桂。', Date.now())); } catch {} }, 5500);
    const t = setInterval(() => { try { res.write(': ping\n\n'); } catch { clearInterval(t); } }, 15000);
    req.on('close', () => clearInterval(t));
    return;
  }
  if (path.startsWith('/api/scenes/') && path.endsWith('/start')) {
    mockSession = 'mock-session-' + Date.now();
    return json({ ok: true, session_id: mockSession, agents: MOCK_AGENTS, me: 'me', goals: { enabled: true, player_goal: '与咖啡馆里的人聊聊' } });
  }
  if (path === '/api/state') {
    return json({ ok: true, session_id: mockSession, mode: 'free', round: 1, awaiting_playback: false, agents: MOCK_AGENTS, messages: MOCK_MESSAGES, current_round: 1, status: 'idle' });
  }
  if (path === '/api/mode') {
    return json({ ok: true, mode: 'free' });
  }
  if (path === '/api/send' || path === '/api/round/start' || path === '/api/round/rollback' || path === '/api/simulation/playback_done') {
    let body = '';
    req.on('data', d => (body += d));
    req.on('end', () => json({ ok: true }));
    return;
  }
  if (path === '/api/characters' && method === 'GET') return json([]);
  if (path === '/api/history' || path === '/api/history/sessions') return json([]);
  return json({ error: 'mock 未覆盖: ' + path }, 404);
}

function ttsMock(url, req, res) {
  const path = url.split('?')[0];
  const json = (obj, code = 200) => {
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' });
    res.end(JSON.stringify(obj));
  };
  if (path === '/api/tts/mimo/status') {
    return json({ enabled: true, configured: true, api_base: 'mock://mimo', model: 'MiMo-Mock', builtin_voices: ['女声温柔', '男声低沉', '元气少女'], mode: 'basic' });
  }
  if (path === '/api/tts/mimo/voices') {
    return json(['女声温柔', '男声低沉', '元气少女']);
  }
  if (path === '/api/tts/mimo/synthesize') {
    let body = '';
    req.on('data', d => (body += d));
    req.on('end', () => {
      let b = {};
      try { b = JSON.parse(body || '{}'); } catch { /* ignore */ }
      if (!String(b.text || '').trim()) return json({ error: 'text 不能为空' }, 400);
      const mode = String(b.mode || 'basic');
      // 模拟真实合成耗时（前端异步友好验证：loading 状态可捕获）
      setTimeout(() => {
        json({
          audio_base64: MOCK_WAV_B64,
          format: 'wav',
          transcript: String(b.text || ''),
          model: 'MiMo-Mock',
          elapsed_ms: 1500,
          mode,
          bytes: 6400,
        });
      }, 1500);
    });
    return;
  }
  if (path === '/api/tts/mimo/synthesize/async') {
    return json({ job_id: 'mock0001', status: 'pending' });
  }
  if (path.startsWith('/api/tts/mimo/result/')) {
    return json({ job_id: path.split('/').pop(), status: 'done', audio_base64: MOCK_WAV_B64, format: 'wav', transcript: 'mock', model: 'MiMo-Mock', elapsed_ms: 42, bytes: 6400 });
  }
  if (path.startsWith('/api/tts/mimo/voice-config/')) {
    const name = decodeURIComponent(path.split('/').pop() || '');
    return json({ character: name, voice_mode: 'basic', voice_data: null, voice: null, tts: { enabled: true, configured: true } });
  }
  return json({ error: 'mock 未覆盖: ' + path }, 404);
}

const server = createServer((req, res) => {
  const url = req.url || '/';
  if (url.startsWith('/api/tts/mimo/')) {
    ttsMock(url, req, res);
    return;
  }
  // 8000 未运行时：一般模式对局链路走 mock（TTS 之外的主要 API）
  const p = url.split('?')[0];
  if (p === '/api/events' || p === '/api/stream' || p === '/api/sse'
    || (p.startsWith('/api/scenes/') && p.endsWith('/start'))
    || p === '/api/state' || p === '/api/mode' || p === '/api/send'
    || p === '/api/round/start' || p === '/api/round/rollback'
    || p === '/api/simulation/playback_done' || p === '/api/characters' || p === '/api/history' || p === '/api/history/sessions') {
    generalMock(url, req.method || 'GET', req, res);
    return;
  }
  if (url.startsWith('/api/') || url.startsWith('/ai-images/') || url.startsWith('/assets/SCENE_TILESET/')) {
    const headers = { ...req.headers };
    delete headers.origin;
    headers.host = 'localhost:8000';
    headers['content-type'] = headers['content-type'] || 'application/json';
    const preq = httpRequest(BACKEND + url, { method: req.method, headers }, (pres) => {
      res.writeHead(pres.statusCode || 200, { ...pres.headers, 'access-control-allow-origin': '*' });
      pres.pipe(res);
    });
    preq.on('error', (e) => { res.writeHead(502); res.end('proxy err: ' + e.message); });
    req.pipe(preq);
    return;
  }
  const clean = normalize(url.split('?')[0]).replace(/^([/\\])+/, '');
  const file = join(ROOT, clean);
  if (existsSync(file) && statSync(file).isFile()) {
    res.writeHead(200, { 'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream' });
    res.end(readFileSync(file));
    return;
  }
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(readFileSync(join(ROOT, 'index.html')));
}).listen(PORT, () => console.log('p0817a static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ', tts mock on)'));
