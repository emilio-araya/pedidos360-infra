# Alternativas al perfil local con HMAC

## Contexto

El perfil `local` de los microservicios (BFF, Catalog, Orders) utiliza HMAC para firmar tokens JWT de desarrollo. Esto permite probar los servicios sin necesidad de un Identity Provider real.

**Riesgo:** Si alguien activa accidentalmente el perfil `local` en producción, podría generar tokens válidos sin autenticación real.

## Alternativa recomendada: Mock IDP con WireMock

### ¿Qué es WireMock?

WireMock es un servidor HTTP simulado que puede responder con tokens JWT válidos. Permite simular el comportamiento de un Identity Provider real sin necesidad de uno.

### Implementación

#### 1. Agregar WireMock al docker-compose

```yaml
# docker-compose.yml
services:
  wiremock:
    image: wiremock/wiremock:3.3.1
    ports:
      - "8089:8080"
    volumes:
      - ./wiremock/mappings:/home/wiremock/mappings
      - ./wiremock/__files:/home/wiremock/__files
    command: ["--global-response-templating"]
```

#### 2. Crear el mapeo para tokens

```json
// wiremock/mappings/token.json
{
  "request": {
    "method": "POST",
    "url": "/oauth2/v2.0/token"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "access_token": "{{randomValue type='UUID'}}",
      "token_type": "Bearer",
      "expires_in": 3600
    }
  }
}
```

#### 3. Crear el mapeo para JWKS

```json
// wiremock/mappings/jwks.json
{
  "request": {
    "method": "GET",
    "url": "/discovery/v2.0/keys"
  },
  "response": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "keys": [
        {
          "kty": "RSA",
          "kid": "mock-key-1",
          "use": "sig",
          "n": "mock-modulus",
          "e": "AQAB"
        }
      ]
    }
  }
}
```

#### 4. Configurar los microservicios para usar WireMock

```yaml
# application-local.yml
entra:
  issuer: http://wiremock:8080
  jwk-set-uri: http://wiremock:8080/discovery/v2.0/keys
```

### Ventajas de WireMock

| Aspecto | HMAC | WireMock |
|---|---|---|
| Similitud con producción | Baja | Alta |
| Complejidad de configuración | Baja | Media |
| Riesgo de uso en producción | Alto | Bajo |
| Realismo de las pruebas | Bajo | Alto |

## Alternativa 2: Keycloak en Docker

### ¿Qué es Keycloak?

Keycloak es un Identity Provider open source que soporta OAuth2/OIDC. Puede ejecutarse en Docker y simular un IdP real.

### Implementación

```yaml
# docker-compose.yml
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

### Configuración

1. Crear un realm `pedidos360`
2. Crear un cliente `pedidos360-api`
3. Configurar los roles `Admin`, `Operador`, `Cliente`
4. Crear usuarios de prueba

### Ventajas

- **Realismo total:** Es un IdP real
- **Flexibilidad:** Permite probar flujos completos de autenticación
- **Sin código:** No requiere modificar los microservicios

### Desventajas

- **Más recursos:** Consume más memoria y CPU
- **Configuración inicial:** Requiere crear realm, clientes y usuarios

## Alternativa 3: Mantener HMAC con salvaguardias

Si decides mantener el perfil HMAC, implementa estas salvaguardias:

### 1. Verificación estricta de perfil

```java
@Configuration
@Profile("!local")  // Solo se activa fuera de local
public class ProductionSecurityConfig {
    // Configuración con JWT real
}
```

### 2. Variable de entorno obligatoria

```java
@PostConstruct
public void validateProfile() {
    if ("local".equals(environment.getProperty("spring.profiles.active"))) {
        log.warn("⚠️  Perfil local activo - HMAC habilitado solo para desarrollo");
    }
}
```

### 3. Health check que alerta

```java
@Component
public class SecurityHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        if (isHmacEnabled() && isProduction()) {
            return Health.down()
                .withDetail("error", "HMAC no debe estar activo en producción")
                .build();
        }
        return Health.up().build();
    }
}
```

## Recomendación

| Escenario | Recomendación |
|---|---|
| Desarrollo individual rápido | Mantener HMAC con salvaguardas |
| Equipo de desarrollo | WireMock |
| CI/CD con integración real | Keycloak |
| Pre-producción | Keycloak o IdP real |

## Conclusión

La alternativa más equilibrada es **WireMock**: proporciona un nivel de realismo alto sin la complejidad de Keycloak, y elimina el riesgo de que HMAC se use accidentalmente en producción.
