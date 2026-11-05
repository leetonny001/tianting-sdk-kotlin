#!/usr/bin/env bash
# 给 CI 配置读取 tianting-server 的只读部署密钥（由仓库所有者本人运行）：
# 生成一次性 ed25519 密钥 → 公钥作为 tianting-server 的只读 Deploy key → 私钥存为本仓库的 Actions secret → 删除本地密钥文件。
# 私钥只经过管道，不打印、不留在磁盘上。
set -euo pipefail
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
ssh-keygen -q -t ed25519 -N '' -C tianting-sdk-kotlin-ci -f "$dir/key"
gh repo deploy-key add "$dir/key.pub" -R leetonny001/tianting-server -t tianting-sdk-kotlin-ci
gh secret set TIANTING_SERVER_DEPLOY_KEY -R leetonny001/tianting-sdk-kotlin < "$dir/key"
echo "完成：tianting-server 已添加只读部署密钥，tianting-sdk-kotlin 已设置 secret；本地密钥文件已删除。"
