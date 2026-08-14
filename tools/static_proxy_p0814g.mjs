/* static_proxy_p0814g.mjs — P-0814-G 本地验证代理（基于 static_proxy_p0814e.mjs）
 * 服务 roleplay-v4/frontend/dist（新 bundle）于 4175，/api/** /ai-images/** /assets/** 透传 http://localhost:8000
 * 差异：+ /assets/** 转发（素材瓦片图集 SCENE_TILESET 直连 8000 静态资源，消除「Failed to process file」代理伪错误）。
 */
import { createServer } from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/roleplay-java/roleplay-v4/frontend/dist';
const BACKEND = process.env.STATIC_PROXY_BACKEND || 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4175);

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
  // API / SSE / 生成图片 → 透传 8000（剥 Origin，防 CORS 拒绝）；/assets/ 先查 dist（新 bundle/CSS），
  // 不存在（素材瓦片图集 SCENE_TILESET 等）→ 透传 8000（旧 jar 静态资源，消除「Failed to process file」伪错误）
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
  if (path.startsWith('/assets/')) {
    const local = normalize(join(ROOT, path));
    if (existsSync(local) && statSync(local).isFile()) {
      res.writeHead(200, { 'Content-Type': MIME[extname(local)] || 'application/octet-stream' });
      createReadStream(local).pipe(res);
      return;
    }
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
  // SPA 兜底 → index.html
  const idx = join(ROOT, 'index.html');
  if (existsSync(idx)) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    createReadStream(idx).pipe(res);
    return;
  }
  res.writeHead(404); res.end('not found');
}).listen(PORT, () => console.log('static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
