import http from 'http';

http.get('http://localhost:8000/api/ai-image/status', res => {
  let d = '';
  res.setEncoding('utf8');
  res.on('data', c => d += c);
  res.on('end', () => {
    const j = JSON.parse(d);
    for (const c of j.characters) {
      const imgs = Object.keys(c.images || {});
      console.log(`${c.id} | ${c.name} | frames: ${imgs.length} | avatar: ${imgs.includes('avatar')} | fullbody: ${imgs.includes('fullbody')}`);
    }
  });
});
