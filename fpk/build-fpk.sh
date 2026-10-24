#!/usr/bin/env bash
# 构建 fnOS 原生 fpk 安装包（官方 fnpack 工具路线，不依赖 Docker）
#
# 默认产物：dist/memosync_<version>_x86_64.fpk / _arm64.fpk
# 可用环境变量覆盖（用于灰度/测试实例）：
#   APPNAME=memosync-test DISPLAY="家庭备忘录(测试)" PORT=5232 VERSION=1.1.1 bash fpk/build-fpk.sh
#
# 前置：fnpack.exe（已随仓库放在 fpk/ 下）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER="$HERE/../server"
DIST="$HERE/../dist"
NATIVE="$HERE/native"
FNPACK="$HERE/fnpack.exe"

VERSION="${VERSION:-1.1.3}"
APPNAME="${APPNAME:-memosync}"
DISPLAY="${DISPLAY:-家庭备忘录}"
PORT="${PORT:-5231}"

mkdir -p "$DIST"

echo "==> 编译服务端（linux 双架构 + 本地调试）  [${APPNAME} ${VERSION} :${PORT}]"
(cd "$SERVER" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o "$DIST/memosync_linux_amd64" ./cmd/memosync)
(cd "$SERVER" && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o "$DIST/memosync_linux_arm64" ./cmd/memosync)
(cd "$SERVER" && go build -trimpath -ldflags "-X main.version=${VERSION}" -o "$DIST/memosync_local_dev.exe" ./cmd/memosync)

echo "==> 生成图标"
node "$HERE/../tools/gen-icon.mjs" >/dev/null

echo "==> 保留 Docker 部署物"
mkdir -p "$DIST/docker"
cp "$HERE/../deploy/Dockerfile" "$HERE/../deploy/docker-compose.yml" "$DIST/docker/" 2>/dev/null || true

for ARCH in x86_64 arm64; do
  echo "==> 组装 ${ARCH} 包源"
  case "$ARCH" in
    x86_64) BIN="$DIST/memosync_linux_amd64" ;;
    arm64)  BIN="$DIST/memosync_linux_arm64" ;;
  esac
  PKG="$HERE/pkg-${APPNAME}-${ARCH}"
  rm -rf "$PKG"
  mkdir -p "$PKG"
  cp -r "$NATIVE/cmd" "$NATIVE/config" "$NATIVE/app" "$NATIVE/wizard" "$PKG/"
  cp "$NATIVE/ICON.PNG" "$NATIVE/ICON_256.PNG" "$PKG/" 2>/dev/null || true
  sed -e "s/@ARCH@/${ARCH}/g" -e "s/@APPNAME@/${APPNAME}/g" -e "s/@VERSION@/${VERSION}/g" \
      -e "s/@PORT@/${PORT}/g" -e "s/@DISPLAY@/${DISPLAY}/g" \
      "$NATIVE/manifest.tmpl" > "$PKG/manifest"
  sed -e "s/@APPNAME@/${APPNAME}/g" -e "s/@PORT@/${PORT}/g" -e "s/@DISPLAY@/${DISPLAY}/g" \
      "$NATIVE/app/ui/config.tmpl" > "$PKG/app/ui/config"
  rm -f "$PKG/app/ui/config.tmpl"
  mkdir -p "$PKG/app/bin" "$PKG/app/conf"
  cp "$BIN" "$PKG/app/bin/memosync"
  chmod +x "$PKG/app/bin/memosync" "$PKG/cmd/"* 2>/dev/null || true

  echo "==> fnpack 打包 ${ARCH}"
  (cd "$PKG" && "$FNPACK" build -d .)
  produced=$(ls "$PKG"/*.fpk 2>/dev/null | head -1)
  [ -z "$produced" ] && produced=$(ls "$HERE"/*.fpk 2>/dev/null | head -1)
  if [ -z "$produced" ]; then
    echo "错误：未找到 fnpack 产物" >&2
    exit 1
  fi
  mv "$produced" "$DIST/${APPNAME}_${VERSION}_${ARCH}.fpk"
  echo "    -> dist/${APPNAME}_${VERSION}_${ARCH}.fpk"
  rm -rf "$PKG"
done

echo "完成。产物清单："
ls -la "$DIST" | grep fpk
