import http from 'http';

// Test with various IDs to find what causes 400
const testCases = [
  { id: "test-ok", name: "test", appearance: "test character", style: "anime" },
  { id: "abc-123", name: "小铃", appearance: "silver hair", style: "retro" },
  { id: "", name: "test", appearance: "test", style: "test" },
  { id: "test", name: "", appearance: "test", style: "test" },
  { id: "test", name: "test", appearance: "", style: "test" },
  { id: "test", name: "test", appearance: "test", style: "" },
  { id: "hello world", name: "test", appearance: "test", style: "test" },
];

for (const tc of testCases) {
  const data = JSON.stringify(tc);
  const req = http.request({hostname:'localhost',port:8000,path:'/api/ai-image/character',method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}}, res => {
    let body = '';
    res.on('data', c => body += c);
    res.on('end', () => console.log(`id="${tc.id}" name="${tc.name}" → ${res.statusCode} ${body}`));
  });
  req.on('error', e => console.log(`id="${tc.id}" → ERROR: ${e.message}`));
  req.write(data);
  req.end();
}
