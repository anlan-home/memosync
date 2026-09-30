#!/bin/bash
# 创建 GitHub Release v1.1.0 并上传资产
set -e
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
if [ -z "$TOKEN" ]; then echo "NO_TOKEN"; exit 1; fi

cat > /tmp/release-body.json <<'EOF'
{
  "tag_name": "v1.1.0",
  "target_commitish": "main",
  "name": "家庭备忘录 v1.1.0",
  "body": "## 本版亮点\n\n- **多条提醒**：一条备忘录可设多个提醒时间（如 18 号 9 点、19 号 8 点、20 号 10 点），App 精确闹钟各自推送\n- **飞书 / 钉钉群机器人**：到点提醒同步推送到群聊（管理页 → 提醒推送，支持钉钉加签）\n- **桌面小组件**：清单小组件可直接在桌面勾选条目，笔记小组件常驻速览\n- **工作组空间**：建组、按用户名拉人/移人，备忘录可在家庭/工作组/个人空间间自由转换，组内内容仅组员可见\n- **资产自定义字段**：网址、会员等级等键值对，卡片上一键复制\n- **清单条目级合并**：家人同时编辑同一清单自动并集，不再产生冲突副本\n- **改端口自愈**：管理页直接改服务端口，旧端口自动跳转\n\n## 下载\n\n| 文件 | 用途 |\n|---|---|\n| `FamilyMemo-v1.1.0-release.apk` | Android 手机安装（x86_64/ARM 手机通用） |\n| `memosync_1.1.0_x86_64.fpk` | 飞牛 fnOS 应用中心本地安装（x86 NAS） |\n| `memosync_1.1.0_arm64.fpk` | fnOS ARM 设备 |\n\n**首次使用**：NAS 装 FPK → 管理页创建账号/生成配对码 → 手机装 APK 登录。详见仓库 README。\n\n**升级**：直接覆盖安装，数据保留（签名与 v1.1.0 起保持一致）。",
  "draft": false,
  "prerelease": false
}
EOF
echo "== 查找/创建 Release =="
REL=$(curl -s --max-time 20 -H "Authorization: token $TOKEN" \
  https://api.github.com/repos/anlan-home/memosync/releases/tags/v1.1.0)
if echo "$REL" | grep -q '"id"'; then
  echo "已存在，直接复用"
else
  REL=$(curl -s --max-time 20 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/release-body.json \
    https://api.github.com/repos/anlan-home/memosync/releases)
fi
REL=$(curl -s --max-time 20 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
  --data-binary @/tmp/release-body.json \
  https://api.github.com/repos/anlan-home/memosync/releases)
echo "$REL" | grep -E '"id"|"html_url"|"message"' | head -3
REL_ID=$(echo "$REL" | grep -m1 '"id"' | tr -dc '0-9')
echo "RELEASE_ID=$REL_ID"

upload() {
  local file="$1" name="$2" ctype="$3"
  echo "== 上传 $name =="
  for i in 1 2 3; do
    R=$(curl -s --max-time 300 -X POST \
      -H "Authorization: token $TOKEN" -H "Content-Type: $ctype" \
      --data-binary @"$file" \
      "https://uploads.github.com/repos/anlan-home/memosync/releases/$REL_ID/assets?name=$name")
    if echo "$R" | grep -q '"browser_download_url"'; then
      echo "$R" | grep '"browser_download_url"' | head -1
      return 0
    fi
    echo "  重试 $i: $(echo "$R" | grep -m1 '"message"')"
    sleep 3
  done
  return 1
}

upload "dist/FamilyMemo-v1.1.0-release.apk" "FamilyMemo-v1.1.0-release.apk" "application/vnd.android.package-archive"
upload "dist/memosync_1.1.0_x86_64.fpk" "memosync_1.1.0_x86_64.fpk" "application/octet-stream"
upload "dist/memosync_1.1.0_arm64.fpk" "memosync_1.1.0_arm64.fpk" "application/octet-stream"
rm /tmp/release-body.json
echo "完成"
