// Seed a realistic user:profile:99 via raw Redis RESP (bypasses degraded extraction LLM).
const net = require('net');

const HOST = '127.0.0.1';
const PORT = 6379;
const KEY = 'user:profile:99';

const fields = {
  contracts: JSON.stringify(['买卖合同', '借款合同']),
  preferences: JSON.stringify({ 回答风格: '简洁', 关注领域: '合同违约' }),
  freqQA: JSON.stringify([
    { q: '违约金怎么算', clause: '违约金' },
    { q: '借款利息上限', clause: '民间借贷' }
  ]),
  corrections: JSON.stringify([]),
  lastActive: new Date().toISOString()
};

function bulk(s) {
  const b = Buffer.from(s, 'utf8');
  return '$' + b.length + '\r\n' + s + '\r\n';
}

// Build HSET command: HSET key f1 v1 f2 v2 ...
const hargs = [KEY];
for (const [k, v] of Object.entries(fields)) { hargs.push(k); hargs.push(v); }
let hset = '*' + (hargs.length + 1) + '\r\n';
hset += '$4\r\nHSET\r\n';
for (const a of hargs) hset += bulk(a);

// EXPIRE command
const expire = '*3\r\n$6\r\nEXPIRE\r\n' + bulk(KEY) + '$7\r\n2592000\r\n';

const payload = Buffer.from(hset + expire, 'utf8');

const socket = net.connect(PORT, HOST, () => {
  console.log('connected');
  socket.write(payload);
});
let data = '';
socket.on('data', d => { data += d.toString(); });
socket.on('end', () => {
  console.log('RESP responses:', JSON.stringify(data.trim()));
  console.log('Seeded fields:', Object.keys(fields).join(', '));
  socket.end();
});
socket.on('error', e => { console.error('SOCKET ERROR:', e.message); process.exit(2); });
setTimeout(() => { console.error('TIMEOUT'); process.exit(3); }, 8000);
