/* static_proxy_p0814e.mjs — P-0814-E 验证用静态代理
 * 服务 frontend/dist（新 bundle）于 4174，/api/** 与 /ai-images/** 透传 http://localhost:8000
 * （剥除 Origin 头规避 8000 CORS 白名单仅 5173/8000）；SSE（/api/events）流式透传。
 * 一次性验证工具，验证后删除（临时文件纪律）。
 */
import { createServer } from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/echoworld/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4174);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const path = decodeURIComponent(u.pathname);
  // API / SSE / 生成图片 → 透传 8000（剥 Origin，防 CORS 拒绝）
  if (path.startsWith('/api/') || path.startsWith('/ai-images/')) {
    const headers = { ...req.headers };
    delete headers.origin;
    delete headers.host;
    const preq = httpRequest(BACKEND + u.pathname + u.search, { method: req.method, headers }, (pres) => {
      res.writeHead(pres.statusCode || 200, pres.headers);
      pres.pipe(res);
    });
    preq.on('error', (e) => { res.writeHead(502); res.end('proxy err: ' + e.message); });
    req.pipe(preq);
    return;
  }
  // 静态文件
  let rel = path === '/' ? '/index.html' : path;
  const fp = normalize(join(ROOT, rel));
  if (!fp.startsWith(normalize(ROOT))) { res.writeHead(403); res.end('forbidden'); return; }
  if (existsSync(fp) && statSync(fp).isFile()) {
    res.writeHead(200, { 'Content-Type': MIME[extname(fp)] || 'application/octet-stream' });
    createReadStream(fp).pipe(res);
    return;
  }
  // SPA 兜底 → index.html（asset 直连 404 时也回 index 与 8000 行为一致）
  const idx = join(ROOT, 'index.html');
  if (existsSync(idx)) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    createReadStream(idx).pipe(res);
    return;
  }
  res.writeHead(404); res.end('not found');
}).listen(PORT, () => console.log('static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
