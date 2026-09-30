#!/bin/bash
# 上传资产到已存在的 Release（REL_ID=399632564）
set -e
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
REL_ID="399632564"

upload() {
  local file="$1" name="$2" ctype="$3"
  # 已上传过则跳过
  if curl -s --max-time 20 -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/anlan-home/memosync/releases/$REL_ID/assets" | grep -q "\"name\": \"$name\""; then
    echo "== $name 已存在，跳过"
    return 0
  fi
  echo "== 上传 $name ($(du -h "$file" | cut -f1)) =="
  for i in 1 2 3 4 5; do
    R=$(curl -s --max-time 600 -X POST \
      -H "Authorization: token $TOKEN" -H "Content-Type: $ctype" \
      --data-binary @"$file" \
      "https://uploads.github.com/repos/anlan-home/memosync/releases/$REL_ID/assets?name=$name")
    if echo "$R" | grep -q '"browser_download_url"'; then
      echo "  完成: $(echo "$R" | grep -m1 '"browser_download_url"' | sed 's/.*: "//;s/",//')"
      return 0
    fi
    echo "  重试 $i: $(echo "$R" | grep -m1 '"message"' || echo '网络无响应')"
    sleep 4
  done
  echo "  !! $name 上传失败"
  return 1
}

cd "$(dirname "$0")"
upload "dist/FamilyMemo-v1.1.0-release.apk" "FamilyMemo-v1.1.0-release.apk" "application/vnd.android.package-archive"
upload "dist/memosync_1.1.0_x86_64.fpk" "memosync_1.1.0_x86_64.fpk" "application/octet-stream"
upload "dist/memosync_1.1.0_arm64.fpk" "memosync_1.1.0_arm64.fpk" "application/octet-stream"
echo "== 最终资产清单 =="
curl -s --max-time 20 -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/anlan-home/memosync/releases/$REL_ID/assets" | grep '"browser_download_url"'
