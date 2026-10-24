#!/usr/bin/env bash
# 发布 v1.1.3：创建/复用 Release、标记 Latest、上传资产、写校验值
set -euo pipefail
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
REPO="anlan-home/memosync"; TAG="v1.1.3"

cd "$(dirname "$0")/.."
APK_SHA=$(certutil -hashfile dist/FamilyMemo-v1.1.3-release.apk SHA256 | sed -n '2p' | tr -d ' \r')
X86_SHA=$(certutil -hashfile dist/memosync_1.1.3_x86_64.fpk SHA256 | sed -n '2p' | tr -d ' \r')
ARM_SHA=$(certutil -hashfile dist/memosync_1.1.3_arm64.fpk SHA256 | sed -n '2p' | tr -d ' \r')
APK_SIZE=$(du -h dist/FamilyMemo-v1.1.3-release.apk | cut -f1)
X86_SIZE=$(du -h dist/memosync_1.1.3_x86_64.fpk | cut -f1)
ARM_SIZE=$(du -h dist/memosync_1.1.3_arm64.fpk | cut -f1)

cat > /tmp/rel113.json <<EOF
{
  "tag_name": "$TAG",
  "target_commitish": "main",
  "name": "家庭备忘录 v1.1.3（双地址 + 真机 UI 打磨）",
  "make_latest": "true",
  "body": "## 这一版解决「在家能用、出门连不上」\n\n- **双地址自动切换**：同时填内网地址与外网/Tailscale 地址，App 自动探测可用线路；在家走内网、出门自动切外网，某条线路失败会立即换另一条重试\n- **设置页可管理地址**：编辑主/备地址、查看「当前生效」、一键「测试连接」\n- **深链接一键配置**：\`familymemo://setup?primary=http://NAS:5231&alt=http://WAN:5231\`（可直接做成二维码给家人扫）\n- **新建时选空间**：新建笔记/清单/资产时选择家庭共享、工作组或私人空间；卡片上标注所属空间\n- 空草稿退出自动进回收站，不再留“(空)”卡片\n\n## 真机 UI 走查（小米 2412 / Android 14）修了 5 处\n- Material3 容器色缺失，导航栏/弹窗/选中态是紫色 → 补全暖米黄色板\n- 编辑器底部工具栏压在手势条上 → 增加系统栏避让\n- 服务端时间略超前几秒时显示“即将” → 60 秒内按“刚刚”显示\n- 编辑器工具栏缺少“标签”按钮 → 补齐为 5 个\n- 卡片看不出所属空间 → 增加空间标签\n\n## 真机验证\n- 主地址设为不可达、备用设为可用 → 同步自动切到备用并成功，「当前生效」显示备用地址 ✅\n- 深链接写入地址并立即同步 ✅\n- 登录/五页签/新建/空间选择/资产页/设置 逐屏截图核对 ✅\n- 服务端 26 个测试、客户端 38 个单测全绿 ✅\n\n---\n\n## 下载\n\n| 文件 | 大小 | 用途 |\n|---|---|---|\n| \`FamilyMemo-v1.1.3-release.apk\` | $APK_SIZE | Android 手机安装（正式签名） |\n| \`memosync_1.1.3_x86_64.fpk\` | $X86_SIZE | 飞牛 fnOS 本地安装（x86） |\n| \`memosync_1.1.3_arm64.fpk\` | $ARM_SIZE | fnOS ARM 设备 |\n\n### 推荐配置（双地址）\n- 主地址：\`http://<NAS内网IP>:5231\`\n- 备用地址：Tailscale 地址 \`http://100.x.x.x:5231\` 或公网 DDNS 地址\n- 手机装 APK → 登录页分别填写两个地址 → 之后无论在家还是外面都能自动选通\n\n### 校验值（SHA256）\n\`\`\`\n$APK_SHA  FamilyMemo-v1.1.3-release.apk\n$X86_SHA  memosync_1.1.3_x86_64.fpk\n$ARM_SHA  memosync_1.1.3_arm64.fpk\n\`\`\`\n\n> 飞牛上改端口请在「应用设置」里改（保存后重启应用）；旧端口会自动跳转到新端口。",
  "draft": false,
  "prerelease": false
}
EOF

REL=$(curl -s --max-time 20 -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/tags/$TAG")
if echo "$REL" | grep -q '"id"'; then
  REL_ID=$(echo "$REL" | grep -m1 '"id"' | tr -dc '0-9')
  echo "Release 已存在，更新说明 (id=$REL_ID)"
  curl -s --max-time 30 -X PATCH -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/rel113.json "https://api.github.com/repos/$REPO/releases/$REL_ID" >/dev/null
else
  REL=$(curl -s --max-time 30 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/rel113.json "https://api.github.com/repos/$REPO/releases")
  REL_ID=$(echo "$REL" | grep -m1 '"id"' | tr -dc '0-9')
  echo "已创建 Release id=$REL_ID"
fi

upload() {
  local file="$1" name="$2" ctype="$3"
  if curl -s --max-time 20 -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/$REPO/releases/$REL_ID/assets" | grep -q "\"name\": \"$name\""; then
    echo "== $name 已存在，跳过"; return 0
  fi
  echo "== 上传 $name"
  for i in 1 2 3 4 5; do
    R=$(curl -s --max-time 600 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: $ctype" \
      --data-binary @"$file" "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$name")
    if echo "$R" | grep -q '"browser_download_url"'; then echo "  完成"; return 0; fi
    echo "  重试 $i"; sleep 4
  done
  echo "  !! 失败"; return 1
}

upload dist/FamilyMemo-v1.1.3-release.apk FamilyMemo-v1.1.3-release.apk application/vnd.android.package-archive
upload dist/memosync_1.1.3_x86_64.fpk memosync_1.1.3_x86_64.fpk application/octet-stream
upload dist/memosync_1.1.3_arm64.fpk memosync_1.1.3_arm64.fpk application/octet-stream
rm -f /tmp/rel113.json

echo "== 资产校验 =="
for f in FamilyMemo-v1.1.3-release.apk memosync_1.1.3_x86_64.fpk memosync_1.1.3_arm64.fpk; do
  code=$(curl -s -o /dev/null -m 60 -L -w "%{http_code}" "https://github.com/$REPO/releases/download/$TAG/$f")
  echo "  $f -> HTTP $code"
done
curl -s --max-time 20 -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/latest" | grep -E '"tag_name"|"name"' | head -2
