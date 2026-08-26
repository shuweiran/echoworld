/* static_proxy_p0816b.mjs — P-0816-B 地图内容渲染验证用静态代理
 * 服务 frontend/dist（新 bundle index-BDCXWtWn.js，含地图内容渲染）于 4191，
 * /api/** 与 /ai-images/** 透传 http://localhost:8000（剥除 Origin 头规避 CORS 白名单）；
 * SSE（/api/events）流式透传。不拦截 /api/scenes/map——本批验证用 localStorage 注入
 * 已生成的真实 LLM 地图（g_cafe 等预设剧本 id），GameBridge 命中缓存不发起生成。
 * 用法：node tools/static_proxy_p0816b.mjs
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/echoworld/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4191);

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
}).listen(PORT, () => console.log('p0816b static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
