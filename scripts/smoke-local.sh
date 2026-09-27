#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT="${PEDIDOS360_LOG_DIR:-/tmp/opencode/pedidos360-smoke}"
mkdir -p "$LOG_ROOT"
RUN_DIR="$(mktemp -d "$LOG_ROOT/run.XXXXXX")"

CATALOG_JAR="$ROOT_DIR/../pedidos360-catalog/target/ms-pedidos360-catalog-1.0.0.jar"
ORDERS_JAR="$ROOT_DIR/../pedidos360-orders/target/ms-pedidos360-orders-1.0.0.jar"
BFF_JAR="$ROOT_DIR/../pedidos360-bff/target/ms-pedidos360-bff-1.0.0.jar"
TOKEN_SCRIPT="$ROOT_DIR/../pedidos360-bff/scripts/generate-local-token.py"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  printf 'Logs: %s\n' "$RUN_DIR" >&2
  exit 1
}

for jar in "$CATALOG_JAR" "$ORDERS_JAR" "$BFF_JAR"; do
  [[ -f "$jar" ]] || fail "Falta $jar. Ejecuta mvn clean verify en los tres backend."
done
command -v java >/dev/null 2>&1 || fail 'java no está instalado.'
command -v curl >/dev/null 2>&1 || fail 'curl no está instalado.'
command -v python3 >/dev/null 2>&1 || fail 'python3 no está instalado.'

for port in 8080 8081 8082; do
  if (echo >"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    fail "El puerto $port ya está ocupado."
  fi
done

CLIENT_TOKEN="$($TOKEN_SCRIPT --role Cliente --oid smoke-client)"
OTHER_CLIENT_TOKEN="$($TOKEN_SCRIPT --role Cliente --oid smoke-other-client)"
OPERATOR_TOKEN="$($TOKEN_SCRIPT --role Operador --oid smoke-operator)"

java -jar "$CATALOG_JAR" --spring.profiles.active=local >"$RUN_DIR/catalog.log" 2>&1 &
CATALOG_PID=$!
java -jar "$ORDERS_JAR" --spring.profiles.active=local >"$RUN_DIR/orders.log" 2>&1 &
ORDERS_PID=$!
java -jar "$BFF_JAR" --spring.profiles.active=local >"$RUN_DIR/bff.log" 2>&1 &
BFF_PID=$!

cleanup() {
  kill "$BFF_PID" "$ORDERS_PID" "$CATALOG_PID" 2>/dev/null || true
  wait "$BFF_PID" "$ORDERS_PID" "$CATALOG_PID" 2>/dev/null || true
}
trap cleanup EXIT

ready=0
for _ in {1..45}; do
  if curl -fsS 'http://127.0.0.1:8082/actuator/health' >/dev/null 2>&1 \
    && curl -fsS 'http://127.0.0.1:8081/actuator/health' >/dev/null 2>&1 \
    && curl -fsS 'http://127.0.0.1:8080/actuator/health' >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
[[ "$ready" -eq 1 ]] || fail 'Los servicios no respondieron health dentro del tiempo esperado.'

expect_status() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: se esperaba $expected y se obtuvo $actual"
}

json_value() {
  local file="$1"
  local expression="$2"
  python3 - "$file" "$expression" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
print(eval(sys.argv[2], {"__builtins__": {}}, {"value": value}))
PY
}

status=$(curl -sS -o "$RUN_DIR/no-token.json" -w '%{http_code}' 'http://127.0.0.1:8080/api/orders')
expect_status 401 "$status" 'Petición sin token'

status=$(curl -sS -o "$RUN_DIR/no-token-aws.json" -w '%{http_code}' 'http://127.0.0.1:8080/aws/api/orders')
expect_status 401 "$status" 'Petición AWS sin token'

status=$(curl -sS -o "$RUN_DIR/client-products.json" -w '%{http_code}' \
  -H "Authorization: Bearer $CLIENT_TOKEN" 'http://127.0.0.1:8080/api/catalog/products')
expect_status 200 "$status" 'Cliente consulta catálogo'

status=$(curl -sS -o "$RUN_DIR/client-catalog-write.json" -w '%{http_code}' \
  -H "Authorization: Bearer $CLIENT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"CLIENT-DENIED","name":"Denegado","price":1000,"stock":1,"active":true}' \
  'http://127.0.0.1:8080/api/catalog/products')
expect_status 403 "$status" 'Cliente administra catálogo'

sku="SMOKE-$(date +%s)"
status=$(curl -sS -o "$RUN_DIR/product-create.json" -w '%{http_code}' \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sku\":\"$sku\",\"name\":\"Producto smoke\",\"description\":\"Prueba E2E\",\"price\":5000,\"stock\":3,\"active\":true}" \
  'http://127.0.0.1:8080/api/catalog/products')
expect_status 201 "$status" 'Operador crea producto'
product_id=$(json_value "$RUN_DIR/product-create.json" 'value["id"]')

status=$(curl -sS -o "$RUN_DIR/order-create.json" -w '%{http_code}' \
  -H "Authorization: Bearer $CLIENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"$product_id\",\"quantity\":1}],\"notes\":\"Pedido smoke\"}" \
  'http://127.0.0.1:8080/api/orders')
expect_status 201 "$status" 'Cliente crea pedido'
order_id=$(json_value "$RUN_DIR/order-create.json" 'value["id"]')

status=$(curl -sS -o "$RUN_DIR/other-client-get.json" -w '%{http_code}' \
  -H "Authorization: Bearer $OTHER_CLIENT_TOKEN" \
  "http://127.0.0.1:8080/api/orders/$order_id")
expect_status 403 "$status" 'Cliente consulta pedido ajeno'

status=$(curl -sS -o "$RUN_DIR/client-status.json" -w '%{http_code}' -X PATCH \
  -H "Authorization: Bearer $CLIENT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"ACEPTADO"}' \
  "http://127.0.0.1:8080/api/orders/$order_id/status")
expect_status 403 "$status" 'Cliente ejecuta transición operacional'

status=$(curl -sS -o "$RUN_DIR/invalid-skip.json" -w '%{http_code}' -X PATCH \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"DESPACHADO"}' \
  "http://127.0.0.1:8080/api/orders/$order_id/status")
expect_status 409 "$status" 'Transición que salta ACEPTADO'

curl -fsS -H "Authorization: Bearer $OPERATOR_TOKEN" \
  "http://127.0.0.1:8080/api/catalog/products/$product_id" >"$RUN_DIR/stock-before.json"
stock_before=$(json_value "$RUN_DIR/stock-before.json" 'value["stock"]')
[[ "$stock_before" == '3' ]] || fail "Stock inicial esperado 3, obtenido $stock_before"

status=$(curl -sS -o "$RUN_DIR/accept.json" -w '%{http_code}' -X PATCH \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"ACEPTADO"}' \
  "http://127.0.0.1:8080/api/orders/$order_id/status")
expect_status 200 "$status" 'Aceptar pedido'

curl -fsS -H "Authorization: Bearer $OPERATOR_TOKEN" \
  "http://127.0.0.1:8080/api/catalog/products/$product_id" >"$RUN_DIR/stock-after-accept.json"
stock_after=$(json_value "$RUN_DIR/stock-after-accept.json" 'value["stock"]')
[[ "$stock_after" == '2' ]] || fail "Stock después de aceptar esperado 2, obtenido $stock_after"

for transition in EN_PREPARACION DESPACHADO ENTREGADO; do
  lower=$(printf '%s' "$transition" | tr '[:upper:]' '[:lower:]')
  status=$(curl -sS -o "$RUN_DIR/$lower.json" -w '%{http_code}' -X PATCH \
    -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"status\":\"$transition\"}" \
    "http://127.0.0.1:8080/api/orders/$order_id/status")
  expect_status 200 "$status" "Transición $transition"
done

status=$(curl -sS -o "$RUN_DIR/second-order.json" -w '%{http_code}' \
  -H "Authorization: Bearer $CLIENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"$product_id\",\"quantity\":1}]}" \
  'http://127.0.0.1:8080/api/orders')
expect_status 201 "$status" 'Crear segundo pedido'
second_order_id=$(json_value "$RUN_DIR/second-order.json" 'value["id"]')

status=$(curl -sS -o "$RUN_DIR/second-accept.json" -w '%{http_code}' -X PATCH \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"ACEPTADO"}' \
  "http://127.0.0.1:8080/api/orders/$second_order_id/status")
expect_status 200 "$status" 'Aceptar segundo pedido'

status=$(curl -sS -o "$RUN_DIR/second-cancel.json" -w '%{http_code}' -X PATCH \
  -H "Authorization: Bearer $OPERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"CANCELADO"}' \
  "http://127.0.0.1:8080/api/orders/$second_order_id/status")
expect_status 200 "$status" 'Cancelar segundo pedido aceptado'

curl -fsS -H "Authorization: Bearer $OPERATOR_TOKEN" \
  "http://127.0.0.1:8080/api/catalog/products/$product_id" >"$RUN_DIR/stock-restored.json"
stock_restored=$(json_value "$RUN_DIR/stock-restored.json" 'value["stock"]')
[[ "$stock_restored" == '2' ]] || fail "Stock tras cancelación esperado 2, obtenido $stock_restored"

status=$(curl -sS -o "$RUN_DIR/third-order.json" -w '%{http_code}' \
  -H "Authorization: Bearer $CLIENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"$product_id\",\"quantity\":0}]}" \
  'http://127.0.0.1:8080/api/orders')
expect_status 400 "$status" 'Cantidad de pedido inválida'

printf 'Smoke E2E aprobado: auth=401/403/200, namespaces AWS/Entra aislados, ownership=403, skip=409, stock=3->2->2, ciclo completo=OK\n'
printf 'Logs conservados en %s\n' "$RUN_DIR"
