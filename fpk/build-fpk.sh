#!/usr/bin/env bash
# 构建 fnOS 原生 fpk 安装包（官方 fnpack 工具路线，不依赖 Docker）
# 产物：dist/memosync_1.0.0_x86_64.fpk / dist/memosync_1.0.0_arm64.fpk
#
# 前置：fnpack.exe（已随仓库放在 fpk/ 下，v1.2.1 与社区验证版本一致）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER="$HERE/../server"
DIST="$HERE/../dist"
NATIVE="$HERE/native"
FNPACK="$HERE/fnpack.exe"
VERSION="1.1.0"

mkdir -p "$DIST"

echo "==> 编译服务端（linux 双架构 + 本地调试）"
(cd "$SERVER" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o "$DIST/memosync_linux_amd64" ./cmd/memosync)
(cd "$SERVER" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o "$DIST/memosync_linux_arm64" ./cmd/memosync)
(cd "$SERVER" && go build -trimpath -o "$DIST/memosync_local_dev.exe" ./cmd/memosync)

echo "==> 生成图标"
node "$HERE/../tools/gen-icon.mjs" >/dev/null

echo "==> 保留 Docker 部署物"
cp "$HERE/../deploy/Dockerfile" "$HERE/../deploy/docker-compose.yml" "$DIST/docker/" 2>/dev/null || { mkdir -p "$DIST/docker"; cp "$HERE/../deploy/Dockerfile" "$HERE/../deploy/docker-compose.yml" "$DIST/docker/"; }

for ARCH in x86_64 arm64; do
  echo "==> 组装 ${ARCH} 包源"
  case "$ARCH" in
    x86_64) BIN="$DIST/memosync_linux_amd64" ;;
    arm64)  BIN="$DIST/memosync_linux_arm64" ;;
  esac
  PKG="$HERE/pkg-$ARCH"
  rm -rf "$PKG"
  mkdir -p "$PKG"
  # 包源结构 = 官方 fnpack create 模板结构
  cp -r "$NATIVE/cmd" "$NATIVE/config" "$NATIVE/app" "$NATIVE/wizard" "$PKG/"
  cp "$NATIVE/ICON.PNG" "$NATIVE/ICON_256.PNG" "$PKG/"
  sed "s/@ARCH@/$ARCH/" "$NATIVE/manifest.tmpl" > "$PKG/manifest"
  mkdir -p "$PKG/app/bin" "$PKG/app/conf"
  cp "$BIN" "$PKG/app/bin/memosync"
  chmod +x "$PKG/app/bin/memosync" "$PKG/cmd/"*

  echo "==> fnpack 打包 ${ARCH}"
  (cd "$PKG" && "$FNPACK" build -d .)
  produced=$(ls "$PKG"/*.fpk 2>/dev/null | head -1)
  if [ -z "$produced" ]; then
    produced=$(ls "$HERE"/*.fpk 2>/dev/null | head -1)
  fi
  if [ -z "$produced" ]; then
    echo "错误：未找到 fnpack 产物" >&2
    exit 1
  fi
  mv "$produced" "$DIST/memosync_${VERSION}_${ARCH}.fpk"
  echo "    -> dist/memosync_${VERSION}_${ARCH}.fpk"
done

echo "完成。产物清单："
ls -la "$DIST" | grep fpk
