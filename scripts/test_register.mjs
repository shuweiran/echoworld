import http from 'http';
const data = JSON.stringify({id:"test123",name:"test",appearance:"test character",style:"anime style"});
const req = http.request({hostname:'localhost',port:8000,path:'/api/ai-image/character',method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}}, res => {
  let body = '';
  res.on('data', c => body += c);
  res.on('end', () => console.log('STATUS:', res.statusCode, '\nBODY:', body));
});
req.on('error', e => console.log('ERROR:', e.message));
req.write(data);
req.end();
