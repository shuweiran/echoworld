import https from 'https';
import fs from 'fs';

const config = JSON.parse(fs.readFileSync('C:\\Users\\shuweiran\\.openclaw\\openclaw.json', 'utf8'));
const key = config.models.providers.deepseek.apiKey;

const data = JSON.stringify({model:"deepseek-chat",messages:[{role:"user",content:"say hi"}],max_tokens:10});
const req = https.request({hostname:'api.deepseek.com',path:'/chat/completions',method:'POST',headers:{'Authorization':`Bearer ${key}`,'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}}, res => {
  let body = '';
  res.on('data', c => body += c);
  res.on('end', () => console.log(`STATUS: ${res.statusCode}\nBODY: ${body.slice(0,500)}`));
});
req.on('error', e => console.log(`ERROR: ${e.message}`));
req.write(data);
req.end();
