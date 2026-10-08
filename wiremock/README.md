# WireMock — Identity Provider simulado para desarrollo local

Este directorio contiene la configuración de WireMock para simular un Identity Provider (IdP) real en el entorno de desarrollo local.

## ¿Por qué WireMock?

El perfil `local` original usa HMAC para firmar tokens JWT de desarrollo. Esto presenta riesgos:

- **Riesgo de seguridad:** Si alguien activa el perfil `local` en producción, podría generar tokens válidos sin autenticación real.
- **Baja similitud con producción:** El flujo de autenticación real usa OIDC/OAuth2, no HMAC.

WireMock resuelve estos problemas:

- **Similitud con producción:** Simula los endpoints reales de un IdP (token, JWKS, authorize).
- **Sin riesgo de seguridad:** No hay forma de generar tokens válidos fuera del entorno de desarrollo.
- **Configuración mínima:** Solo requiere Docker y los archivos de mapeo.

## Uso

### 1. Iniciar WireMock

```bash
cd pedidos360-infra
docker compose up wiremock
```

### 2. Configurar los microservicios

En cada microservicio (BFF, Catalog, Orders), usa estas variables de entorno:

```bash
# BFF
ENTRA_ISSUER=http://localhost:8089
ENTRA_JWK_SET_URI=http://localhost:8089/discovery/v2.0/keys

# Catalog y Orders
ENTRA_ISSUER=http://localhost:8089
ENTRA_JWK_SET_URI=http://localhost:8089/discovery/v2.0/keys
```

### 3. Generar tokens de prueba

```bash
# Token de acceso
curl -X POST http://localhost:8089/oauth2/v2.0/token

# JWKS (claves públicas)
curl http://localhost:8089/discovery/v2.0/keys
```

## Archivos de mapeo

| Archivo | Descripción |
|---|---|
| `mappings/token.json` | Simula el endpoint de token de Entra ID |
| `mappings/jwks.json` | Simula el endpoint de claves públicas (JWKS) |
| `mappings/authorize.json` | Simula el endpoint de autorización OAuth2 |

## Personalización

### Agregar roles personalizados

Edita `mappings/token.json` para incluir claims específicos:

```json
{
  "jsonBody": {
    "access_token": "mock-access-token-{{randomValue type='UUID'}}",
    "roles": ["Admin", "Operador"],
    "oid": "mock-user-123"
  }
}
```

### Simular errores

Crea un nuevo mapeo para simular errores:

```json
{
  "request": {
    "method": "POST",
    "url": "/oauth2/v2.0/token",
    "bodyPatterns": [
      {
        "contains": "invalid_grant"
      }
    ]
  },
  "response": {
    "status": 400,
    "jsonBody": {
      "error": "invalid_grant",
      "error_description": "The provided grant has been revoked."
    }
  }
}
```

## Alternativa: Keycloak

Si necesitas un IdP completo con interfaz gráfica, considera usar Keycloak:

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8081:8080"
    volumes:
      - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
```

Ver `../docs/LOCAL_DEV_ALTERNATIVES.md` para más detalles.

## Troubleshooting

### WireMock no inicia

```bash
# Ver logs
docker compose logs wiremock

# Verificar que el puerto está libre
lsof -i :8089
```

### Los microservicios no pueden conectar

```bash
# Verificar que WireMock está en la misma red
docker compose exec wiremock ping oracle

# Verificar que los mapeos están cargados
curl http://localhost:8089/__admin/mappings
```

### Tokens no válidos

```bash
# Verificar que el endpoint de token responde
curl -X POST http://localhost:8089/oauth2/v2.0/token

# Verificar que el endpoint de JWKS responde
curl http://localhost:8089/discovery/v2.0/keys
```

## Referencia

- [WireMock Documentation](https://wiremock.org/docs/)
- [WireMock Docker](https://hub.docker.com/r/wiremock/wiremock)
- [OIDC Simulado con WireMock](https://wiremock.org/docs/oauth/)
