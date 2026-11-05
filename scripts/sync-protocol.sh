#!/usr/bin/env bash
# 从 tianting-server 检出同步协议文件（errors.json、schema、vectors），然后重新生成 ErrorCodes.gen.kt。
# 用法：scripts/sync-protocol.sh [tianting-server 检出目录]，默认 ../tianting-server（或旧名 ../im-mvp）。
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:-}"
if [ -z "$src" ]; then
  for c in ../tianting-server ../im-mvp; do [ -d "$c/protocol" ] && src="$c" && break; done
fi
[ -n "$src" ] && [ -d "$src/protocol" ] || { echo "找不到 tianting-server 检出目录" >&2; exit 1; }
rm -rf protocol/schema protocol/vectors
cp "$src/protocol/errors.json" protocol/
cp -R "$src/protocol/schema" "$src/protocol/vectors" protocol/
git -C "$src" rev-parse --short HEAD > protocol/SOURCE
./gradlew -q :sdk-core:generateErrors
echo "已同步到 tianting-server@$(cat protocol/SOURCE)"
