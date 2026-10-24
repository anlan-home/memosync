// 真机 webhook 验证：捕获服务器监听局域网 IP，让 NAS 回调过来
// 用法: BASE=http://NAS:5232 ADMIN_PASSWORD=xxx LOCAL_IP=192.168.5.13 node e2e-webhook.mjs
import http from 'node:http';

const BASE = process.env.BASE || 'http://192.168.5.211:5232';
const PWD = process.env.ADMIN_PASSWORD || '';
const LOCAL_IP = process.env.LOCAL_IP || '192.168.5.13';

let pass = 0, fail = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log(`  ✅ ${name}`); }
  else { fail++; console.log(`  ❌ ${name} ${extra}`); }
};
async function api(method, path, token, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  return { status: r.status, json: j };
}
const uuidOf = s => '00000000-0000-4000-8000-' +
  [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7).toString(16).padStart(12, '0').slice(-12)
    .replace(/[^0-9a-f]/g, '0');

// 捕获服务器：监听所有网卡，NAS 才能访问
const received = [];
let server;
await new Promise(resolve => {
  server = http.createServer((req, res) => {
    let body = '';
    req.on('data', c => body += c);
    req.on('end', () => {
      received.push({ url: req.url, body });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: 0, errcode: 0 }));
    });
  });
  server.listen(0, '0.0.0.0', () => resolve());
});
const port = server.address().port;
console.log(`捕获服务器: http://${LOCAL_IP}:${port}/hook`);

try {
  const login = await api('POST', '/api/v1/auth/login', '', { username: 'admin', password: PWD, device_name: 'webhook-e2e' });
  if (login.status !== 200) { console.log('登录失败', JSON.stringify(login.json)); process.exit(1); }
  const tok = login.json.token;
  const family = (await api('GET', '/api/v1/spaces', tok)).json.spaces.find(s => s.type === 'family').id;

  // 1) 飞书
  await api('PUT', '/api/v1/admin/notify', tok, {
    feishu_webhook: `http://${LOCAL_IP}:${port}/hook/feishu`,
    dingtalk_webhook: '',
    dingtalk_secret: '',
  });
  const t1 = await api('POST', '/api/v1/admin/notify/test', tok, { channel: 'feishu' });
  await new Promise(r => setTimeout(r, 800));
  const feishuHit = received.find(x => x.url.includes('/feishu'));
  check('飞书测试消息到达', t1.status === 200 && !!feishuHit, JSON.stringify(t1.json));
  if (feishuHit) {
    const body = JSON.parse(feishuHit.body);
    check('飞书消息格式正确（msg_type=text）', body.msg_type === 'text' && !!body.content?.text);
  }

  // 2) 到期提醒推送
  const id = uuidOf('真机提醒' + Date.now());
  const past = Date.now() - 60000;
  const push = await api('POST', '/api/v1/sync/push', tok, {
    memos: [{ memo: { id, space_id: family, type: 'note', title: '真机提醒测试',
      content: '{"type":"note","text":"NAS 推送验证"}', client_mtime: Date.now(), remind_ats: [past] }, base_version: 0 }],
  });
  check('创建到期提醒', push.json.results?.[0]?.status === 'ok', JSON.stringify(push.json).slice(0, 120));
  received.length = 0;
  const fire = await api('POST', '/api/v1/admin/notify/fire', tok);
  check('触发扫描至少返回 1 条', fire.json.count >= 1, JSON.stringify(fire.json).slice(0, 160));
  await new Promise(r => setTimeout(r, 800));
  const hit2 = received.find(x => x.url.includes('/feishu'));
  check('NAS 真的把提醒推给了 webhook', !!hit2, `收到 ${received.length} 个请求`);
  if (hit2) {
    const allText = received
      .map(x => { try { return JSON.parse(x.body).content?.text || ''; } catch { return ''; } })
      .join(" | ");
    check('推送内容含本次提醒标题', allText.includes('真机提醒测试'), allText.slice(0, 200));
  }
  const fire2 = await api('POST', '/api/v1/admin/notify/fire', tok);
  check('已提醒不重复触发', fire2.json.count === 0, JSON.stringify(fire2.json).slice(0, 120));

  // 3) 钉钉加签
  received.length = 0;
  await api('PUT', '/api/v1/admin/notify', tok, {
    dingtalk_webhook: `http://${LOCAL_IP}:${port}/hook/ding?access_token=abc`,
    dingtalk_secret: 'SECtest123',
  });
  const t2 = await api('POST', '/api/v1/admin/notify/test', tok, { channel: 'dingtalk' });
  await new Promise(r => setTimeout(r, 800));
  const dingHit = received.find(x => x.url.includes('/ding'));
  check('钉钉测试消息到达', t2.status === 200 && !!dingHit, JSON.stringify(t2.json));
  if (dingHit) {
    const u = new URL('http://x' + dingHit.url);
    check('钉钉请求带 timestamp/sign 且保留 access_token',
      !!u.searchParams.get('timestamp') && !!u.searchParams.get('sign') && u.searchParams.get('access_token') === 'abc',
      dingHit.url);
  }
  // 清理配置，避免影响后续
  await api('PUT', '/api/v1/admin/notify', tok, { feishu_webhook: '', dingtalk_webhook: '', dingtalk_secret: '' });
} finally {
  server.close();
}
console.log(`\n结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);
