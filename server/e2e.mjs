// memosync 端到端冒烟测试：对真实运行的服务实例按家庭使用场景走完整流程。
const BASE = process.env.BASE || 'http://127.0.0.1:5231';
const fs = await import('node:fs');
const httpMod = await import('node:http');

let pass = 0, fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log(`  ✅ ${name}`); }
  else { fail++; console.log(`  ❌ ${name} ${extra}`); }
}
async function api(method, path, token, body, raw) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  let payload;
  if (body instanceof Uint8Array) { payload = body; headers['Content-Type'] = raw?.ct || 'application/octet-stream'; }
  else if (body !== undefined) { payload = JSON.stringify(body); headers['Content-Type'] = 'application/json'; }
  const r = await fetch(BASE + path, { method, headers, body: payload });
  const buf = Buffer.from(await r.arrayBuffer());
  let json = null;
  try { json = JSON.parse(buf.toString('utf8')); } catch {}
  return { status: r.status, json, buf, headers: r.headers };
}
function uuidOf(s) {
  return '00000000-0000-4000-8000-' + [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7)
    .toString(16).padStart(12, '0').slice(-12).replace(/[^0-9a-f]/g, '0');
}

console.log('== 1. 管理页与认证 ==');
{
  const page = await fetch(BASE + '/');
  const html = await page.text();
  check('管理页 200 且包含标题', page.status === 200 && html.includes('家庭备忘录'));
  const bad = await api('POST', '/api/v1/auth/login', '', { username: 'admin', password: 'wrong' });
  check('错误密码 401', bad.status === 401);
}
const pwd = fs.readFileSync(process.env.PWD_FILE || 'data/initial_admin_password.txt', 'utf8').match(/初始密码: (\S+)/)[1];
const login = await api('POST', '/api/v1/auth/login', '', { username: 'admin', password: pwd, device_name: 'E2E-管理员' });
check('管理员登录', login.status === 200 && !!login.json.token);
const adminTok = login.json.token;
const me = await api('GET', '/api/v1/auth/me', adminTok);
check('me 返回 admin', me.json.username === 'admin' && me.json.role === 'admin');

console.log('== 2. 家庭成员与空间 ==');
const mk = await api('POST', '/api/v1/admin/users', adminTok, { username: 'mom', password: 'mom12345', nickname: '妈妈', role: 'member' });
check('创建成员 妈妈', mk.status === 200 && !!mk.json.id);
const mk2 = await api('POST', '/api/v1/admin/users', adminTok, { username: 'dad', password: 'dad12345', nickname: '爸爸', role: 'member' });
check('创建成员 爸爸', mk2.status === 200);
const momTok = (await api('POST', '/api/v1/auth/login', '', { username: 'mom', password: 'mom12345' })).json.token;
const spaces = (await api('GET', '/api/v1/spaces', adminTok)).json.spaces;
const momSpaces = (await api('GET', '/api/v1/spaces', momTok)).json.spaces;
const family = spaces.find(s => s.type === 'family');
check('双方家庭空间一致', family && family.id === momSpaces.find(s => s.type === 'family').id);
check('个人空间互不相同', spaces.find(s => s.type === 'personal').id !== momSpaces.find(s => s.type === 'personal').id);

console.log('== 3. 同步生命周期（创建/更新/冲突/删除/墓碑） ==');
const id1 = uuidOf('wifi密码');
let r = await api('POST', '/api/v1/sync/push', adminTok, { memos: [{ memo: { id: id1, space_id: family.id, type: 'note', title: 'WiFi 密码', content: '{"type":"note","text":"Family-5G / 88664402"}', color: 'lemon', client_mtime: Date.now() }, base_version: 0 }] });
check('创建备忘录 ok v1', r.json.results[0].status === 'ok' && r.json.results[0].version === 1);
r = await api('POST', '/api/v1/sync/push', adminTok, { memos: [{ memo: { id: id1, space_id: family.id, type: 'note', title: 'WiFi 密码(改)', content: '{"type":"note","text":"Family-5G / 88664402"}', color: 'lemon', pinned: true, client_mtime: Date.now() }, base_version: 1 }] });
check('正确基线更新 ok v2', r.json.results[0].status === 'ok' && r.json.results[0].version === 2);
r = await api('POST', '/api/v1/sync/push', adminTok, { memos: [{ memo: { id: id1, space_id: family.id, type: 'note', title: '过期的编辑', content: '{}', client_mtime: Date.now() }, base_version: 1 }] });
check('过期基线 → conflict 且带服务端版本', r.json.results[0].status === 'conflict' && r.json.results[0].memo.title === 'WiFi 密码(改)');

// 清单
const id2 = uuidOf('购物清单');
r = await api('POST', '/api/v1/sync/push', momTok, { memos: [{ memo: { id: id2, space_id: family.id, type: 'checklist', title: '购物清单', content: '{"type":"checklist","items":[{"text":"牛奶","done":true},{"text":"鸡蛋","done":false}]}', color: 'sky', client_mtime: Date.now() }, base_version: 0 }] });
check('妈妈创建共享清单 ok', r.json.results[0].status === 'ok');

// 删除 + 墓碑
const delAt = Date.now();
r = await api('POST', '/api/v1/sync/push', adminTok, { memos: [{ memo: { id: id1, space_id: family.id, type: 'note', title: 'x', content: '{}', client_mtime: Date.now(), deleted_at: delAt }, base_version: 2 }] });
check('软删除 ok', r.json.results[0].status === 'ok');

let pull = await api('GET', '/api/v1/sync/pull?since=0', momTok);
const memoIds = pull.json.changes.map(c => c.memo?.id);
check('成员能拉到家庭空间变更', memoIds.includes(id1) && memoIds.includes(id2));
check('删除以墓碑下发', pull.json.changes.find(c => c.memo?.id === id1)?.memo?.deleted_at > 0);
const cursor = pull.json.next_cursor;
pull = await api('GET', `/api/v1/sync/pull?since=${cursor}`, momTok);
check('游标推进后无重复', pull.json.changes.length === 0);

// 私人空间隔离
const adminPersonal = spaces.find(s => s.type === 'personal').id;
await api('POST', '/api/v1/sync/push', adminTok, { memos: [{ memo: { id: uuidOf('私人'), space_id: adminPersonal, type: 'note', title: '管理员的私事', content: '{}', client_mtime: Date.now() }, base_version: 0 }] });
pull = await api('GET', '/api/v1/sync/pull?since=0', momTok);
check('成员看不到他人私人备忘录', !pull.json.changes.some(c => c.memo?.id === uuidOf('私人')));
r = await api('POST', '/api/v1/sync/push', momTok, { memos: [{ memo: { id: uuidOf('越权'), space_id: adminPersonal, type: 'note', title: 'x', content: '{}', client_mtime: Date.now() }, base_version: 0 }] });
check('成员写入他人私人空间被拒', r.json.results[0].status === 'error' && r.json.results[0].message.includes('无权'));

console.log('== 4. 附件（上传/秒传/下载/引用） ==');
const img = Buffer.from('PNGDATA-' + 'x'.repeat(2048));
const up1 = await fetch(BASE + '/api/v1/attachments', { method: 'POST', headers: { Authorization: 'Bearer ' + momTok, 'Content-Type': 'multipart/form-data; boundary=X' }, body: multipartBody('file', 'photo.png', img) });
const up1j = await up1.json();
const crypto = await import('node:crypto');
const wantSha = crypto.createHash('sha256').update(img).digest('hex');
check('上传返回正确 sha256', up1.status === 200 && up1j.sha256 === wantSha);
const up2 = await fetch(BASE + '/api/v1/attachments', { method: 'POST', headers: { Authorization: 'Bearer ' + momTok, 'Content-Type': 'multipart/form-data; boundary=X' }, body: multipartBody('file', 'photo-again.png', img) });
const up2j = await up2.json();
check('同内容秒传（同 id）', up2j.sha256 === up1j.sha256);
const chk = await api('POST', '/api/v1/attachments/check', momTok, { sha256s: [wantSha, 'ab'.repeat(32)] });
check('check 秒传接口', chk.json.existing[wantSha] === img.length && !chk.json.existing['ab'.repeat(32)]);
const imgMemo = uuidOf('带图备忘');
r = await api('POST', '/api/v1/sync/push', momTok, { memos: [{ memo: { id: imgMemo, space_id: family.id, type: 'note', title: '出游照片', content: '{"type":"note","text":"看看"}', color: 'lav', client_mtime: Date.now(), attachments: [{ id: wantSha, sha256: wantSha, filename: 'photo.png', mime: 'image/png', size: img.length }] }, base_version: 0 }] });
check('带附件备忘录推送 ok', r.json.results[0].status === 'ok');
r = await api('POST', '/api/v1/sync/push', momTok, { memos: [{ memo: { id: uuidOf('坏图'), space_id: family.id, type: 'note', title: 'x', content: '{}', client_mtime: Date.now(), attachments: [{ id: 'cd'.repeat(32), sha256: 'cd'.repeat(32), filename: 'x', size: 1 }] }, base_version: 0 }] });
check('引用未上传附件被拒', r.json.results[0].status === 'error');
const dl = await fetch(`${BASE}/api/v1/attachments/${wantSha}`, { headers: { Authorization: 'Bearer ' + momTok } });
check('下载内容一致', dl.status === 200 && Buffer.compare(Buffer.from(await dl.arrayBuffer()), img) === 0);
const dl304 = await fetch(`${BASE}/api/v1/attachments/${wantSha}`, { headers: { Authorization: 'Bearer ' + momTok, 'If-None-Match': `"${wantSha}"` } });
check('ETag 304 生效', dl304.status === 304);

console.log('== 5. 配对码接入 ==');
const momId = (await api('GET', '/api/v1/admin/users', adminTok)).json.users.find(u => u.username === 'mom').id;
const pc = await api('POST', '/api/v1/admin/pair-code', adminTok, { user_id: momId, ttl_hours: 24 });
check('生成配对码（8 位 + 地址）', pc.json.code?.length === 8 && !!pc.json.url);
const pair = await api('POST', '/api/v1/auth/pair', '', { code: pc.json.code.toLowerCase(), device_name: '爸爸的旧手机给妈妈用' });
check('配对码兑换成功（大小写不敏感）', pair.status === 200 && pair.json.user.username === 'mom');
const rePair = await api('POST', '/api/v1/auth/pair', '', { code: pc.json.code });
check('配对码一次性', rePair.status === 400);

console.log('== 6. 管理：设备/统计/备份/导出 ==');
const devs = (await api('GET', '/api/v1/admin/devices', adminTok)).json.devices;
check('设备列表含 E2E 设备', devs.some(d => d.name === 'E2E-管理员') && devs.some(d => d.name?.includes('爸爸的旧手机')));
const stats = (await api('GET', '/api/v1/admin/stats', adminTok)).json;
check('统计正常（附件≥1 备份前 memo≥2）', stats.attachments >= 1 && stats.memos >= 2 && stats.users === 3);
const bk = await api('POST', '/api/v1/admin/backup', adminTok);
check('手动备份', bk.status === 200 && bk.json.name.startsWith('backup-'));
const bks = (await api('GET', '/api/v1/admin/backups', adminTok)).json.backups;
check('备份列表', bks.length >= 1);
const exp = await fetch(BASE + '/api/v1/admin/export', { headers: { Authorization: 'Bearer ' + adminTok } });
const zipBuf = Buffer.from(await exp.arrayBuffer());
// zip 条目默认 deflate 压缩：只验 PK 头与解压后的目录名（用本地文件头中未压缩的文件名不存在，
// 故改用「能被解压工具识别」的最朴素特征：EOCD 记录必含中央目录结束签名）。
const eocd = zipBuf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
check('导出为合法 zip（PK 头 + EOCD）', zipBuf.slice(0, 2).toString() === 'PK' && eocd > 0 && zipBuf.length > 1000);

console.log('== 6.5 数字资产（type=asset） ==');
{
  const expMs = 1798761600000; // 2027-01-01
  const content = JSON.stringify({
    type: 'asset', category: 'video', account: '13800138000',
    password: 'vip-pass-8888', extra: '妈妈手机号开的，已关自动续费',
    expires_at: expMs, remind_before: 7,
  });
  let r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: uuidOf('爱奇艺VIP'), space_id: family.id, type: 'asset', title: '爱奇艺黄金VIP',
      content, color: 'apricot', client_mtime: Date.now(), remind_at: expMs - 7 * 86400000 }, base_version: 0 }],
  });
  check('创建资产备忘录 ok', r.json.results[0].status === 'ok');
  const pullAssets = await api('GET', '/api/v1/sync/pull?since=0', momTok);
  const asset = pullAssets.json.changes.find(c => c.memo?.id === uuidOf('爱奇艺VIP'))?.memo;
  check('家人同步到资产且密码字段完整', !!asset && asset.content.includes('"password":"vip-pass-8888"') && asset.remind_at === expMs - 7 * 86400000);
  // 资产更新（续费）：版本 +1，家人可见新到期时间
  const newContent = JSON.stringify({ ...JSON.parse(content), expires_at: expMs + 31 * 86400000 });
  r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: uuidOf('爱奇艺VIP'), space_id: family.id, type: 'asset', title: '爱奇艺黄金VIP',
      content: newContent, color: 'apricot', client_mtime: Date.now(), remind_at: expMs + 31 * 86400000 - 7 * 86400000 }, base_version: 1 }],
  });
  check('资产续费更新 ok v2', r.json.results[0].status === 'ok' && r.json.results[0].version === 2);
  const after = await api('GET', '/api/v1/sync/pull?since=0', momTok);
  check('家人看到新到期时间', after.json.changes.some(c => c.memo?.content.includes(String(expMs + 31 * 86400000))));
}

console.log('== 6.8 SSE 实时推送 ==');
{
  const ctrl = new AbortController();
  const es = await fetch(BASE + '/api/v1/sync/events', {
    headers: { Authorization: 'Bearer ' + adminTok },
    signal: ctrl.signal,
  });
  check('SSE 连接建立（text/event-stream）', es.status === 200 && (es.headers.get('content-type') || '').includes('text/event-stream'));
  const reader = es.body.getReader();
  const dec = new TextDecoder();
  const gotEvent = (async () => {
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) return false;
      buf += dec.decode(value, { stream: true });
      if (buf.includes('event: change')) return true;
    }
  })();
  await new Promise(r => setTimeout(r, 300)); // 等订阅在服务端建立
  const t0 = Date.now();
  const pushRes = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: uuidOf('sse-触发'), space_id: family.id, type: 'note', title: '实时推送', content: '{"type":"note","text":"x"}', client_mtime: Date.now() }, base_version: 0 }],
  });
  if (!pushRes.json.results[0] || pushRes.json.results[0].status !== 'ok') {
    check('SSE 触发用的推送成功', false, JSON.stringify(pushRes.json));
  }
  const ok = await Promise.race([gotEvent, new Promise(r => setTimeout(() => r(false), 3000))]);
  check(`推送变更后 3 秒内收到 change 事件（${Date.now() - t0}ms）`, ok === true);
  const noAuth = await fetch(BASE + '/api/v1/sync/events');
  check('未认证 SSE 401', noAuth.status === 401);
  ctrl.abort();
}

console.log('== 6.9 清单条目级合并与标签 ==');
{
  const cid = uuidOf('e2e清单');
  const cl = (items) => JSON.stringify({ type: 'checklist', text: '', items: items.map(([t, d]) => ({ text: t, done: d })) });
  let r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: cid, space_id: family.id, type: 'checklist', title: 'E2E 清单',
      content: cl([['牛奶', false], ['鸡蛋', false]]), color: 'sky', client_mtime: Date.now(), tags: ['购物', '家用'] }, base_version: 0 }],
  });
  check('创建带标签清单 ok', r.json.results[0].status === 'ok');
  r = await api('POST', '/api/v1/sync/push', momTok, {
    memos: [{ memo: { id: cid, space_id: family.id, type: 'checklist', title: 'E2E 清单',
      content: cl([['牛奶', true], ['鸡蛋', false]]), client_mtime: Date.now(), tags: ['购物', '家用'] }, base_version: 1 }],
  });
  check('妈妈勾选牛奶 ok v2', r.json.results[0].status === 'ok' && r.json.results[0].version === 2);
  // 管理员基于过期 v1 加水果 → 条目级合并而不是冲突
  r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: cid, space_id: family.id, type: 'checklist', title: 'E2E 清单',
      content: cl([['牛奶', false], ['鸡蛋', false], ['水果', false]]), client_mtime: Date.now(), tags: ['购物', '家用'] }, base_version: 1 }],
  });
  check('过期基线走条目合并（非冲突）ok v3', r.json.results[0].status === 'ok' && r.json.results[0].version === 3);
  const merged = JSON.parse(r.json.results[0].memo.content);
  const milk = merged.items.find(i => i.text === '牛奶');
  check('合并保留勾选并追加水果', milk?.done === true && merged.items.length === 3);
  // 标签同步给家人（pull 条目携带备忘录当前状态）
  const p2 = await api('GET', '/api/v1/sync/pull?since=0', momTok);
  const tagged = p2.json.changes.find(c => c.memo?.id === cid)?.memo;
  check('标签同步到家人', JSON.stringify(tagged?.tags) === JSON.stringify(['购物', '家用']));
}

console.log('== 6.95 多条提醒 / 工作组 / webhook ==');
{
  // 1) 多条提醒
  const mid = uuidOf('季度总结');
  const at1 = Date.now() + 86400000, at2 = Date.now() + 172800000;
  let r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: mid, space_id: family.id, type: 'note', title: '写季度总结',
      content: '{"type":"note","text":"怕忘"}', client_mtime: Date.now(), remind_ats: [at2, at1, at1] }, base_version: 0 }],
  });
  const ats = r.json.results[0].memo?.remind_ats;
  check('多提醒去重升序(2条)且 remind_at=最早', JSON.stringify(ats) === JSON.stringify([at1, at2]) && r.json.results[0].memo.remind_at === at1, JSON.stringify(r.json.results[0]).slice(0,200));
  // 2) 工作组
  r = await api('POST', '/api/v1/spaces', momTok, { name: 'E2E工作组' });
  const grp = r.json;
  check('妈妈建工作组 ok', r.status === 200 && grp.type === 'group');
  r = await api('POST', `/api/v1/spaces/${grp.id}/members`, momTok, { username: 'dad' });
  check('按用户名拉人 ok', r.status === 200);
  const gid = uuidOf('工作笔记');
  r = await api('POST', '/api/v1/sync/push', momTok, {
    memos: [{ memo: { id: gid, space_id: grp.id, type: 'note', title: '工作笔记', content: '{}', client_mtime: Date.now() }, base_version: 0 }],
  });
  check('组内备忘录创建 ok', r.json.results[0].status === 'ok', JSON.stringify(r.json.results[0]).slice(0,200));
  r = await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: uuidOf('越权组'), space_id: grp.id, type: 'note', title: 'x', content: '{}', client_mtime: Date.now() }, base_version: 0 }],
  });
  check('非组员写入被拒', r.json.results[0].status === 'error');
  // 3) webhook：本地捕获服务器模拟飞书
  const cap = await new Promise(resolve => {
    const chunks = [];
    const srv = httpMod.createServer((req, res) => {
      let b = ''; req.on('data', c => b += c); req.on('end', () => { chunks.push(b); res.end(JSON.stringify({ code: 0 })); });
    });
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port, chunks }));
  });
  await api('PUT', '/api/v1/admin/notify', adminTok, { feishu_webhook: `http://127.0.0.1:${cap.port}/hook` });
  const past = Date.now() - 3600000;
  await api('POST', '/api/v1/sync/push', adminTok, {
    memos: [{ memo: { id: uuidOf('到期提醒'), space_id: family.id, type: 'note', title: '缴物业费',
      content: '{}', client_mtime: Date.now(), remind_ats: [past] }, base_version: 0 }],
  });
  r = await api('POST', '/api/v1/admin/notify/fire', adminTok);
  check('触发扫描返回 1 条', r.json.count === 1);
  await new Promise(rs => setTimeout(rs, 300));
  const msg = JSON.parse(cap.chunks[0] || '{}');
  check('飞书收到提醒文本', msg.msg_type === 'text' && (msg.content?.text || '').includes('缴物业费'));
  cap.srv.close();
  // 重复触发不重发
  r = await api('POST', '/api/v1/admin/notify/fire', adminTok);
  check('已提醒不重复触发', r.json.count === 0);
}

console.log('== 7. 改密与设备下线 ==');const pw1 = await api('POST', '/api/v1/auth/password', momTok, { old_password: 'mom12345', new_password: 'mom54321' });
check('成员自助改密', pw1.status === 200);
const relog = await api('POST', '/api/v1/auth/login', '', { username: 'mom', password: 'mom54321' });
check('新密码可登录', relog.status === 200);
const momDev = devs.find(d => d.name?.includes('爸爸的旧手机'));
if (momDev) {
  const kick = await api('DELETE', '/api/v1/admin/devices/' + momDev.id, adminTok);
  const meAfter = await api('GET', '/api/v1/auth/me', pair.json.token);
  check('踢设备后 token 失效', kick.status === 200 && meAfter.status === 401);
}

console.log(`\n结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);

function multipartBody(field, filename, data) {
  const CRLF = '\r\n';
  const pre = Buffer.from(`--X${CRLF}Content-Disposition: form-data; name="${field}"; filename="${filename}"${CRLF}Content-Type: application/octet-stream${CRLF}${CRLF}`);
  const post = Buffer.from(`${CRLF}--X--${CRLF}`);
  return Buffer.concat([pre, data, post]);
}
