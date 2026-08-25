#!/usr/bin/env bash
# 在国内机器上构建并发布后端，不经过 GitHub 托管 runner 跨境传包。
# 环境变量沿用服务器上已有的 /opt/Bot-Brief/.env.deploy，不从 GitHub Secrets 覆盖。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/Bot-Brief}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-bot-brief-backend}"
ENV_FILE="$DEPLOY_DIR/.env.deploy"

if [ -n "${GITHUB_SHA:-}" ]; then
  short_sha="$(printf '%s' "$GITHUB_SHA" | cut -c1-12)"
elif git -C "$ROOT" rev-parse --short=12 HEAD >/dev/null 2>&1; then
  short_sha="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
else
  echo "Cannot determine release SHA"
  exit 1
fi
image_tag="${IMAGE_REPOSITORY}:${short_sha}"

command -v docker >/dev/null 2>&1 || { echo "Docker is required"; exit 1; }
docker compose version >/dev/null
test -f "$ROOT/backend/Dockerfile"
test -f "$ROOT/docker-compose.yml"
mkdir -p "$DEPLOY_DIR"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Keep the existing production env file on the server."
  exit 1
fi

env_value() {
  awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE"
}

for required in maven:3.9-eclipse-temurin-17 eclipse-temurin:17-jre mysql:8.0; do
  if ! docker image inspect "$required" >/dev/null 2>&1; then
    echo "Missing local image $required. Pull once on the server, then retry:"
    echo "  docker pull docker.m.daocloud.io/library/mysql:8.0"
    echo "  docker tag docker.m.daocloud.io/library/mysql:8.0 mysql:8.0"
    echo "Maven/JRE images must already exist from a previous backend deploy, or pull them via a China mirror and retag."
    exit 1
  fi
done

echo "Building backend $image_tag"
docker build -t "$image_tag" "$ROOT/backend"

schema_ready="$(
  docker run --rm --pull=never \
    --add-host=host.docker.internal:host-gateway \
    -e MYSQL_PWD="$(env_value DB_PASSWORD)" \
    mysql:8.0 \
    mysql -N \
    -h "$(env_value DB_HOST)" \
    -P "$(env_value DB_PORT)" \
    -u "$(env_value DB_USER)" \
    "$(env_value DB_NAME)" \
    -e "SELECT COUNT(*) = 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'etf_price_history' UNION ALL SELECT COUNT(*) = 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'market_valuation_history' AND column_name = 'percentile_method';"
)"
if [ "$(printf '%s\n' "$schema_ready" | tr -d '\r' | sort -u)" != "1" ]; then
  docker image rm "$image_tag" >/dev/null 2>&1 || true
  echo "Database schema is missing V7. Back up the database and run backend/sql/V7__market_data_history.sql before deployment."
  exit 1
fi

previous_image="$(docker inspect --format '{{.Config.Image}}' bot-brief-backend 2>/dev/null || true)"
had_previous_compose=false
if [ -f "$DEPLOY_DIR/docker-compose.yml" ]; then
  cp -p "$DEPLOY_DIR/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml.previous"
  had_previous_compose=true
fi
install -m 644 "$ROOT/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml"
cd "$DEPLOY_DIR"
BACKEND_IMAGE="$image_tag" docker compose --env-file .env.deploy up -d --no-build --force-recreate backend

healthy=false
for _ in $(seq 1 30); do
  if curl -fsS --max-time 5 http://127.0.0.1:8081/api/health >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done

if [ "$healthy" != "true" ]; then
  echo "Backend health check failed"
  docker logs --tail 200 bot-brief-backend || true
  if [ "$had_previous_compose" = "true" ]; then
    install -m 644 "$DEPLOY_DIR/docker-compose.yml.previous" "$DEPLOY_DIR/docker-compose.yml"
  fi
  if [ -n "$previous_image" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
    BACKEND_IMAGE="$previous_image" docker compose --env-file .env.deploy up -d --no-build --force-recreate backend
  fi
  docker image rm "$image_tag" >/dev/null 2>&1 || true
  rm -f "$DEPLOY_DIR/docker-compose.yml.previous"
  exit 1
fi
rm -f "$DEPLOY_DIR/docker-compose.yml.previous"

if [ -n "$previous_image" ] && [ "$previous_image" != "$image_tag" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
  docker tag "$previous_image" bot-brief-backend:previous
else
  docker image rm bot-brief-backend:previous >/dev/null 2>&1 || true
fi
docker tag "$image_tag" bot-brief-backend:local
current_id="$(docker image inspect --format '{{.Id}}' "$image_tag")"
previous_id="$(docker image inspect --format '{{.Id}}' bot-brief-backend:previous 2>/dev/null || true)"
while IFS= read -r old_image; do
  [ -n "$old_image" ] || continue
  old_id="$(docker image inspect --format '{{.Id}}' "$old_image" 2>/dev/null || true)"
  if [ "$old_id" != "$current_id" ] && [ "$old_id" != "$previous_id" ]; then
    docker image rm "$old_image" >/dev/null 2>&1 || true
  fi
done < <(docker image ls bot-brief-backend --format '{{.Repository}}:{{.Tag}}' | grep -E '^bot-brief-backend:[0-9a-f]{12,}$' || true)
docker image prune -f
echo "Backend deployed: $image_tag"
