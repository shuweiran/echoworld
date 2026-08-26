/* static_proxy_p0815b.mjs — P-0815-B 验证用静态代理
 * 服务 frontend/dist（新 bundle）于 4176，/api/** 与 /ai-images/** 透传 http://localhost:8000
 * （剥除 Origin 头规避 8000 CORS 白名单仅 5173/8000）；SSE（/api/events）流式透传。
 * 用法：node tools/static_proxy_p0815b.mjs   （PORT 环境变量可覆盖，默认 4176）
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/echoworld/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4176);

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
  // API / SSE / 生成图片 → 透传 8000（剥 Origin，防 CORS 拒绝）
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
  // 静态资源
  const clean = normalize(url.split('?')[0]).replace(/^([/\\])+/, '');
  const file = join(ROOT, clean);
  if (existsSync(file) && statSync(file).isFile()) {
    res.writeHead(200, { 'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream' });
    res.end(readFileSync(file));
    return;
  }
  // SPA 兜底 → index.html（asset 直连 404 时也回 index 与 8000 行为一致）
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  res.end(readFileSync(join(ROOT, 'index.html')));
}).listen(PORT, () => console.log('static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
