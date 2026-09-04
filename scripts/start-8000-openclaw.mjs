// OpenClaw launcher for roleplay-java (EchoWorld) service on port 8000.
// Reads LLM API key from a local secrets file at runtime (never on command line).
// Based on D:\echoworld\scripts\start-8000.mjs (latest project convention).
// P-0902-A (2026-09-02): LLM switched zhipu glm-5.3-flash -> DeepSeek (api.deepseek.com).
//   Key now read from D:\echoworld\.local\secrets\deepseek-api.key (gitignored .local/).
//   jar-internal application.yml (08-31 build, still zhipu) is overridden via CLI args below.
// P-0902-E (2026-09-02): TTS switched tp endpoint -> official api.xiaomimimo.com/v1 + sk key.
//   Reason: tp key (token-plan-cn) 429 quota exhausted; sk key + official endpoint verified 200 WAV.
//   sk key read at runtime from openclaw.json models.providers.xiaomimimo.apiKey (never in source).
//   Explicit env override also shields against the user-scope ROLEPLAY_MIMO_TTS_KEY (still tp key).
import { readFileSync, openSync, existsSync, mkdirSync } from 'fs';
import { spawn } from 'child_process';

const KEY_FILE = 'D:\\echoworld\\.local\\secrets\\deepseek-api.key';
const secret = existsSync(KEY_FILE) ? readFileSync(KEY_FILE, 'utf8').trim() : '';
if (!secret || !secret.startsWith('sk-')) { console.error('FAIL: no valid DeepSeek key in ' + KEY_FILE); process.exit(1); }

// P-0902-E: MiMo TTS sk key from openclaw.json (provider xiaomimimo). Read at runtime only; not logged.
const OPENCLAW_JSON = 'C:\\Users\\shuweiran\\.openclaw\\openclaw.json';
let mimoSk = '';
try {
  const oc = JSON.parse(readFileSync(OPENCLAW_JSON, 'utf8'));
  mimoSk = (oc.models && oc.models.providers && oc.models.providers.xiaomimimo && oc.models.providers.xiaomimimo.apiKey) || '';
} catch (e) { console.error('WARN: cannot read ' + OPENCLAW_JSON + ': ' + e.message); }
if (!mimoSk || !mimoSk.startsWith('sk-')) { console.error('FAIL: no valid xiaomimimo sk key in openclaw.json'); process.exit(1); }

const java = 'C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot\\bin\\java.exe';
const jar = 'D:\\echoworld\\target\\roleplay-engine-0.1.1.jar';
if (!existsSync(jar)) { console.error('FAIL: jar not found ' + jar); process.exit(1); }
if (!existsSync('C:\\tmp')) mkdirSync('C:\\tmp', { recursive: true }); // unixdomain tmpdir fix P-0827-D

const logDir = 'D:\\echoworld\\target';
const out = openSync(logDir + '\\server_openclaw.out.log', 'a');
const err = openSync(logDir + '\\server_openclaw.err.log', 'a');
const env = Object.assign({}, process.env, { ROLEPLAY_LLM_API_KEY: secret, ROLEPLAY_MIMO_TTS_KEY: mimoSk });
const args = [
  '-Djdk.net.unixdomain.tmpdir=C:\\tmp',
  '-jar', jar,
  '--server.port=8000', '--server.address=127.0.0.1',
  // P-0902-A: DeepSeek overrides (jar yml still points at open.bigmodel.cn/glm-5.3-flash).
  '--roleplay.llm.api-base=https://api.deepseek.com/v1/chat/completions',
  // P-0902-A（13:2x 终稿）：旧 jar（在运 08-31）无 thinking 参数控制，实测（.local/secrets/
  // oldjar-probe.mjs）：pro 默认思考在 callJson 小预算（Router 审批 800）下 reasoning 吃满→
  // content 空（finish=length r_tok=800）；对话 700 虽幸存属侥幸。flash 同形态全路径正常
  // （800→4.6s 合法 JSON；4000→12-27s 五层卡）。故旧 jar 期全局用 flash。
  // P-0902-B 新 jar（对话 thinking=disabled）打包后升级方案：本行改 deepseek-v4-pro，
  // 并加 --roleplay.llm.planner-model=deepseek-v4-flash（arbiter 保持 flash）。
  '--roleplay.llm.model=deepseek-v4-flash',
  '--roleplay.arbiter-llm.api-base=https://api.deepseek.com/v1/chat/completions',
  // P-0902-A 实测（.local/secrets/matrix-test.mjs 复现 Java 同款请求）：v4-pro+PLANNING 会被
  // ModelRequestProfile 自动加 thinking=enabled,reasoning_effort=high，max_tokens=4000 全被
  // reasoning 吃满→content 空→角色/地图等结构化生成全部静默失败回退默认值。
  // flash 在 PLANNING 下 thinking=disabled（代码内建豁免），实测 12-13s 产出合法五层卡 JSON。
  '--roleplay.arbiter-llm.model=deepseek-v4-flash',
  '--roleplay.monitor.fallback-model=deepseek-v4-flash', // fallback must exist on same endpoint (models list verified 2026-09-02)
  // P-0902-E: TTS official endpoint (chat/completions provider form unchanged; key via env above).
  '--roleplay.tts.mimo.base-url=https://api.xiaomimimo.com/v1',
];
const child = spawn(java, args, { cwd: 'D:\\echoworld', env, detached: true, stdio: ['ignore', out, err] });
child.unref();
console.log('STARTED pid=' + child.pid + ' keylen=' + secret.length + ' ttskeylen=' + mimoSk.length + ' jar=' + jar + ' log=' + logDir + '\\server_openclaw.out.log');
