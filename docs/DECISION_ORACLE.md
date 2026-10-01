# Decisión: Base de datos RDS Oracle en AWS

## Estado

**Pendiente** — Se requiere una decisión de licencia antes de crear el RDS Oracle en AWS.

## Contexto

El proyecto Pedidos360 utiliza Oracle como base de datos de producción para los microservicios de Catalog y Orders. El perfil `cloud` está configurado para usar Oracle con Flyway para migraciones.

## Opciones

### Opción 1: RDS Oracle (recomendado si ya tienes licencia)

**Ventajas:**
- Compatibilidad total con el código actual (OracleDialect, Flyway Oracle)
- Sin cambios en el código de los microservicios
- Soporte oficial de AWS

**Desventajas:**
- Costo de licencia Oracle (puede ser significativo)
- Requiere configuración de licencia (License Included o BYOL)

**Costo estimado:**
- Instancia `db.t3.medium`: ~$0.078/hora (~$56/mes)
- Licencia Oracle: variable (consultar con AWS o Oracle)
- Almacenamiento: ~$0.115/GB/mes

### Opción 2: Migrar a PostgreSQL

**Ventajas:**
- Sin costo de licencia
- RDS PostgreSQL es más económico
- Amplia compatibilidad con Spring Boot y Flyway

**Desventajas:**
- Requiere migración de código (OracleDialect → PostgreSQLDialect)
- Posibles diferencias en funciones SQL (secuencias, triggers, etc.)
- Requiere pruebas exhaustivas de regresión

**Costo estimado:**
- Instancia `db.t3.medium`: ~$0.065/hora (~$47/mes)
- Sin licencia adicional

### Opción 3: RDS Oracle con licencia BYOL

**Ventajas:**
- Puede ser más económico si ya tienes licencias Oracle
- Flexibilidad en la gestión de licencias

**Desventajas:**
- Requiere gestión de licencias propia
- Complejidad administrativa

## Recomendación

1. **Si ya tienes licencia Oracle:** Usar RDS Oracle con BYOL
2. **Si no tienes licencia y el proyecto es pequeño/mediano:** Evaluar migración a PostgreSQL
3. **Si el proyecto es enterprise y Oracle es requisito:** Usar RDS Oracle con License Included

## Pasos para decidir

1. [ ] Consultar con el equipo de finanzas sobre el presupuesto de licencias
2. [ ] Verificar si la organización ya tiene licencias Oracle (BYOL)
3. [ ] Evaluar el esfuerzo de migración a PostgreSQL (si aplica)
4. [ ] Tomar decisión y documentar en este archivo
5. [ ] Actualizar el README de infra con la decisión

## Decisión final

**Fecha:** 2026-09-30
**Decisión:** Mantener Oracle para producción, H2 para desarrollo local
**Responsable:** Emilio Araya
**Justificación:** El proyecto ya está completamente configurado para Oracle con Flyway. La migración a PostgreSQL requeriría cambios significativos en el código y pruebas de regresión. Para desarrollo local, H2 es suficiente y no requiere licencia. Para producción, se evaluará el costo de licencia Oracle vs. el esfuerzo de migración a PostgreSQL en función del presupuesto disponible.

## Nota sobre el perfil local

El perfil `local` usa H2 en memoria, por lo que **no requiere licencia Oracle** para desarrollo. La decisión solo afecta al despliegue en AWS.

---

## Nota sobre el perfil local

El perfil `local` usa H2 en memoria, por lo que **no requiere licencia Oracle** para desarrollo. La decisión solo afecta al despliegue en AWS.
