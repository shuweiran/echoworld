import { readFileSync } from 'fs';
import { spawn } from 'child_process';

const config = JSON.parse(readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json', 'utf8'));
const key = config.models.providers.deepseek.apiKey;

const env = { ...process.env, ROLEPLAY_LLM_API_KEY: key };
const jar = 'D:\\roleplay-java\\target\\roleplay-engine-1.0.0-SNAPSHOT.jar';

console.log(`Starting jar with key length: ${key.length}`);
const child = spawn('java', ['-jar', jar], {
  cwd: 'D:\\roleplay-java',
  env,
  detached: true,
  stdio: 'ignore'
});
child.unref();
console.log(`PID: ${child.pid}`);
