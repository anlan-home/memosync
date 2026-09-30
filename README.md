# 家庭备忘录（MemoSync）

自托管的家庭备忘录系统：**Android 客户端（离线优先）+ NAS 服务端（FPK/Docker 部署）**，数据 100% 存在自己家里。

- 服务端：Go 单二进制 + SQLite，REST API，多成员账号、家庭共享空间、附件、提醒、每日备份/恢复、内嵌 Web 管理页
- 客户端：Kotlin + Jetpack Compose，Room 本地库（离线可用），**SSE 实时推送触发 + 游标增量同步**（changes 游标 + 版本号乐观锁），冲突自动保全副本，本地精确闹钟提醒
- **数字资产**：家庭账号/密码统一管理（视频会员、宽带、云盘…），到期倒计时 + 到期前 N 天自动推送提醒，账号密码一键复制给家人，支持个人空间（私有密码）与家庭空间（全家可见）
- **标签系统**：备忘录打标签（编辑器内添加/移除，首页 #标签 筛选），标签随同步分发给全家
- **多条提醒**：一条备忘录可设多个提醒时间（18号9点/19号8点/20号10点…各自推送）；App 本地精确闹钟 + 飞书/钉钉群机器人 Webhook 双通道（管理页配置）
- **桌面小组件**：清单小组件（桌面直接勾选条目）与笔记小组件，长按备忘录「设为小组件内容」指定内容
- **工作组空间**：任何成员可建工作组、按用户名拉人/移人，备忘录可在家庭/工作组/个人空间间自由转换，组内内容仅组员可见
- **资产自定义字段**：键值对自定义（网址、会员等级…），卡片上值可一键复制
- **清单协作合并**：家人同时编辑同一清单时服务端自动做条目级合并（并集、勾选取"或"），不再产生冲突副本
- 详细设计：[docs/方案设计.md](docs/方案设计.md) · UI 原型：[ui/prototype.html](ui/prototype.html)

```
server/     Go 服务端（含 18 个集成测试 + 48 项 E2E 脚本）
android/    Android 客户端（含同步引擎 JVM 单元测试）
fpk/        fnOS FPK 打包（官方 fnpack + 生命周期脚本 + 构建脚本）
deploy/     Dockerfile + docker-compose.yml
dist/       构建产物（构建后生成）
```

---

## 一、服务端部署（三选一）

### 方式 A：直接运行二进制（最快验证）

```bash
# NAS（Linux amd64）上：
scp dist/memosync_linux_amd64 nas:/usr/local/bin/memosync
ssh nas memosync              # 默认监听 :5231，数据目录 ./data
```

- 首次启动自动创建管理员，**初始密码打印在日志里**，同时写入 `<数据目录>/initial_admin_password.txt`
- 浏览器打开 `http://<NAS-IP>:5231` 进入管理页：创建家人账号、生成配对码、备份、导出

### 方式 B：fnOS 安装 FPK 包（官方 fnpack 打包）

```bash
bash fpk/build-fpk.sh         # 产出 dist/memosync_1.0.0_x86_64.fpk / _arm64.fpk
```

使用**飞牛官方 fnpack 1.2.1** 工具打包（随仓库附在 fpk/ 下），包结构完全遵循 `fnpack create` 官方模板：
manifest（appname/desc/service_port）+ 9 个生命周期脚本（install/upgrade/uninstall/config 各自的 init 与 callback）+ config/privilege + 桌面入口（app/ui/config）+ 全套图标。

安装：fnOS 应用中心 →「本地安装」→ 上传对应架构的 fpk。安装后：

- 桌面出现「家庭备忘录」图标，点击直达管理页（http://NAS-IP:5231）
- 数据存于应用 var 目录（TRIM_PKGVAR），**升级保留**；进程由应用中心托管（启停/开机自启/状态检测）
- 首次打开管理页创建管理员，初始密码在应用日志 info.log 中
- **端口可配置**：安装向导可自定义服务端口（默认 5231）；装完后在 fnOS 应用的「设置」页改端口，保存并在应用中心重启应用即可生效（桌面图标与手机客户端同步用新端口）

> 说明：包结构与打包工具与已验证可本地安装的社区应用一致，且通过 fnpack 官方校验；但本仓库作者没有 fnOS 真机，**最终安装以真机为准**——如有问题优先核对 fnOS 版本对 fpk 的要求（os_min_version=0.9.0）。ARM 包的 arch 字段名（arm64）未经真机验证。

### 方式 C：Docker Compose（推荐，全自动）

```bash
cd deploy && docker compose up -d
docker logs memosync          # 看初始管理员密码
```

数据落在 `deploy/data/`，备份、导出都从管理页操作。

## 二、家人手机接入（两种方式）

1. **配对码（推荐给长辈）**：管理页 →「接入配对」→ 选成员生成 8 位配对码 → 手机登录页选「配对码接入」→ 填 NAS 地址 + 输配对码 → 完事，零密码输入。
2. **账号密码**：管理页创建成员 → 手机填 NAS 地址 + 账号密码登录。

## 三、外网访问（不在家也能同步）

推荐顺序：
1. **Tailscale/WireGuard**：NAS 与手机各装一个，手机端 NAS 地址填 Tailscale IP（免公网、免暴露端口、端到端加密）
2. fnOS 自带 DDNS + HTTPS 反代（有公网 IP 时）
3. 纯局域网用（客户端离线优先，回家自动同步，日常完全可用）

## 四、从源码构建

```bash
# 服务端（需 Go 1.24+）
cd server && go test ./... && go build ./cmd/memosync

# E2E 冒烟（48 项）：先起服务，然后
node server/e2e.mjs                       # 默认打 http://127.0.0.1:5231
BASE=http://NAS-IP:5231 PWD_FILE=... node server/e2e.mjs

# Android（需 JDK 17 + Android SDK 35）
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

## 五、数据与安全

- 存储：`<数据目录>/memo.db`（SQLite/WAL）+ `attachments/`（内容寻址 sha256）+ `backup/`
- 每日 02:00 自动备份（保留 7 份）；管理页可手动备份、一键导出 zip（memos.json + 全部附件）
- 密码 argon2id；登录 token 可在管理页按设备下线；回收站软删除 30 天
- 无任何第三方 SDK / 埋点 / 外部推送（提醒用本地精确闹钟）

## 六、当前范围与已知取舍（不藏话）

已实现并验证的功能见根 README 底部「验证状态」。以下为**明确未做**（V2 计划，代码里没有假实现）：

- 资产密码的端到端加密（当前明文同步到自家 NAS，适用家庭共享场景；外网务必走 Tailscale/HTTPS，见「数字资产」页提示）
- 单条备忘录级别的 ACL（给单独某人授权某一条）——当前用「工作组」承载：把他拉进组即可，粒度是组不是条
- 桌面小组件、扫码自动填地址（配对码为 8 位手输，因未引入二维码生成库）
- Web 端完整读写界面（管理页仅管理功能；记事请用 Android 客户端）

## 七、验证状态（截至当前提交，全部真实执行）

| 项目 | 方式 | 结果 |
|---|---|---|
| 服务端单元/集成测试 | `go test ./...`（22 个测试：认证、同步生命周期、冲突、**清单条目级合并**、**标签**、**多条提醒**、**飞书/钉钉 Webhook 推送**、**工作组空间**、墓碑、分页、空间隔离、附件去重/秒传/ETag、配对码、成员设备管理、备份导出、**SSE 实时广播**、**备份恢复**、**管理页改端口/旧端口跳转**、并发竞争、清理、数字资产同步） | ✅ 全部通过 |
| 服务端 E2E（56 项断言） | `node server/e2e.mjs` 对真实运行实例按家庭场景走完整流程（管理员+妈妈+爸爸三账号、清单协作、附件秒传下载、数字资产创建/家人可见密码/续费更新、**SSE 毫秒级 change 事件**、**清单合并/标签同步**、配对码接入、改密踢设备、备份导出） | ✅ 48/48 |
| 管理页 | 浏览器实测：登录、成员管理、生成配对码（截图验证） | ✅ |
| linux 双架构二进制 | `CGO_ENABLED=0` 交叉编译 amd64/arm64，并在本地以新二进制复测 E2E | ✅ |
| Android 单元测试 | `gradle :app:testDebugUnitTest`（20 个测试：同步引擎 10 个 + 资产倒计时/提醒换算/内容兼容 6 个 + **小组件内容选择/自定义字段/多提醒编解码 4 个**） | ✅ 16/16 |
| Android APK | `gradle :app:assembleDebug` → dist/FamilyMemo-v1.1.0-debug.apk（minSdk 26 / targetSdk 35） | ✅ 编译打包成功 |
| FPK 包 | `bash fpk/build-fpk.sh`：官方 fnpack 1.2.1 打包并通过其 manifest 校验，包结构与已验证可本地安装的社区应用（Good-GYM）完全一致 | ✅ 打包成功（x86_64 + arm64）；**真机安装待确认**（作者无 fnOS 设备） |
| Android 真机联调 | —— | ⏳ 待真机（无设备；逻辑层已全部测试覆盖） |
| fnOS 实装 | —— | ⏳ 待 NAS（可先用 Docker 方式，行为与 FPK 内服务一致） |

