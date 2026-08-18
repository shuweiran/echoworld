// probe_mimo_vision.mjs — 探测小米 MiMo mimo-v2.5 是否支持图像输入（多模态视觉）
import { readFileSync } from 'node:fs';

const c = JSON.parse(readFileSync(process.env.USERPROFILE + '/.openclaw/openclaw.json', 'utf-8'));
const key = c.models.providers['xiaomimimo-map'].apiKey;
const b64 = readFileSync('D:/roleplay-java/work/vision_test.png').toString('base64');

const t0 = Date.now();
fetch('https://token-plan-cn.xiaomimimo.com/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key },
  body: JSON.stringify({
    model: 'mimo-v2.5',
    messages: [{
      role: 'user',
      content: [
        { type: 'text', text: '这是程序生成的地图截图。请审核布局质量（拥挤/空旷/协调）。只输出 JSON（不要任何推理过程/前言/解释）：{"score":0-100整数,"issues":[{"level":"low|medium|high","what":"具体问题（中文）","suggest":"建议（中文）"}]}，最多 3 条。' },
        { type: 'image_url', image_url: { url: 'data:image/png;base64,' + b64 } },
      ],
    }],
    max_tokens: 800,
  }),
}).then(async r => {
  const t = await r.text();
  console.log('status', r.status, 'elapsed_ms', Date.now() - t0);
  console.log(t.slice(0, 1200));
}).catch(e => console.log('ERR', String(e)));
