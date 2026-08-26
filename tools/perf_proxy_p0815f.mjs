/* perf_proxy_p0815f.mjs — P-0815-F 前后端渲染性能对比验证用静态代理
 * 端口 4181 = BEFORE（src/main/resources/static 当前生产 bundle：index-CUDanUv2.js，无本批优化）
 * 端口 4182 = AFTER （frontend/dist 新 bundle：index-D2TRmpGE.js，含本批优化）
 * /api/** 与 /ai-images/** 透传 http://localhost:8000（剥 Origin 头规避 CORS 白名单）；SSE 流式透传。
 * 用法：node tools/perf_proxy_p0815f.mjs
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const BACKEND = 'http://localhost:8000';
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

function serve(port, ROOT, label) {
  const server = createServer((req, res) => {
    const url = req.url || '/';
    if (url.startsWith('/api/')) {
      // P-0815-F：拦截 LLM 地图生成（前端兜底 park 场景）——性能对比不付真实 LLM 成本
      if (url.startsWith('/api/scenes/map')) {
        res.writeHead(500, { 'access-control-allow-origin': '*' });
        res.end('intercepted by perf proxy');
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
    const file = join(ROOT, clean || 'index.html');
    if (existsSync(file) && statSync(file).isFile()) {
      res.writeHead(200, { 'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream' });
      res.end(readFileSync(file));
      return;
    }
    res.writeHead(404); res.end('not found: ' + url);
  });
  server.listen(port, () => console.log(`[${label}] ${label} proxy on http://127.0.0.1:${port} (root=${ROOT})`));
}

serve(4181, 'D:/echoworld/src/main/resources/static', 'BEFORE');
serve(4182, 'D:/echoworld/frontend/dist', 'AFTER');
