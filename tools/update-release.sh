#!/usr/bin/env bash
# 更新 v1.1.2 Release：标记 Latest + 补充校验值与安装指引
set -euo pipefail
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
REPO="anlan-home/memosync"
TAG="v1.1.2"

REL_ID=$(curl -s --max-time 20 -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/releases/tags/$TAG" | grep -m1 '"id"' | tr -dc '0-9')
[ -z "$REL_ID" ] && { echo "找不到 release"; exit 1; }
echo "RELEASE_ID=$REL_ID"

APK_SHA=$(grep 'release.apk' /tmp/checksums.txt | cut -d'|' -f3)
X86_SHA=$(grep 'x86_64.fpk' /tmp/checksums.txt | cut -d'|' -f3)
ARM_SHA=$(grep 'arm64.fpk' /tmp/checksums.txt | cut -d'|' -f3)

cat > /tmp/rel-upd.json <<EOF
{
  "name": "家庭备忘录 v1.1.2（真机验证版）",
  "make_latest": "true",
  "body": "## 本版重点：在真实飞牛 NAS 上完成端到端验证，并修复验证中发现的缺陷\n\n### 真机验证结果（app install-fpk 实装）\n- 安装任务成功、应用 running、管理页 200、未认证接口 401\n- 完整 E2E **60/60 连续两次全绿**（清单合并、多提醒、工作组、空间转换、附件、配对码、改密踢设备）\n- 飞书 / 钉钉（含加签）webhook 真实回调 **9/9**\n- 应用设置改端口 → 新端口可用、旧端口自动 307 跳转\n- 卸载重装后账号与数据完整保留\n\n### 重要修复\n- **备份恢复后同步失效**：恢复导致序号回退，客户端永久收不到更新 → 服务端下发 \`reset_required\`，客户端保留未推送编辑并全量重建\n- **空间转换未生效**：服务端 UPDATE 漏了 \`space_id\`；且移出可见空间后仍能读到内容（隐私泄漏）→ 修正并下发墓碑\n- **删除组长导致工作组不可管理** → 自动转让组长；删除用户清理成员关系\n- **Webhook 失败后提醒丢失** → 失败重试（上限 3 次）\n- **fnOS 内改端口无效** → 明确拒绝并指引走平台设置（平台只路由已声明端口）\n- 端口改到已占用端口导致服务起不来 → 变更前探测\n- 用户名可创建重名账号 → 去空格 + 字符集校验\n- 二进制版本号写死 → 构建时注入\n\n### 新增\n- **安装向导可设置管理员账号密码**（不必再翻日志找初始密码）\n- \`GET /api/v1/admin/diagnostics\` 运行诊断（版本/端口/配置可写性/平台模式）\n- E2E 脚本可重复运行；webhook 测试支持局域网回调\n\n### 客户端修复\n- 小组件勾选索引错位（列表重排后勾错条目）\n- 未来提醒时间显示“刚刚”\n- 「我的」提醒列表不随时间刷新\n- 退出编辑器时保存可能被取消（快速返回丢最后输入）\n- SSE 401 后无限重连；被移出工作组后本地空间残留；小组件主线程 IO\n\n---\n\n## 下载\n\n| 文件 | 大小 | 用途 |\n|---|---|---|\n| \`FamilyMemo-v1.1.2-release.apk\` | 12.1 MB | Android 手机安装（正式签名） |\n| \`memosync_1.1.2_x86_64.fpk\` | 4.6 MB | 飞牛 fnOS 本地安装（x86） |\n| \`memosync_1.1.2_arm64.fpk\` | 4.3 MB | fnOS ARM 设备 |\n\n### 安装/升级步骤\n\n**NAS 端（飞牛）**：应用中心 → 本地安装 → 选择对应架构的 fpk。安装向导里可设置**服务端口**与**管理员账号密码**。\n实测：卸载后重装，账号与备忘录数据完整保留。\n\n**手机端**：安装 APK → 登录页填 \`http://NAS-IP:端口\` → 用管理员账号登录，或在 NAS 管理页「接入配对」生成配对码给家人免密接入。\n\n### 校验值（SHA256）\n\n\`\`\`\n$APK_SHA  FamilyMemo-v1.1.2-release.apk\n$X86_SHA  memosync_1.1.2_x86_64.fpk\n$ARM_SHA  memosync_1.1.2_arm64.fpk\n\`\`\`\n\n> **注意**：飞牛上修改端口请在「应用设置」里改（保存后重启应用）；管理页内的改端口按钮在应用包内会被拒绝，这是平台限制。旧端口会自动跳转到新端口。",
  "draft": false,
  "prerelease": false
}
EOF

echo "== 更新 Release =="
curl -s --max-time 30 -X PATCH -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
  --data-binary @/tmp/rel-upd.json "https://api.github.com/repos/$REPO/releases/$REL_ID" \
  | grep -E '"tag_name"|"name"|"html_url"' | head -3

echo "== 验证资产可下载 =="
for f in FamilyMemo-v1.1.2-release.apk memosync_1.1.2_x86_64.fpk memosync_1.1.2_arm64.fpk; do
  code=$(curl -s -o /dev/null -m 60 -L -w "%{http_code}" "https://github.com/$REPO/releases/download/$TAG/$f")
  echo "  $f -> HTTP $code"
done
rm -f /tmp/rel-upd.json
