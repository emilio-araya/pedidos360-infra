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
[[ -f "$ENV_FILE" ]] || fail "Falta $ENV_FILE. Copia .env.example como .env y completa los valores."

set -a
source "$ENV_FILE"
set +a

required_vars=(
  ORACLE_PASSWORD
  CATALOG_DB_USERNAME
  CATALOG_DB_PASSWORD
  ORDERS_DB_USERNAME
  ORDERS_DB_PASSWORD
  ENTRA_TENANT_ID
  ENTRA_CLIENT_ID
  ENTRA_ISSUER
  ENTRA_API_AUDIENCE
  API_SCOPE
  API_BASE_URL
  CORS_ALLOWED_ORIGINS
  COGNITO_USER_POOL_ID
  COGNITO_USER_POOL_CLIENT_ID
  COGNITO_DOMAIN
  COGNITO_ISSUER
  COGNITO_API_AUDIENCE
  COGNITO_REDIRECT_URI
  COGNITO_LOGOUT_URI
  COGNITO_API_SCOPE
)

for variable_name in "${required_vars[@]}"; do
  [[ -n "${!variable_name:-}" ]] || fail "Falta o está vacío $variable_name en $ENV_FILE."
done

zero_guid='00000000-0000-0000-0000-000000000000'
guid_pattern='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

[[ "$ENTRA_TENANT_ID" =~ $guid_pattern ]] || fail 'ENTRA_TENANT_ID debe ser un GUID.'
[[ "$ENTRA_CLIENT_ID" =~ $guid_pattern ]] || fail 'ENTRA_CLIENT_ID debe ser un GUID.'
[[ "$ENTRA_API_AUDIENCE" =~ $guid_pattern ]] || \
  fail 'ENTRA_API_AUDIENCE debe ser el Application (client) ID GUID de la API.'

for identifier in ENTRA_TENANT_ID ENTRA_CLIENT_ID ENTRA_API_AUDIENCE; do
  [[ "${!identifier}" != "$zero_guid" ]] || fail "$identifier sigue usando el GUID de ejemplo."
done
[[ "$ENTRA_CLIENT_ID" != "$ENTRA_API_AUDIENCE" ]] || \
  fail 'La SPA y la API deben tener Application (client) ID distintos.'

expected_scope="api://${ENTRA_API_AUDIENCE}/access_as_user"
[[ "$API_SCOPE" == "$expected_scope" ]] || \
  fail "API_SCOPE debe ser $expected_scope."

[[ "$CORS_ALLOWED_ORIGINS" != '*' ]] || \
  fail 'CORS_ALLOWED_ORIGINS no debe usar el comodín *.'

expected_issuer="https://login.microsoftonline.com/${ENTRA_TENANT_ID}/v2.0"
[[ "$ENTRA_ISSUER" == "$expected_issuer" ]] || \
  fail "ENTRA_ISSUER debe ser $expected_issuer."

expected_cognito_issuer="https://cognito-idp.us-east-1.amazonaws.com/${COGNITO_USER_POOL_ID}"
[[ "$COGNITO_ISSUER" == "$expected_cognito_issuer" ]] || \
  fail "COGNITO_ISSUER debe ser $expected_cognito_issuer."
[[ "$COGNITO_API_AUDIENCE" == "$COGNITO_USER_POOL_CLIENT_ID" ]] || \
  fail 'COGNITO_API_AUDIENCE debe coincidir con el App Client público en la configuración baseline.'
[[ "$COGNITO_DOMAIN" != *http://* && "$COGNITO_DOMAIN" != *https://* ]] || \
  fail 'COGNITO_DOMAIN debe ser solo el hostname de Hosted UI.'
[[ "$COGNITO_REDIRECT_URI" == */auth/cognito/callback ]] || \
  fail 'COGNITO_REDIRECT_URI debe ser /auth/cognito/callback.'
[[ " $COGNITO_API_SCOPE " == *' openid '* ]] || \
  fail 'COGNITO_API_SCOPE debe incluir openid.'

validate_oracle_password() {
  local variable_name="$1"
  local value="${!variable_name}"

  [[ ${#value} -ge 8 ]] || fail "$variable_name debe tener al menos 8 caracteres."
  [[ "$value" =~ [A-Z] ]] || fail "$variable_name debe incluir una mayúscula."
  [[ "$value" =~ [a-z] ]] || fail "$variable_name debe incluir una minúscula."
  [[ "$value" =~ [0-9] ]] || fail "$variable_name debe incluir un dígito."
  [[ "$value" =~ [^[:alnum:]] ]] || fail "$variable_name debe incluir un carácter especial."
}

oracle_username_pattern='^[A-Z][A-Z0-9_$#]{2,29}$'
[[ "$CATALOG_DB_USERNAME" =~ $oracle_username_pattern ]] || \
  fail 'CATALOG_DB_USERNAME debe ser un identificador Oracle no citado de 3 a 30 caracteres.'
[[ "$ORDERS_DB_USERNAME" =~ $oracle_username_pattern ]] || \
  fail 'ORDERS_DB_USERNAME debe ser un identificador Oracle no citado de 3 a 30 caracteres.'
[[ "$CATALOG_DB_USERNAME" != "$ORDERS_DB_USERNAME" ]] || \
  fail 'Catalog y orders deben usar usuarios Oracle distintos.'

validate_oracle_password ORACLE_PASSWORD

validate_app_password() {
  local variable_name="$1"
  local value="${!variable_name}"
  validate_oracle_password "$variable_name"
  [[ "$value" != *'"'* && "$value" != *$'\n'* && "$value" != *$'\r'* ]] || \
    fail "$variable_name no puede contener comillas dobles ni saltos de línea."
}

validate_app_password CATALOG_DB_PASSWORD
validate_app_password ORDERS_DB_PASSWORD

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

printf 'Validando la definición de Docker Compose...\n'
compose config --quiet

printf 'Construyendo y levantando Oracle, catalog, orders, bff y frontend...\n'
if ! compose up --build --detach --remove-orphans; then
  printf 'Falló el arranque. Revise el estado con:\n  docker compose --env-file %q -f %q ps\n' "$ENV_FILE" "$COMPOSE_FILE" >&2
  exit 1
fi

compose ps

cat <<EOF

Pedidos360 local quedó iniciado.
  Frontend: http://localhost:${FRONTEND_PORT:-4200}
  BFF:      http://localhost:${BFF_PORT:-8080}

Solo frontend y BFF publican puertos, y ambos se limitan a 127.0.0.1.
Oracle, catalog y orders no tienen puertos publicados; el tráfico de negocio entre
BFF, catalog, orders y Oracle usa la red interna de Compose.
Consulte los logs con: docker compose --env-file $ENV_FILE -f $COMPOSE_FILE logs -f
EOF
