#!/bin/bash
# 创建 v1.1.2 Release 并上传资产（幂等）
set -e
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
REPO="anlan-home/memosync"
TAG="v1.1.2"

cat > /tmp/rel112.json <<'EOF'
{
  "tag_name": "v1.1.2",
  "target_commitish": "main",
  "name": "家庭备忘录 v1.1.2（真机验证版）",
  "body": "## 本版重点：在真实飞牛 NAS 上完成端到端验证，并修复验证中发现的缺陷\n\n### 真机验证结果（app install-fpk 实装）\n- 安装任务成功、应用 running、管理页 200、未认证接口 401\n- 完整 E2E **60/60 连续两次全绿**（清单合并、多提醒、工作组、空间转换、附件、配对码、改密踢设备）\n- 飞书 / 钉钉（含加签）webhook 真实回调 **9/9**\n- 应用设置改端口 → 新端口可用、旧端口自动 307 跳转\n- 卸载重装后账号与数据完整保留\n\n### 重要修复\n- **备份恢复后同步失效**：恢复导致序号回退，客户端永久收不到更新 → 服务端下发 `reset_required`，客户端保留未推送编辑并全量重建\n- **空间转换未生效**：服务端 UPDATE 漏了 `space_id`；且移出可见空间后仍能读到内容（隐私泄漏）→ 修正并下发墓碑\n- **删除组长导致工作组不可管理** → 自动转让组长；删除用户清理成员关系\n- **Webhook 失败后提醒丢失** → 失败重试（上限 3 次）\n- **fnOS 内改端口无效** → 改为明确拒绝并指引走平台设置（平台只路由已声明端口）\n- 端口改到已占用端口导致服务起不来 → 变更前探测\n- 用户名可创建重名账号 → 去空格 + 字符集校验\n- 二进制版本号写死 → 构建时注入\n\n### 新增\n- **安装向导可设置管理员账号密码**（不必再翻日志找初始密码）\n- `GET /api/v1/admin/diagnostics` 运行诊断（版本/端口/配置可写性/平台模式）\n- E2E 脚本可重复运行；webhook 测试支持局域网回调\n\n### 客户端修复\n- 小组件勾选索引错位（列表重排后勾错条目）\n- 未来提醒时间显示“刚刚”\n- 「我的」提醒列表不随时间刷新\n- 退出编辑器时保存可能被取消（快速返回丢最后输入）\n- SSE 401 后无限重连；被移出工作组后本地空间残留；小组件主线程 IO\n\n## 下载\n\n| 文件 | 用途 |\n|---|---|\n| `FamilyMemo-v1.1.2-release.apk` | Android 手机安装 |\n| `memosync_1.1.2_x86_64.fpk` | 飞牛 fnOS 本地安装（x86） |\n| `memosync_1.1.2_arm64.fpk` | fnOS ARM 设备 |\n\n**升级**：fnOS 应用中心重新本地安装即可（实测卸载重装数据与账号完整保留）。",
  "draft": false,
  "prerelease": false
}
EOF

REL=$(curl -s --max-time 20 -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/tags/$TAG")
if echo "$REL" | grep -q '"id"'; then
  echo "Release 已存在，复用"
else
  REL=$(curl -s --max-time 20 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    --data-binary @/tmp/rel112.json "https://api.github.com/repos/$REPO/releases")
fi
REL_ID=$(echo "$REL" | grep -m1 '"id"' | tr -dc '0-9')
echo "RELEASE_ID=$REL_ID"

upload() {
  local file="$1" name="$2" ctype="$3"
  if curl -s --max-time 20 -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/$REPO/releases/$REL_ID/assets" | grep -q "\"name\": \"$name\""; then
    echo "== $name 已存在，跳过"; return 0
  fi
  echo "== 上传 $name ($(du -h "$file" | cut -f1))"
  for i in 1 2 3 4 5; do
    R=$(curl -s --max-time 600 -X POST -H "Authorization: token $TOKEN" -H "Content-Type: $ctype" \
      --data-binary @"$file" "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$name")
    if echo "$R" | grep -q '"browser_download_url"'; then
      echo "  完成"; return 0
    fi
    echo "  重试 $i"; sleep 4
  done
  echo "  !! 失败"; return 1
}

upload "dist/FamilyMemo-v1.1.2-release.apk" "FamilyMemo-v1.1.2-release.apk" "application/vnd.android.package-archive"
upload "dist/memosync_1.1.2_x86_64.fpk" "memosync_1.1.2_x86_64.fpk" "application/octet-stream"
upload "dist/memosync_1.1.2_arm64.fpk" "memosync_1.1.2_arm64.fpk" "application/octet-stream"
rm -f /tmp/rel112.json
echo "== 资产清单 =="
curl -s --max-time 20 -H "Authorization: token $TOKEN" "https://api.github.com/repos/$REPO/releases/$REL_ID/assets" | grep '"browser_download_url"'
