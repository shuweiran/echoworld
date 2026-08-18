import http from 'http';

// Test with Chinese character ID (like what frontend might send for custom roles)
const data = JSON.stringify({id:"测试角色",name:"测试角色",appearance:"anime character",style:"retro game"});
const req = http.request({hostname:'localhost',port:8000,path:'/api/ai-image/character',method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}}, res => {
  let body = '';
  res.on('data', c => body += c);
  res.on('end', () => console.log(`Chinese ID → ${res.statusCode} ${body}`));
});
req.on('error', e => console.log(`ERROR: ${e.message}`));
req.write(data);
req.end();
