const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const childProcess = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');

let backend = null;
let gateway = null;
let updater = null;
let updateState = { status: 'idle', version: null, percent: 0, message: '' };

// 单实例保护：多个桌面进程共用同一个用户数据目录，重复启动会让 H2 roleplay.mv.db
// 被第二个 Java 后端锁住，最终表现为“Java 后端已退出（code=1）”。
const singleInstanceLock = app.requestSingleInstanceLock();
if (!singleInstanceLock) app.quit();

function broadcastUpdate(next) {
  updateState = { ...updateState, ...next };
  for (const win of BrowserWindow.getAllWindows()) win.webContents.send('update:status', updateState);
}

function configureUpdater() {
  const configPath = app.isPackaged ? packagedResource('update-config.json') : path.join(__dirname, 'staged', 'update-config.json');
  let configuredUrl = '';
  try { configuredUrl = JSON.parse(fs.readFileSync(configPath, 'utf8')).updateUrl || ''; } catch { /* release preparation creates this file */ }
  const updateUrl = process.env.ROLEPLAY_UPDATE_URL || configuredUrl;
  if (!app.isPackaged || !updateUrl) {
    broadcastUpdate({ status: 'unavailable', message: updateUrl ? '开发模式不检查更新' : '未配置更新地址' });
    return;
  }
  ({ autoUpdater: updater } = require('electron-updater'));
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = true;
  updater.setFeedURL({ provider: 'generic', url: updateUrl });
  updater.on('checking-for-update', () => broadcastUpdate({ status: 'checking', percent: 0, message: '正在检查更新' }));
  updater.on('update-available', info => broadcastUpdate({ status: 'available', version: info.version, percent: 0, message: `发现新版本 v${info.version}` }));
  updater.on('update-not-available', () => broadcastUpdate({ status: 'latest', percent: 0, message: '已是最新版本' }));
  updater.on('download-progress', progress => broadcastUpdate({ status: 'downloading', percent: Math.round(progress.percent), message: '正在下载更新' }));
  updater.on('update-downloaded', info => broadcastUpdate({ status: 'downloaded', version: info.version, percent: 100, message: '更新已下载，重启后安装' }));
  updater.on('error', error => broadcastUpdate({ status: 'error', message: `更新失败：${error.message}` }));
  setTimeout(() => updater.checkForUpdates().catch(() => {}), 1500);
}

ipcMain.handle('update:state', () => updateState);
ipcMain.handle('update:check', async () => {
  if (!updater) return updateState;
  await updater.checkForUpdates();
  return updateState;
});
ipcMain.handle('update:download', async () => {
  if (!updater) return updateState;
  await updater.downloadUpdate();
  return updateState;
});
ipcMain.handle('update:install', () => {
  if (updater && updateState.status === 'downloaded') updater.quitAndInstall();
});

function freePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.unref();
    probe.on('error', reject);
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

function packagedResource(...segments) {
  return app.isPackaged
    ? path.join(process.resourcesPath, ...segments)
    : path.join(__dirname, '..', '..', '..', ...segments);
}

function engineJar() {
  return app.isPackaged
    ? packagedResource('engine', 'roleplay-engine.jar')
    : packagedResource('target', 'roleplay-engine-1.0.0-SNAPSHOT.jar');
}

function javaCommand() {
  const bundled = packagedResource('jre', process.platform === 'win32' ? 'bin/java.exe' : 'bin/java');
  return fs.existsSync(bundled) ? bundled : 'java';
}

function backendWorkspace() {
  const workspace = path.join(app.getPath('userData'), 'backend');
  fs.mkdirSync(workspace, { recursive: true });
  return workspace;
}

async function startBackend(port) {
  const jar = engineJar();
  if (!fs.existsSync(jar)) throw new Error(`未找到后端程序：${jar}。请先执行 Maven 打包。`);
  const workspace = backendWorkspace();
  const unixDomainTempDir = path.join(workspace, 'java-unix-domain');
  fs.mkdirSync(unixDomainTempDir, { recursive: true });
  const logPath = path.join(workspace, 'backend.log');
  const log = fs.createWriteStream(logPath, { flags: 'a' });
  let exitMessage = '';
  backend = childProcess.spawn(javaCommand(), ['-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1'], {
    windowsHide: true,
    stdio: 'pipe',
    cwd: workspace,
    env: {
      ...process.env,
      JDK_JAVA_OPTIONS: [process.env.JDK_JAVA_OPTIONS, `-Djdk.net.unixdomain.tmpdir=${unixDomainTempDir}`].filter(Boolean).join(' '),
    },
  });
  backend.stdout.pipe(log);
  backend.stderr.pipe(log);
  backend.on('error', err => { exitMessage = `Java 后端启动失败：${err.message}`; });
  backend.on('exit', (code, signal) => { exitMessage ||= `Java 后端已退出（code=${code}, signal=${signal || 'none'}）`; });
  for (let i = 0; i < 180; i += 1) {
    const ready = await new Promise(resolve => {
      const req = http.get(`http://127.0.0.1:${port}/api/config/integrations`, res => {
        res.resume(); resolve(res.statusCode && res.statusCode < 500);
      });
      req.setTimeout(500, () => { req.destroy(); resolve(false); });
      req.on('error', () => resolve(false));
    });
    if (ready) return;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  log.end();
  const tail = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').slice(-1800) : '';
  throw new Error(`${exitMessage || '后端启动超时'}。\n日志：${logPath}\n${tail}`);
}

function safeFile(root, requestPath) {
  const decoded = decodeURIComponent(requestPath.split('?')[0]);
  const relative = decoded === '/' ? 'index.html' : decoded.replace(/^[/\\]+/, '');
  const file = path.resolve(root, relative);
  return file.startsWith(root) ? file : null;
}

function startGateway(backendPort) {
  const dist = path.join(__dirname, '..', 'dist');
  gateway = http.createServer((req, res) => {
    const requestPath = req.url || '/';
    if (requestPath.startsWith('/api/') || requestPath === '/simulation.html') {
      const upstreamHeaders = { ...req.headers, host: `127.0.0.1:${backendPort}` };
      // 浏览器的 Origin 属于桌面本地网关，不应透传给只接受后端自身 Origin 的 Spring CORS。
      delete upstreamHeaders.origin;
      const upstream = http.request({ hostname: '127.0.0.1', port: backendPort, path: requestPath, method: req.method, headers: upstreamHeaders }, upstreamRes => {
        res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
        upstreamRes.pipe(res);
      });
      upstream.on('error', () => { res.writeHead(502); res.end('本地游戏服务暂不可用'); });
      req.pipe(upstream);
      return;
    }
    const file = safeFile(dist, requestPath);
    const target = file && fs.existsSync(file) && fs.statSync(file).isFile() ? file : path.join(dist, 'index.html');
    const contentType = target.endsWith('.js') ? 'text/javascript'
      : target.endsWith('.css') ? 'text/css'
        : target.endsWith('.png') ? 'image/png'
          : target.endsWith('.svg') ? 'image/svg+xml'
            : target.endsWith('.jpg') || target.endsWith('.jpeg') ? 'image/jpeg'
              : 'text/html';
    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(target).pipe(res);
  });
  return new Promise(resolve => gateway.listen(0, '127.0.0.1', () => resolve(gateway.address().port)));
}

async function createWindow() {
  const backendPort = await freePort();
  await startBackend(backendPort);
  const gatewayPort = await startGateway(backendPort);
  const win = new BrowserWindow({
    width: 1280, height: 720, minWidth: 960, minHeight: 540,
    backgroundColor: '#0c1322', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false },
  });
  win.setAspectRatio(16 / 9);
  win.once('ready-to-show', () => win.show());
  await win.loadURL(`http://127.0.0.1:${gatewayPort}`);
}

if (singleInstanceLock) {
  app.on('second-instance', () => {
    const win = BrowserWindow.getAllWindows()[0];
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    configureUpdater();
    return createWindow();
  }).catch(error => {
    dialog.showErrorBox('幻境之书无法启动', error.message);
    app.quit();
  });
}
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
app.on('before-quit', () => { if (gateway) gateway.close(); if (backend) backend.kill(); });
