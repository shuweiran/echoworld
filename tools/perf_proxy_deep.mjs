/* perf_proxy_deep.mjs — 深度调研代理：静态 dist-instr + API→8001（固定后端，拦截 LLM 地图）
 * 端口 4183；/api/scenes/map 返回 500（前端兜底 park，零 LLM 地图成本）。
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const BACKEND = 'http://localhost:8001';
const PORT = 4183;
const ROOT = 'D:/echoworld/frontend/dist-instr';
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2',
};

const server = createServer((req, res) => {
  const url = req.url || '/';
  if (url.startsWith('/api/')) {
    if (url.startsWith('/api/scenes/map')) {
      res.writeHead(500, { 'access-control-allow-origin': '*' });
      res.end('intercepted by perf proxy deep');
      return;
    }
    const headers = { ...req.headers };
    delete headers.origin;
    headers.host = 'localhost:8001';
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
  const file = join(ROOT, clean || 'index.html');
  if (existsSync(file) && statSync(file).isFile()) {
    res.writeHead(200, { 'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream' });
    res.end(readFileSync(file));
    return;
  }
  res.writeHead(404); res.end('not found: ' + url);
});
server.listen(PORT, () => console.log(`[deep] proxy http://127.0.0.1:${PORT} (root=${ROOT}, api→${BACKEND})`));
