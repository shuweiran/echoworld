/* static_proxy_p0818d.mjs — P-0818-D 验证代理：服务新 dist，/api 透传 8000 */
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import { request as httpRequest } from 'node:http';

const ROOT = 'D:/roleplay-java/roleplay-v4/frontend/dist';
const BACKEND = 'http://localhost:8000';
const PORT = Number(process.env.PORT || 4195);

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
    forward(url, req, res, null);
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
}).listen(PORT, () => console.log('p0818d static proxy on ' + PORT + ' -> ' + BACKEND + ' (root ' + ROOT + ')'));

function forward(url, req, res, body) {
  const headers = { ...req.headers };
  delete headers.origin;
  headers.host = 'localhost:8000';
  headers['content-type'] = headers['content-type'] || 'application/json';
  const preq = httpRequest(BACKEND + url, { method: req.method, headers }, (pres) => {
    res.writeHead(pres.statusCode || 200, { ...pres.headers, 'access-control-allow-origin': '*' });
    pres.pipe(res);
    res.on('close', () => pres.destroy());
  });
  preq.on('error', (e) => {
    if (res.headersSent) { res.destroy(); return; }
    res.writeHead(502); res.end('proxy err: ' + e.message);
  });
  if (body != null) preq.end(body); else req.pipe(preq);
}
