#!/usr/bin/env bash
# 从国内机器下载 GitHub 源码包。代理对 git 协议经常 404，所以走 archive。
set -euo pipefail

test -n "${GITHUB_WORKSPACE:-}" || { echo "GITHUB_WORKSPACE is required"; exit 1; }
test -n "${GITHUB_REPOSITORY:-}" || { echo "GITHUB_REPOSITORY is required"; exit 1; }
test -n "${GITHUB_SHA:-}" || { echo "GITHUB_SHA is required"; exit 1; }

mkdir -p "$GITHUB_WORKSPACE"
cd "$GITHUB_WORKSPACE"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

candidates=(
  "https://gh-proxy.com/https://github.com/${GITHUB_REPOSITORY}/archive/${GITHUB_SHA}.tar.gz"
  "https://gh-proxy.com/https://codeload.github.com/${GITHUB_REPOSITORY}/tar.gz/${GITHUB_SHA}"
  "https://ghfast.top/https://github.com/${GITHUB_REPOSITORY}/archive/${GITHUB_SHA}.tar.gz"
  "https://ui.ghproxy.cc/https://github.com/${GITHUB_REPOSITORY}/archive/${GITHUB_SHA}.tar.gz"
  "https://gh-proxy.com/https://github.com/${GITHUB_REPOSITORY}/archive/refs/heads/${GITHUB_REF_NAME:-main}.tar.gz"
)

archive="$tmp/src.tar.gz"
downloaded=false
for url in "${candidates[@]}"; do
  echo "Downloading source archive"
  if curl -fL --retry 2 --connect-timeout 20 --max-time 180 -o "$archive" "$url"; then
    if tar -tzf "$archive" >/dev/null 2>&1; then
      downloaded=true
      break
    fi
  fi
  rm -f "$archive"
done
if [ "$downloaded" != "true" ]; then
  echo "Could not download the repository archive via proxy"
  exit 1
fi

tar -xzf "$archive" -C "$tmp"
extracted="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
test -n "$extracted"
find "$GITHUB_WORKSPACE" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -a "$extracted"/. "$GITHUB_WORKSPACE/"
test -f docker-compose.yml
test -d frontend
test -d backend
