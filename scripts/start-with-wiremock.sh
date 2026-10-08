#!/usr/bin/env bash
# ============================================
# Pedidos360 — Inicio rápido con WireMock
# ============================================
# Este script inicia el stack completo usando WireMock
# como Identity Provider simulado en lugar de HMAC.
#
# Uso:
#   ./scripts/start-with-wiremock.sh
#
# Requisitos:
#   - Docker y Docker Compose
#   - Los 5 repositorios clonados como carpetas hermanas
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
REPOS_DIR="$(dirname "$INFRA_DIR")"

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Verificar que los repositorios existen
check_repos() {
    log_info "Verificando repositorios hermanos..."

    local repos=("pedidos360-frontend" "pedidos360-bff" "pedidos360-catalog" "pedidos360-orders")
    local missing=()

    for repo in "${repos[@]}"; do
        if [ ! -d "$REPOS_DIR/$repo" ]; then
            missing+=("$repo")
        fi
    done

    if [ ${#missing[@]} -ne 0 ]; then
        log_error "Faltan los siguientes repositorios:"
        for repo in "${missing[@]}"; do
            echo "  - $repo"
        done
        log_info "Clona los repositorios en: $REPOS_DIR"
        exit 1
    fi

    log_success "Todos los repositorios están presentes"
}

# Verificar que .env existe
check_env() {
    if [ ! -f "$INFRA_DIR/.env" ]; then
        log_warn "No se encontró .env"
        log_info "Creando .env desde .env.example..."
        cp "$INFRA_DIR/.env.example" "$INFRA_DIR/.env"
        log_warn "Edita .env con tus valores reales antes de continuar"
        log_info "Especialmente: ORACLE_PASSWORD, CATALOG_DB_PASSWORD, ORDERS_DB_PASSWORD"
    fi
}

# Iniciar WireMock
start_wiremock() {
    log_info "Iniciando WireMock..."
    cd "$INFRA_DIR"
    docker compose up -d wiremock

    # Esperar a que WireMock esté listo
    log_info "Esperando a que WireMock esté listo..."
    local attempts=0
    local max_attempts=30

    while [ $attempts -lt $max_attempts ]; do
        if curl -s http://localhost:8089/__admin/health > /dev/null 2>&1; then
            log_success "WireMock está listo en http://localhost:8089"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 1
    done

    log_error "WireMock no respondió después de $max_attempts intentos"
    return 1
}

# Iniciar el stack completo
start_stack() {
    log_info "Iniciando el stack completo..."
    cd "$INFRA_DIR"

    # Cargar variables de entorno
    set -a
    source .env
    set +a

    # Iniciar servicios
    docker compose up -d

    log_success "Stack iniciado"
}

# Mostrar información útil
show_info() {
    echo ""
    echo "============================================"
    echo "  Pedidos360 — Stack local con WireMock"
    echo "============================================"
    echo ""
    echo "Servicios:"
    echo "  Frontend:    http://localhost:4200"
    echo "  BFF:         http://localhost:8080"
    echo "  WireMock:    http://localhost:8089"
    echo ""
    echo "Endpoints de WireMock:"
    echo "  Token:       POST http://localhost:8089/oauth2/v2.0/token"
    echo "  JWKS:        GET  http://localhost:8089/discovery/v2.0/keys"
    echo "  Authorize:   GET  http://localhost:8089/authorize"
    echo ""
    echo "Comandos útiles:"
    echo "  Ver logs:    docker compose logs -f"
    echo "  Detener:     ./scripts/stop-local.sh"
    echo "  Reiniciar:   ./scripts/restart-local.sh"
    echo ""
    echo "Documentación:"
    echo "  WireMock:    $INFRA_DIR/wiremock/README.md"
    echo "  Alternativas: $INFRA_DIR/docs/LOCAL_DEV_ALTERNATIVES.md"
    echo ""
}

# Limpiar al salir
cleanup() {
    echo ""
    log_info "Deteniendo servicios..."
    cd "$INFRA_DIR"
    docker compose down
    log_success "Servicios detenidos"
}

# Main
main() {
    trap cleanup EXIT

    log_info "Iniciando Pedidos360 con WireMock..."

    check_repos
    check_env
    start_wiremock
    start_stack
    show_info

    log_info "Presiona Ctrl+C para detener los servicios"
    wait
}

main "$@"
