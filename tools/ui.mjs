// 真机 UI 检查工具：解析 uiautomator XML，按文字/描述找元素并给出坐标
// 用法:
//   node tools/ui.mjs dump              列出当前界面元素（文本 / 描述 / 坐标）
//   node tools/ui.mjs find <文字>       只找匹配元素
import fs from 'node:fs';
import { execSync } from 'node:child_process';

const ADB = process.env.ADB || 'D:/androidsdk/platform-tools/adb.exe';
const mode = process.argv[2] || 'dump';

function sh(cmd) {
  return execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function dumpXml() {
  try { sh(`"${ADB}" shell uiautomator dump /sdcard/ui.xml`); } catch {}
  const xml = sh(`"${ADB}" shell cat /sdcard/ui.xml`);
  return xml;
}

function parse(xml) {
  const nodes = [];
  const re = /<node\b([^>]*)\/?>/g;
  let m;
  while ((m = re.exec(xml))) {
    const attrs = {};
    const ar = /([\w-]+)="([^"]*)"/g;
    let a;
    while ((a = ar.exec(m[1]))) attrs[a[1]] = a[2];
    const b = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(attrs.bounds || '');
    if (!b) continue;
    nodes.push({
      text: attrs.text || '',
      desc: attrs['content-desc'] || '',
      cls: (attrs.class || '').split('.').pop(),
      clickable: attrs.clickable === 'true',
      checked: attrs.checked === 'true',
      focused: attrs.focused === 'true',
      x: Math.round((+b[1] + +b[3]) / 2),
      y: Math.round((+b[2] + +b[4]) / 2),
      w: +b[3] - +b[1],
      h: +b[4] - +b[2],
    });
  }
  return nodes;
}

const xml = dumpXml();
const nodes = parse(xml);
const label = n => n.text || n.desc || '';

if (mode === 'dump') {
  const useful = nodes.filter(n => label(n) && (n.w > 0 && n.h > 0));
  console.log(`界面元素 ${useful.length} 个:`);
  useful.forEach(n => {
    console.log(`  ${(n.cls || '').padEnd(14)} ${n.clickable ? '[可点]' : '      '} (${n.x},${n.y}) ${label(n)}`);
  });
} else if (mode === 'find') {
  const q = process.argv[3] || '';
  const hits = nodes.filter(n => label(n).includes(q));
  if (!hits.length) { console.log(`未找到含 "${q}" 的元素`); process.exit(1); }
  hits.forEach(n => console.log(`${n.clickable ? 'CLICK' : 'TEXT '} (${n.x},${n.y}) ${label(n)}`));
}
