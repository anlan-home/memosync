// 生成应用图标 PNG（便签风格：琥珀圆角底 + 白色页面 + 内容条），支持任意尺寸。
// 输出:
//   fpk/assets/icon.png                (256, 通用)
//   fpk/native/ICON.PNG                (256, fnOS 根图标)
//   fpk/native/ICON_256.PNG            (256)
//   fpk/native/app/ui/images/icon_256.png
//   fpk/native/app/ui/images/icon_64.png
// 用法: node tools/gen-icon.mjs
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

function crc32(buf) {
  let c, table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  c = -1;
  for (const b of buf) c = table[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}
function encodePNG(size, px) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8bit RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    px.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// 以 256 为基准坐标系绘制，factor 缩放到目标尺寸
function drawIcon(size) {
  const f = size / 256;
  const px = Buffer.alloc(size * size * 4, 0);
  const X = v => Math.round(v * f);
  const set = (x, y, r, g, b, a = 255) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 4;
    px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
  };
  const fillRoundRect = (x0, y0, x1, y1, rad, c) => {
    for (let y = X(y0); y <= X(y1); y++) for (let x = X(x0); x <= X(x1); x++) {
      const dx = x < X(x0 + rad) ? X(x0 + rad) - x : x > X(x1 - rad) ? x - X(x1 - rad) : 0;
      const dy = y < X(y0 + rad) ? X(y0 + rad) - y : y > X(y1 - rad) ? y - X(y1 - rad) : 0;
      if (dx * dx + dy * dy <= X(rad) * X(rad)) set(x, y, ...c);
    }
  };
  // 背景琥珀圆角方块（轻微纵向渐变）
  for (let y = X(16); y <= X(240); y++) {
    const t = (y - X(16)) / X(224);
    const r = Math.round(0xF5 + (0xE8 - 0xF5) * t);
    const g = Math.round(0x9E + (0x8A - 0x9E) * t);
    const b = Math.round(0x0B + (0x09 - 0x0B) * t);
    for (let x = X(16); x <= X(240); x++) {
      const dx = x < X(64) ? X(64) - x : x > X(192) ? x - X(192) : 0;
      const dy = y < X(64) ? X(64) - y : y > X(192) ? y - X(192) : 0;
      if (dx * dx + dy * dy <= X(48) * X(48)) set(x, y, r, g, b);
    }
  }
  // 白色页面
  fillRoundRect(72, 48, 184, 208, 14, [255, 253, 248]);
  // 标题条 + 两条内容条
  fillRoundRect(90, 78, 166, 90, 6, [180, 83, 9]);
  fillRoundRect(90, 112, 166, 122, 5, [245, 158, 11]);
  fillRoundRect(90, 140, 146, 150, 5, [245, 158, 11]);
  // 右下角折页
  const k0 = X(18);
  for (let k = 0; k < k0; k++) for (let j = 0; j <= k; j++) {
    set(X(184) - k, X(208) - k0 + j, 252, 211, 77);
  }
  return encodePNG(size, px);
}

const root = path.resolve(import.meta.dirname, '..');
const outputs = [
  ['fpk/assets/icon.png', 256],
  ['fpk/native/ICON.PNG', 256],
  ['fpk/native/ICON_256.PNG', 256],
  ['fpk/native/app/ui/images/icon_256.png', 256],
  ['fpk/native/app/ui/images/icon_64.png', 64],
];
for (const [rel, size] of outputs) {
  const p = path.join(root, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, drawIcon(size));
  console.log(rel, size + 'px');
}
