// OpenClaw launcher for roleplay-java (EchoWorld) service on port 8000.
// Reads LLM API key from a local secrets file at runtime (never on command line).
// Based on D:\echoworld\scripts\start-8000.mjs (latest project convention).
// P-0902-A (2026-09-02): LLM switched zhipu glm-5.3-flash -> DeepSeek (api.deepseek.com).
//   Key now read from D:\echoworld\.local\secrets\deepseek-api.key (gitignored .local/).
//   jar-internal application.yml (08-31 build, still zhipu) is overridden via CLI args below.
import { readFileSync, openSync, existsSync, mkdirSync } from 'fs';
import { spawn } from 'child_process';

const KEY_FILE = 'D:\\echoworld\\.local\\secrets\\deepseek-api.key';
const secret = existsSync(KEY_FILE) ? readFileSync(KEY_FILE, 'utf8').trim() : '';
if (!secret || !secret.startsWith('sk-')) { console.error('FAIL: no valid DeepSeek key in ' + KEY_FILE); process.exit(1); }

const java = 'C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot\\bin\\java.exe';
const jar = 'D:\\echoworld\\target\\roleplay-engine-0.1.1.jar';
if (!existsSync(jar)) { console.error('FAIL: jar not found ' + jar); process.exit(1); }
if (!existsSync('C:\\tmp')) mkdirSync('C:\\tmp', { recursive: true }); // unixdomain tmpdir fix P-0827-D

const logDir = 'D:\\echoworld\\target';
const out = openSync(logDir + '\\server_openclaw.out.log', 'a');
const err = openSync(logDir + '\\server_openclaw.err.log', 'a');
const env = Object.assign({}, process.env, { ROLEPLAY_LLM_API_KEY: secret });
const args = [
  '-Djdk.net.unixdomain.tmpdir=C:\\tmp',
  '-jar', jar,
  '--server.port=8000', '--server.address=127.0.0.1',
  // P-0902-A: DeepSeek overrides (jar yml still points at open.bigmodel.cn/glm-5.3-flash).
  '--roleplay.llm.api-base=https://api.deepseek.com/v1/chat/completions',
  // P-0902-A 实测修正（13:10）：运行中 jar（08-31）无 planner-model/ModelRequestProfile（字节码
  // grep 取证）——callJson（剧本 4000/地图/审批 600-1500 等）实走主 client 的 llm.model，
  // v4-pro 在 DeepSeek 默认 thinking 下 content 被 reasoning 吃满→静默回退（.local/secrets/
  // matrix-test.mjs 复现）。故 llm.model=flash（旧 jar 下 callJson+对话均实测正常）。
  // 注：源码工作区（P-0902-B，未打包）已有 thinking=disabled/任务路由——下次 mvn package
  // 后若主人要升级主链路到 pro，改为：llm.model=deepseek-v4-pro + 恢复下方 planner-model 行。
  '--roleplay.llm.model=deepseek-v4-flash',
  '--roleplay.arbiter-llm.api-base=https://api.deepseek.com/v1/chat/completions',
  // P-0902-A 实测（.local/secrets/matrix-test.mjs 复现 Java 同款请求）：v4-pro+PLANNING 会被
  // ModelRequestProfile 自动加 thinking=enabled,reasoning_effort=high，max_tokens=4000 全被
  // reasoning 吃满→content 空→角色/地图等结构化生成全部静默失败回退默认值。
  // flash 在 PLANNING 下 thinking=disabled（代码内建豁免），实测 12-13s 产出合法五层卡 JSON。
  '--roleplay.arbiter-llm.model=deepseek-v4-flash',
  '--roleplay.monitor.fallback-model=deepseek-v4-flash', // fallback must exist on same endpoint (models list verified 2026-09-02)
];
const child = spawn(java, args, { cwd: 'D:\\echoworld', env, detached: true, stdio: ['ignore', out, err] });
child.unref();
console.log('STARTED pid=' + child.pid + ' keylen=' + secret.length + ' jar=' + jar + ' log=' + logDir + '\\server_openclaw.out.log');
