#!/usr/bin/env bash
# 在国内机器上构建并发布后端，不经过 GitHub 托管 runner 跨境传包。
# 环境变量沿用服务器上已有的 /opt/Bot-Brief/.env.deploy，不从 GitHub Secrets 覆盖。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/Bot-Brief}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-bot-brief-backend}"
ENV_FILE="$DEPLOY_DIR/.env.deploy"
COMPOSE_ENV_FILE="$ENV_FILE"
readable_env_copy=""

cleanup() {
  if [ -n "$readable_env_copy" ]; then
    rm -f "$readable_env_copy"
  fi
}
trap cleanup EXIT

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

prepare_env_file() {
  if [ ! -e "$ENV_FILE" ]; then
    echo "Missing $ENV_FILE. Keep the existing production env file on the server."
    exit 1
  fi
  if [ -r "$ENV_FILE" ]; then
    return 0
  fi

  echo "Cannot read $ENV_FILE as $(id -un)."
  ls -l "$ENV_FILE" 2>/dev/null || sudo -n ls -l "$ENV_FILE" 2>/dev/null || true

  if sudo -n cat "$ENV_FILE" >/dev/null 2>&1; then
    readable_env_copy="$(mktemp)"
    sudo -n cat "$ENV_FILE" > "$readable_env_copy"
    chmod 600 "$readable_env_copy"
    ENV_FILE="$readable_env_copy"
    COMPOSE_ENV_FILE="$readable_env_copy"
    echo "Using a temporary readable copy of the deploy env file."
    return 0
  fi

  echo "Grant the GitHub Actions runner read access, then rerun deploy:"
  echo "  sudo chmod 640 $DEPLOY_DIR/.env.deploy"
  echo "  sudo chgrp $(id -gn) $DEPLOY_DIR/.env.deploy"
  exit 1
}

env_value() {
  awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); gsub(/\r/, ""); print; exit}' "$ENV_FILE"
}

require_env() {
  local key="$1"
  local value
  value="$(env_value "$key")"
  if [ -z "$value" ]; then
    echo "Missing or empty $key in deploy env file"
    exit 1
  fi
  printf '%s' "$value"
}

prepare_env_file

ensure_image() {
  local name="$1"
  shift
  if docker image inspect "$name" >/dev/null 2>&1; then
    echo "Using local image $name"
    return 0
  fi
  local src
  for src in "$@"; do
    echo "Pulling $src"
    if docker pull "$src"; then
      docker tag "$src" "$name"
      return 0
    fi
  done
  echo "Missing image $name. Pull it on the server via a China mirror and retag as $name."
  return 1
}

ensure_image eclipse-temurin:17-jre \
  docker.m.daocloud.io/library/eclipse-temurin:17-jre \
  m.daocloud.io/docker.io/library/eclipse-temurin:17-jre \
  eclipse-temurin:17-jre
ensure_image maven:3.9-eclipse-temurin-17 \
  docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
  m.daocloud.io/docker.io/library/maven:3.9-eclipse-temurin-17 \
  maven:3.9-eclipse-temurin-17
ensure_image mysql:8.0 \
  docker.m.daocloud.io/library/mysql:8.0 \
  m.daocloud.io/docker.io/library/mysql:8.0 \
  mysql:8.0

echo "Building backend $image_tag"
docker build -t "$image_tag" "$ROOT/backend"

db_host="$(require_env DB_HOST)"
db_port="$(require_env DB_PORT)"
db_user="$(require_env DB_USER)"
db_name="$(require_env DB_NAME)"
db_password="$(require_env DB_PASSWORD)"
schema_ready="$(
  docker run --rm --pull=never \
    --add-host=host.docker.internal:host-gateway \
    -e MYSQL_PWD="$db_password" \
    mysql:8.0 \
    mysql -N \
    -h "$db_host" \
    -P "$db_port" \
    -u "$db_user" \
    "$db_name" \
    -e "SELECT COUNT(*) = 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'topic_sections' UNION ALL SELECT COUNT(*) = 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'reports' AND column_name = 'user_id';"
)"
if [ "$(printf '%s\n' "$schema_ready" | tr -d '\r' | sort -u)" != "1" ]; then
  docker image rm "$image_tag" >/dev/null 2>&1 || true
  echo "Database schema is missing V10. Back up the database and run backend/sql/V10__topic_sections_and_user_reports.sql before deployment."
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
BACKEND_IMAGE="$image_tag" docker compose --env-file "$COMPOSE_ENV_FILE" up -d --no-build --force-recreate backend

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
    BACKEND_IMAGE="$previous_image" docker compose --env-file "$COMPOSE_ENV_FILE" up -d --no-build --force-recreate backend
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
