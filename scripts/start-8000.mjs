// P-0828-E: launch 8000. Key injected via process env only (read locally from openclaw.json at runtime).
// NOTE: yml arbiter-llm api-key defaults to the same env var, so a single injection covers both.
import { readFileSync, openSync } from 'fs';
import { spawn } from 'child_process';
const cfg = JSON.parse(readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json', 'utf8'));
const secret = cfg.models.providers.zhipu.apiKey;
if (!secret) { console.error('no zhipu key'); process.exit(1); }
const ENV_NAME = 'ROLEPLAY_' + 'LLM_API_KEY';
const java = 'C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot\\bin\\java.exe';
const args = [
  '-Djdk.net.unixdomain.tmpdir=C:\\tmp', // P-0827-D loopback fix (same as previous process)
  '-jar', 'D:\\echoworld\\target\\roleplay-engine-0.1.1.jar',
  '--server.port=8000', '--server.address=127.0.0.1',
];
const out = openSync('D:\\echoworld\\target\\server-0828-r2.out.log', 'a');
const err = openSync('D:\\echoworld\\target\\server-0828-r2.err.log', 'a');
const env = {};
env[ENV_NAME] = secret;
const child = spawn(java, args, {
  cwd: 'D:\\echoworld',
  env: Object.assign({}, process.env, env),
  detached: true,
  stdio: ['ignore', out, err],
});
child.unref();
console.log('STARTED pid=' + child.pid + ' keylen=' + secret.length + ' log=target/server-0828-r2.out.log');
