/* static_proxy_p0817c.mjs — P-0817-C 阶段B 大厅换肤 CDP 走查用静态代理
 * 服务 roleplay-v4/frontend/dist（本批新构建产物）于 4498；
 * /api/** + /ai-images/** + /assets/** 纯透传 http://localhost:8000（8000 真实后端在跑，无 mock）。
 * 用法：node tools/static_proxy_p0817c.mjs
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/roleplay-java/roleplay-v4/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4498);

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
}).listen(PORT, () => console.log('p0817c static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));
