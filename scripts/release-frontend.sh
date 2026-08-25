#!/usr/bin/env bash
# 在国内机器（推荐：腾讯云服务器）上构建并发布前端，不经过 GitHub 托管 runner 跨境传包。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/Bot-Brief}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-bot-brief-frontend}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
short_sha="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
image_tag="${IMAGE_REPOSITORY}:${short_sha}"

command -v docker >/dev/null 2>&1 || { echo "Docker is required"; exit 1; }
docker compose version >/dev/null
test -f "$ROOT/frontend/package.json"
mkdir -p "$DEPLOY_DIR"

if ! command -v npm >/dev/null 2>&1; then
  echo "Install Node.js 18+ on this machine first: https://npmmirror.com/mirrors/node"
  exit 1
fi

echo "Building frontend $image_tag"
npm ci --prefix "$ROOT/frontend" --registry "$NPM_REGISTRY"
npm run build --prefix "$ROOT/frontend"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/dist"
cp -a "$ROOT/frontend/dist/." "$stage/dist/"
printf '%s\n' \
  'server {' \
  '    listen 80;' \
  '' \
  '    location / {' \
  '        root /usr/share/nginx/html;' \
  '        index index.html index.htm;' \
  '        try_files $uri $uri/ /index.html;' \
  '    }' \
  '}' \
  > "$stage/default.conf"

base_image=""
for candidate in bot-brief-frontend:local bot-brief-frontend:previous nginx:alpine; do
  if docker image inspect "$candidate" >/dev/null 2>&1; then
    base_image="$candidate"
    break
  fi
done
if [ -z "$base_image" ]; then
  running_image="$(docker inspect --format '{{.Config.Image}}' bot-brief-frontend 2>/dev/null || true)"
  if [ -n "$running_image" ] && docker image inspect "$running_image" >/dev/null 2>&1; then
    base_image="$running_image"
  fi
fi
if [ -z "$base_image" ]; then
  echo "No local nginx/frontend image. Pull once on the server, then retry:"
  echo "  docker pull docker.m.daocloud.io/library/nginx:alpine"
  echo "  docker tag docker.m.daocloud.io/library/nginx:alpine nginx:alpine"
  exit 1
fi

printf '%s\n' \
  "FROM $base_image" \
  'COPY dist/ /usr/share/nginx/html/' \
  'COPY default.conf /etc/nginx/conf.d/default.conf' \
  > "$stage/Dockerfile"

previous_image="$(docker inspect --format '{{.Config.Image}}' bot-brief-frontend 2>/dev/null || true)"
docker build --no-cache -t "$image_tag" "$stage"
install -m 644 "$ROOT/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml"
cd "$DEPLOY_DIR"
FRONTEND_IMAGE="$image_tag" docker compose up -d --no-build --force-recreate frontend

healthy=false
for _ in $(seq 1 20); do
  if curl -fsS --max-time 5 http://127.0.0.1:8080/ >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done

if [ "$healthy" != "true" ]; then
  echo "Frontend health check failed"
  docker logs --tail 200 bot-brief-frontend || true
  if [ -n "$previous_image" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
    FRONTEND_IMAGE="$previous_image" docker compose up -d --no-build --force-recreate frontend
  fi
  docker image rm "$image_tag" >/dev/null 2>&1 || true
  exit 1
fi

if [ -n "$previous_image" ] && [ "$previous_image" != "$image_tag" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
  docker tag "$previous_image" bot-brief-frontend:previous
else
  docker image rm bot-brief-frontend:previous >/dev/null 2>&1 || true
fi
docker tag "$image_tag" bot-brief-frontend:local
current_id="$(docker image inspect --format '{{.Id}}' "$image_tag")"
previous_id="$(docker image inspect --format '{{.Id}}' bot-brief-frontend:previous 2>/dev/null || true)"
while IFS= read -r old_image; do
  [ -n "$old_image" ] || continue
  old_id="$(docker image inspect --format '{{.Id}}' "$old_image" 2>/dev/null || true)"
  if [ "$old_id" != "$current_id" ] && [ "$old_id" != "$previous_id" ]; then
    docker image rm "$old_image" >/dev/null 2>&1 || true
  fi
done < <(docker image ls bot-brief-frontend --format '{{.Repository}}:{{.Tag}}' | grep -E '^bot-brief-frontend:[0-9a-f]{12,}$' || true)
docker image prune -f
echo "Frontend deployed: $image_tag"
