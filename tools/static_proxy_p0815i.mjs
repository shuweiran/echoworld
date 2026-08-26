/* static_proxy_p0815i.mjs — P-0815-H 群聊按群过滤验证用静态代理
 * 服务 frontend/dist（新 bundle，含 P-0815-H 过滤修复）于 4183，
 * /api/** 与 /ai-images/** 透传 http://localhost:8000（剥除 Origin 头规避 CORS 白名单）；
 * SSE（/api/events）流式透传。拦截 POST /api/scenes/map → 500 → 前端兜底 park 场景
 * （零 LLM 地图成本，2D 动态模拟照常，P-0815-F perf_proxy 同款手法）。
 * 用法：node tools/static_proxy_p0815i.mjs
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/echoworld/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4183);

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

const server = createServer((req, res) => {
  const url = req.url || '/';
  if (url.startsWith('/api/') || url.startsWith('/ai-images/')) {
    // P-0815-H：拦截 LLM 地图生成（前端兜底 park 场景）——验证不付真实 LLM 地图成本
    if (url.startsWith('/api/scenes/map')) {
      res.writeHead(500, { 'access-control-allow-origin': '*' });
      res.end('intercepted by p0815i proxy');
      return;
    }
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
}).listen(PORT, () => console.log('p0815i static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
