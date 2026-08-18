import http from 'http';

// Simulate what the frontend does with an empty-appearance role
const role = { id: "test-empty", name: "测试角色", intro: "", personality: "", talkStyle: "", background: "", motive: "" };
const parts = [role.intro, role.personality, role.talkStyle, role.background, role.motive].filter(Boolean).map(s => String(s).trim());
const appearance = parts.join('，').slice(0, 240) || `${role.name}，动漫风格角色，精致五官，全身立绘`;
const style = 'retro game character art style, 16-bit pixel art, clean outlines, flat colors';

console.log("appearance:", JSON.stringify(appearance));
console.log("style:", JSON.stringify(style));

const data = JSON.stringify({ id: role.id, name: role.name, appearance, style });
console.log("body:", data);

const req = http.request({hostname:'localhost',port:8000,path:'/api/ai-image/character',method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}}, res => {
  let body = '';
  res.on('data', c => body += c);
  res.on('end', () => console.log(`STATUS: ${res.statusCode} BODY: ${body}`));
});
req.on('error', e => console.log(`ERROR: ${e.message}`));
req.write(data);
req.end();
