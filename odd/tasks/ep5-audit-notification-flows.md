# EP5 — Flujos de auditoría y notificaciones (Kafka #2 y #8)

**Objetivo:** cerrar los dos indicadores parciales del EP5 con comportamiento real
(nada artificial): un segundo Consumer Group propio en código (`pedidos360-audit`)
que audita el mismo tópico de dominio, y un segundo tópico de negocio
(`pedidos360.notification-events`) con productor y consumidor reales: orders publica
la intención de notificación en cada cambio de estado de un pedido de invitado y un
consumidor propio envía el correo (Mailpit en local, SES en AWS).

**Rama:** main de cada repo (trabajo sobre el árbol ya publicado).
**Fecha:** 2026-11-18

## Specs

- **S1** — Auditoría propia en código: `pedidos360-catalog` publicará un consumidor
  con `groupId = pedidos360-audit` sobre `pedidos360.order-events`, que persiste cada
  evento en una tabla `audited_order_events` con `event_id` único (idempotente por
  construcción), sin interferir con `catalog-sales-projection` ni depender de offsets
  del otro grupo. Un mensaje inválido sigue el mismo camino que el flujo existente:
  `InvalidOrderEventException` → sin reintentos → DLT.
- **S2** — Segundo flujo de negocio: tópico `pedidos360.notification-events` creado
  por los tres Compose (RF=1 en principal y ZK, RF=3 en el cluster de 3 brokers),
  reservado por el admin (`pedidos360.` prefix) e incluido en la evidencia.
- **S3** — Orders publica la intención de notificación vía la outbox transaccional
  existente (columna `target_topic`, valor por defecto `pedidos360.order-events`):
  se garantiza at-least-once sin duplicar lógica de publicación.
- **S4** — El evento `NOTIFICATION_REQUESTED` se encola **sólo** para pedidos de
  invitado (tienen `customer_email`) en cada cambio de estado
  (ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO), en la misma
  transacción que el cambio de estado. Payload: `eventId`, `orderId`, `orderNumber`,
  `status`, `previousStatus`, `email`, `occurredAt`.
- **S5** — Consumidor `pedidos360-notifications` en orders: lee
  `pedidos360.notification-events`, valida el payload, renderiza y envía el correo
  (con `JavaMailSender` si `MAIL_HOST` está configurado; en su defecto, log). El
  contenido: número de pedido, estado anterior → nuevo y el enlace genérico a
  `${FRONTEND_PUBLIC_URL}/seguimiento` (el token crudo del invitado no se persiste,
  sólo su hash). Un correo por transición; reenvíos posibles por at-least-once
  (documentado).
- **S6** — Los 3 Compose y la evidencia registran `NOTIFICATION` como segundo
  tópico de negocio; la documentación EP5 se actualiza: #2 → Cumple, #8 → Cumple
  (código), nota explícita sobre los dos clústeres (RF=1 flujo, RF=3 resiliencia) y
  prueba de caída de broker con salidas registradas.

## Tasks

- [x] T1 · S1 · catalog · inline · AuditedOrderEvent + repo + service + AuditTrailListener + test · 92/92 tests
- [x] T2 · S2,S3,S4 · orders · inline · V6 (target_topic), entity/publisher routing, enqueue on status change (guests), tests · 128/128
- [x] T3 · S5 · orders · inline · Notification listener + renderer + notifier + tests · 128/128
- [x] T4 · S2,S6 · infra · inline · kafka-init en 3 composes (topic + RF=3), evidencia EP5 + broker-death test en vivo
- [x] T5 · cierre · zip de entrega + commits en los 3 repos + push + mirror Engram

## Log

- **L1** (verbatim, 2026-11-18): usuario pega el informe del evaluador: "1. Segundo
  Consumer Group en código… mi recomendación es implementar un consumidor de auditoría
  real… 2. Segundo flujo de eventos de negocio… 3. Prueba de caída de broker…
  4. Catalog con Flyway… 5. Login Entra ID."
- **L2** Decisiones (ask): (a) alcance completo A+3+B notifications; (b) correo a
  **todos los estados** del invitado; (c) el flujo y el cluster RF=3 son ejecuciones
  DISTINTAS y deben presentarse así; (d) Flyway en catalog queda diferido (decisión
  previa del usuario); (e) evidencia con salidas reales, sin RFs supuestos.
- **L3** Detalle técnico clave: el agrupamiento `pedidos360-audit` (código) sustituye
  al grupo de consola `ep5-auditoria` usado hasta ahora sólo para demostrar
  offsets independientes; el rol real es persistir la auditoría.
- **L4** Detección: `pedidos360.` está reservado en messaging-admin
  (`application.yml` del admin, `reserved-prefixes`), así que el nuevo tópico queda
  protegido sin cambios en el admin.
- **L5** (2026-11-18, evidencia real, viva): pedido invitado PED-1F1F001A → PATCH
  ACEPTADO → NOTIFICATION_REQUESTED en `pedidos360.notification-events` (offset 1) →
  consumidor `pedidos360-notifications` envía correo (Mailpit: "Tu pedido
  PED-1F1F001A cambió a ACEPTADO"). `pedidos360-audit` consumió los mismos eventos
  con offsets 3/5/1 lag 0. Proyección de ventas: unitsSold 1. Suite catalog 92/92,
  orders 128/128. Cluster RF=3 con prueba de caída de broker (ISR 1,2,3 → 1,2 →
  1,2,3) y `__consumer_offsets` RF=3/min.insync 2. Pendiente en T5: zip + commits +
  push (no pushear sin confirmación) + mirror. El cluster corre en ejecución aparte:
  el flujo E2E documentado quedó sobre el stack RF=1 de un broker, y eso debe
  decirlo la presentación (no son la misma corrida).
- **L6** Decisión anotada: el correo sólo va a invitados (únicos con `customer_email`);
  creado no encola (el voucher ya avisa); el cuerpo remite a `/seguimiento` genérico
  (el token crudo nunca se persiste); fallos de SMTP: 2 reintentos cada 2 s y descarte
  con error en log, sin DLT propia (no bloquear la partición por un correo).

## Addendum 2026-11-19 — Etapa 1 (resiliencia de notificaciones, informe de arquitectura)

- **S1'** — DLT propia `pedidos360.notification-events.DLT`: DeadLetterPublishingRecoverer
  con 2 reintentos cada 2 s (FixedBackOff), partición -1 (clave orderId). Payloads
  inválidos también terminan en DLT (la excepción sube desde el listener).
- **S2'** — Registro `notification_delivery` (V7, clave `event_id`): SENT nunca se
  reenvía; PROCESSING con <10 min se omite (huérfano reintenta); PENDING/FAILED
  reintentables; carrera por el PK gana la primera. SMTP no da exactly-once: la
  ventana SMTP-aceptado→SENT queda documentada.
- **S3'** — Migración `V7__create_notification_delivery.sql` en H2 (tests y demo) y
  Oracle (productivo, pendiente de deploy autorizado).
- **S4'** — Los tres Compose crean la DLT (RF=1/RF=3 + min.insync.replicas=2 en cluster,
  retention 14 días).
- **Evidencia viva (2026-11-19, local)**: payload inválido → DLT con headers
  `kafka_dlt-original-*`; flujo feliz PED-EA584DA1 → ACEPTADO → mail único en Mailpit;
  offsets grupo 0/2/0 lag 0; orders **134/134** tests.
- **L7** Alcance: del informe del evaluador (etapa 1) se implementó sólo 1.1+1.2, por
  aprobación del usuario. 2.1 (recuperación/antigüedad del outbox) y 4.1
  (messaging-admin en la API, hoy protegido sólo por el bind a 127.0.0.1) quedan
  post-entrega. Flyway de catalog diferido como decisión previa del usuario.
