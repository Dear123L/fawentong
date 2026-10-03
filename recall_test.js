// LTM cross-session recall demo: send a question, report whether the
// user long-term profile was injected (【结合您的长期画像】 marker).
const http = require('http');
const { execSync } = require('child_process');

const TOKEN = require('fs').readFileSync(require('path').join(__dirname, 'demotoken.txt'), 'utf8').trim();
const sid = process.argv[2];
const q = process.argv.slice(3).join(' ');

const params = new URLSearchParams({ kbId: '1', sessionId: sid, question: q });
const opts = {
  hostname: 'localhost', port: 8082, path: '/api/rag/chatAgent/multi?' + params.toString(),
  headers: { Authorization: 'Bearer ' + TOKEN }
};
http.get(opts, res => {
  let s = '';
  res.on('data', d => s += d);
  res.on('end', () => {
    try {
      const j = JSON.parse(s);
      const a = j.data || '';
      const m = a.match(/【结合您的长期画像】([\s\S]*?)(?:\n|$)/);
      if (m) console.log('  INJECTED=YES ->', m[1].trim().slice(0, 90));
      else console.log('  INJECTED=NO (profile not relevant / empty)');
    } catch (e) { console.log('  raw:', s.slice(0, 120)); }
  });
}).on('error', e => console.log('ERR', e.message));
