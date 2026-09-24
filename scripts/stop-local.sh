#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail 'docker no está instalado.'
docker compose version >/dev/null 2>&1 || fail 'Se requiere Docker Compose v2 (docker compose).'
[[ -f "$COMPOSE_FILE" ]] || fail "No existe $COMPOSE_FILE"
[[ -f "$ENV_FILE" ]] || fail "Falta $ENV_FILE, requerido para resolver Docker Compose."

compose_args=(--env-file "$ENV_FILE" --file "$COMPOSE_FILE")

printf 'Deteniendo y eliminando los contenedores de Pedidos360...\n'
docker compose "${compose_args[@]}" down --remove-orphans --timeout 60

cat <<EOF
El entorno local se detuvo. El volumen Oracle del proyecto se conserva.
Para borrar también los datos de Oracle, ejecute de forma explícita:
  docker compose --env-file $ENV_FILE -f $COMPOSE_FILE down --volumes
EOF
