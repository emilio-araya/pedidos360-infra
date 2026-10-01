# Plan de desbloqueo: ECS Fargate y Secrets Manager en AWS

## Estado actual

| Componente | Estado | Motivo del bloqueo |
|---|---|---|
| ECS Fargate | Bloqueado | El rol del laboratorio no tiene permisos y la organización aplica una SCP que lo impide |
| Secrets Manager | Bloqueado | Mismo bloqueo de permisos |
| CloudFront + S3 | Bloqueado | Depende de que la API esté viva primero |
| RDS Oracle | Pendiente | Requiere decisión de licencia |

## ¿Qué es una SCP?

Una **Service Control Policy (SCP)** es una política de control de servicios de AWS Organizations que establece permisos máximos para las cuentas miembros. Las SCPs anulan cualquier política IAM, incluso si el rol tiene permisos completos.

## Plan de acción

### Fase 1: Identificar la SCP que bloquea

1. **Revisar las SCPs activas en la organización:**
   ```bash
   aws organizations list-policies --filter SERVICE_CONTROL_POLICY
   aws organizations list-policies-for-target --target-id <account-id> --filter SERVICE_CONTROL_POLICY
   ```

2. **Identificar qué acciones están denegadas:**
   ```bash
   aws organizations describe-policy --policy-id <policy-id>
   ```

3. **Buscar referencias a:**
   - `ecs:*`
   - `secretsmanager:*`
   - `fargate:*`

### Fase 2: Solicitar excepción o modificación

**Opción A: Solicitar una excepción en la SCP**

1. Contactar al equipo de administración de AWS Organizations
2. Proporcionar justificación de negocio:
   - Proyecto Pedidos360 requiere ECS Fargate para microservicios
   - Secrets Manager es necesario para gestionar credenciales de base de datos
   - Ambos servicios son estándar de la industria y no representan riesgo adicional
3. Solicitar una excepción para la cuenta de laboratorio o un OU específico

**Opción B: Crear una cuenta de desarrollo separada**

1. Crear una nueva cuenta AWS dentro de la organización
2. Aplicar una SCP menos restrictiva a esa cuenta
3. Usar esa cuenta para desarrollo y pruebas

**Opción C: Usar servicios alternativos temporalmente**

| Servicio bloqueado | Alternativa temporal | Limitaciones |
|---|---|---|
| ECS Fargate | EC2 con Docker | Más gestión operativa |
| Secrets Manager | Variables de entorno en EC2 | Menos seguro, no rotación automática |

### Fase 3: Implementar una vez desbloqueado

**Para ECS Fargate:**

1. **Crear el cluster:**
   ```bash
   aws ecs create-cluster --cluster-name pedidos360-cluster
   ```

2. **Definir las task definitions** para BFF, Catalog y Orders

3. **Configurar el servicio con ALB:**
   - Health checks
   - Auto-scaling policies
   - Service discovery

**Para Secrets Manager:**

1. **Crear los secretos necesarios:**
   ```bash
   aws secretsmanager create-secret \
     --name pedidos360/oracle/username \
     --secret-string '{"username":"catalog_user"}'
   
   aws secretsmanager create-secret \
     --name pedidos360/oracle/password \
     --secret-string '{"password":"..."}'
   ```

2. **Configurar la rotación automática** (opcional pero recomendado)

3. **Actualizar las task definitions** para usar los secretos

### Fase 4: Verificación

1. **Desplegar un servicio de prueba:**
   ```bash
   aws ecs create-service \
     --cluster pedidos360-cluster \
     --service-name test-service \
     --task-definition pedidos360-bff:1 \
     --desired-count 1
   ```

2. **Verificar que el servicio se inicia correctamente**

3. **Probar el acceso a secretos desde el contenedor**

4. **Ejecutar smoke tests contra el ALB**

## Resumen de responsables

| Paso | Responsable | Estado |
|---|---|---|
| Identificar SCP | Equipo AWS/Admin | Pendiente |
| Solicitar excepción | Equipo AWS/Admin | Pendiente |
| Crear cluster ECS | Emilio | Pendiente |
| Configurar Secrets Manager | Emilio | Pendiente |
| Desplegar servicios | Emilio | Pendiente |
| Verificación final | Emilio | Pendiente |

## Contacto

- **Administrador de AWS Organizations:** [nombre/email]
- **Equipo de seguridad:** [nombre/email]
- **Responsable del proyecto:** Emilio Araya

---

## Nota importante

Este plan asume que tienes acceso a la consola de AWS y permisos para ejecutar los comandos CLI. Si no tienes estos permisos, contacta al administrador de la organización para que ejecute los pasos de la Fase 1.
