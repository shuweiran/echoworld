// probe_mimo.mjs — 探测小米 MiMo mimo-v2.5 生成结构树（模拟 StructureLlmBlueprint 请求）
import { readFileSync } from 'node:fs';
const c = JSON.parse(readFileSync(process.env.USERPROFILE + '/.openclaw/openclaw.json', 'utf-8'));
const key = c.models.providers['xiaomimimo-map'].apiKey;

const prompt = [
  '你是大型角色扮演地图的结构设计师。根据主题输出一份「结构树」JSON，描述一个可探索的大型区域（城堡/庄园/街区/地牢/飞船/小镇等）由哪些部分组成、如何连接。',
  '主题：雾隐研究所',
  '风格：科幻',
  '硬性要求：',
  '1. 只输出语义结构，绝不输出坐标/网格/瓦片/像素等几何数据；',
  '2. 顶层 root 的 children 为 8-14 个节点：type ∈ building/zone（开放区域，open=true）/room；叶子节点必须带 template 键（如 great_hall/gatehouse/kitchen/gu_bedroom/garden/storage/treasury/shop/house/dungeon_cell/entrance/boss_room…）或 type=zone；',
  '3. 每个节点字段：id（英文短名，全局唯一）、type、name（中文）、template（尽量复用模板键）、size [w,h]（4-40）；',
  '4. relations：8-14 条 {from, to, kind}，kind ∈ adjacent/connects；整棵树必须连通；',
  '5. 输出纯 JSON（不要 markdown 代码块）：{"version":1,"kind":"custom","root":{"id":"<主题>","type":"structure","name":"<中文名>","children":[...]},"relations":[...]}',
  '6. 请直接输出 JSON，不要任何推理过程/解释/前言（不要 reasoning，先给结论再给结构）。',
].join('\n');

const t0 = Date.now();
fetch('https://token-plan-cn.xiaomimimo.com/v1/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key },
  body: JSON.stringify({
    model: 'mimo-v2.5',
    messages: [
      { role: 'system', content: '你是一个角色扮演主控（DM）。必须严格按照要求的JSON格式回复。' },
      { role: 'user', content: prompt },
    ],
    max_tokens: 8000,
    temperature: 0.1,
  }),
}).then(async r => {
  const t = await r.text();
  console.log('status', r.status, 'elapsed_ms', Date.now() - t0, 'len', t.length);
  console.log(t.slice(0, 600));
}).catch(e => console.log('ERR', String(e)));
