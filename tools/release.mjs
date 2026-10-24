// 可复用的 Release 发布脚本：读版本号 → 创建/更新 Release → 上传资产 → 校验下载
// 用法: node tools/release.mjs ["发布说明标题"]
import fs from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
process.chdir(ROOT);

const REPO = 'anlan-home/memosync';
const props = Object.fromEntries(
  fs.readFileSync('version.properties', 'utf8').split('\n')
    .filter(l => l.includes('=')).map(l => l.split('=').map(s => s.trim())),
);
const VERSION = `${props.major}.${props.minor}.${props.patch}`;
const TAG = `v${VERSION}`;

const TOKEN = execSync('git credential fill', {
  input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8',
}).split('\n').filter(l => l.startsWith('password='))[0].slice('password='.length).trim();
if (!TOKEN) { console.error('无法获取 GitHub 凭据'); process.exit(1); }

const api = async (method, url, body, isUpload = false) => {
  const res = await fetch((isUpload ? 'https://uploads.github.com' : 'https://api.github.com') + url, {
    method,
    headers: {
      Authorization: `token ${TOKEN}`,
      Accept: 'application/vnd.github+json',
      ...(body ? { 'Content-Type': isUpload ? 'application/octet-stream' : 'application/json' } : {}),
    },
    body: body ? (isUpload ? body : JSON.stringify(body)) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text };
};

const assets = [
  { file: `dist/FamilyMemo-${TAG}-release.apk`, type: 'application/vnd.android.package-archive', desc: 'Android 手机安装' },
  { file: `dist/memosync_${VERSION}_x86_64.fpk`, type: 'application/octet-stream', desc: '飞牛 fnOS x86 本地安装' },
  { file: `dist/memosync_${VERSION}_arm64.fpk`, type: 'application/octet-stream', desc: '飞牛 fnOS ARM 本地安装' },
].filter(a => fs.existsSync(a.file));

if (!assets.length) { console.error('dist 下找不到本版本产物，请先构建'); process.exit(1); }

const sha = f => execSync(`certutil -hashfile "${f}" SHA256`).toString().split('\n')[1].trim();
const size = f => (fs.statSync(f).size / 1048576).toFixed(1) + ' MB';

const body = [
  fs.readFileSync(process.argv[3] || 'RELEASE_NOTES.md', 'utf8'),
  '',
  '## 下载',
  '',
  '| 文件 | 大小 | 用途 |',
  '|---|---|---|',
  ...assets.map(a => `| \`${path.basename(a.file)}\` | ${size(a.file)} | ${a.desc} |`),
  '',
  '### 校验值（SHA256）',
  '',
  '```',
  ...assets.map(a => `${sha(a.file)}  ${path.basename(a.file)}`),
  '```',
].join('\n');

// 1) 创建或更新 Release
const existing = await api('GET', `/repos/${REPO}/releases/tags/${TAG}`);
let rel = existing.status === 200 ? existing.json : null;
if (rel) {
  console.log(`Release ${TAG} 已存在，更新说明…`);
  const upd = await api('PATCH', `/repos/${REPO}/releases/${rel.id}`, {
    name: process.argv[2] || rel.name, body, make_latest: 'true', prerelease: false,
  });
  if (upd.status >= 300) { console.error('更新失败', upd.text.slice(0, 300)); process.exit(1); }
  rel = upd.json;
} else {
  const created = await api('POST', `/repos/${REPO}/releases`, {
    tag_name: TAG, target_commitish: 'main',
    name: process.argv[2] || `家庭备忘录 ${TAG}`,
    body, draft: false, prerelease: false, make_latest: 'true',
  });
  if (created.status >= 300) { console.error('创建失败', created.text.slice(0, 500)); process.exit(1); }
  rel = created.json;
  console.log(`已创建 Release ${TAG} (id=${rel.id})`);
}

// 2) 上传资产（已存在则跳过）
const list = await api('GET', `/repos/${REPO}/releases/${rel.id}/assets`);
const have = new Set((list.json || []).map(a => a.name));
for (const a of assets) {
  const name = path.basename(a.file);
  if (have.has(name)) { console.log(`== ${name} 已存在，跳过`); continue; }
  console.log(`== 上传 ${name} (${size(a.file)})`);
  let ok = false;
  for (let i = 1; i <= 4 && !ok; i++) {
    const r = await api('POST', `/repos/${REPO}/releases/${rel.id}/assets?name=${encodeURIComponent(name)}`,
      fs.readFileSync(a.file), true);
    ok = r.status < 300;
    if (!ok) { console.log(`  重试 ${i}: ${r.text.slice(0, 120)}`); await new Promise(r2 => setTimeout(r2, 3000)); }
  }
  if (!ok) { console.error(`  !! ${name} 上传失败`); process.exit(1); }
}

// 3) 校验下载
console.log('== 校验下载 ==');
for (const a of assets) {
  const name = path.basename(a.file);
  const res = await fetch(`https://github.com/${REPO}/releases/download/${TAG}/${name}`, { redirect: 'follow' });
  console.log(`  ${name} -> HTTP ${res.status}`);
}

const latest = await api('GET', `/repos/${REPO}/releases/latest`);
console.log(`== Latest: ${latest.json?.tag_name} ${latest.json?.name}`);
