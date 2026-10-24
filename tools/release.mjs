// 可复用的 Release 发布脚本（走 curl，自动使用系统代理）
// 用法: node tools/release.mjs ["发布说明标题"]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync, execSync } from 'node:child_process';
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
const TITLE = process.argv[2] || `家庭备忘录 ${TAG}`;

const TOKEN = execSync('git credential fill', {
  input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8',
}).split('\n').filter(l => l.startsWith('password='))[0].slice('password='.length).trim();
if (!TOKEN) { console.error('无法获取 GitHub 凭据'); process.exit(1); }

/** 用 curl 调 GitHub API（curl 会使用系统代理，Node fetch 不会） */
function curl(method, url, bodyPath, contentType) {
  const args = ['-sS', '--max-time', '300', '-X', method, '-w', '\n%{http_code}',
    '-H', `Authorization: token ${TOKEN}`, '-H', 'Accept: application/vnd.github+json'];
  if (contentType) args.push('-H', `Content-Type: ${contentType}`);
  if (bodyPath) args.push('--data-binary', `@${bodyPath}`);
  args.push(url);
  let out = '';
  try {
    out = execFileSync('curl', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    out = (e.stdout || '') + '\n000';
  }
  const idx = out.lastIndexOf('\n');
  const status = parseInt(out.slice(idx + 1).trim(), 10) || 0;
  const text = out.slice(0, idx);
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status, json, text };
}

const assets = [
  { file: `dist/FamilyMemo-${TAG}-release.apk`, type: 'application/vnd.android.package-archive', desc: 'Android 手机安装' },
  { file: `dist/memosync_${VERSION}_x86_64.fpk`, type: 'application/octet-stream', desc: '飞牛 fnOS x86 本地安装' },
  { file: `dist/memosync_${VERSION}_arm64.fpk`, type: 'application/octet-stream', desc: '飞牛 fnOS ARM 本地安装' },
].filter(a => fs.existsSync(a.file));

if (!assets.length) { console.error('dist 下找不到本版本产物，请先构建'); process.exit(1); }

const sha = f => execSync(`certutil -hashfile "${f}" SHA256`).toString().split('\n')[1].trim();
const size = f => (fs.statSync(f).size / 1048576).toFixed(1) + ' MB';

const body = [
  fs.readFileSync('RELEASE_NOTES.md', 'utf8'),
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
const existing = curl('GET', `https://api.github.com/repos/${REPO}/releases/tags/${TAG}`);
let rel = null;
if (existing.status === 200) {
  rel = existing.json;
  console.log(`Release ${TAG} 已存在，更新说明…`);
  fs.writeFileSync('/tmp/rel-update.json', JSON.stringify({
    name: TITLE, body, make_latest: 'true', prerelease: false,
  }));
  const upd = curl('PATCH', `https://api.github.com/repos/${REPO}/releases/${rel.id}`, '/tmp/rel-update.json', 'application/json');
  if (upd.status >= 300) { console.error('更新失败', upd.text.slice(0, 300)); process.exit(1); }
  rel = upd.json;
} else {
  fs.writeFileSync('/tmp/rel-create.json', JSON.stringify({
    tag_name: TAG, target_commitish: 'main', name: TITLE,
    body, draft: false, prerelease: false, make_latest: 'true',
  }));
  const created = curl('POST', `https://api.github.com/repos/${REPO}/releases`, '/tmp/rel-create.json', 'application/json');
  if (created.status >= 300) { console.error('创建失败', created.text.slice(0, 500)); process.exit(1); }
  rel = created.json;
  console.log(`已创建 Release ${TAG} (id=${rel.id})`);
}

// 2) 上传资产（已存在则跳过）
const list = curl('GET', `https://api.github.com/repos/${REPO}/releases/${rel.id}/assets`);
const have = new Set((list.json || []).map(a => a.name));
for (const a of assets) {
  const name = path.basename(a.file);
  if (have.has(name)) { console.log(`== ${name} 已存在，跳过`); continue; }
  console.log(`== 上传 ${name} (${size(a.file)})`);
  let ok = false;
  for (let i = 1; i <= 4 && !ok; i++) {
    const r = curl('POST',
      `https://uploads.github.com/repos/${REPO}/releases/${rel.id}/assets?name=${encodeURIComponent(name)}`,
      a.file, a.type);
    ok = r.status < 300;
    if (!ok) {
      console.log(`  重试 ${i}: HTTP ${r.status} ${r.text.slice(0, 100)}`);
      execSync('sleep 3');
    }
  }
  if (!ok) { console.error(`  !! ${name} 上传失败`); process.exit(1); }
}

// 3) 校验下载
console.log('== 校验下载 ==');
for (const a of assets) {
  const name = path.basename(a.file);
  const code = execSync(
    `curl -s -o NUL -L --max-time 120 -w "%{http_code}" "https://github.com/${REPO}/releases/download/${TAG}/${name}"`,
    { encoding: 'utf8' },
  ).trim();
  console.log(`  ${name} -> HTTP ${code}`);
}

const latest = curl('GET', `https://api.github.com/repos/${REPO}/releases/latest`);
console.log(`== Latest: ${latest.json?.tag_name} ${latest.json?.name}`);
