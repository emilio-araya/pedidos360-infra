# Kafka domain events for Pedidos360
Objective: add Apache Kafka alongside RabbitMQ — RabbitMQ keeps commands (stock reservation), Kafka carries order domain events consumed by catalog as a sales-per-product projection.
Branch: `feat/kafka-domain-events` in pedidos360-orders, pedidos360-catalog, pedidos360-infra · Delivery: one PR per repo (ask-on-risk) · Test runner: `mvn test` (JUnit 5); infra: `docker compose config` + local smoke.

## Specs

- **S1 — Coexistence (user decision, verbatim):** "mi profesor pidio k integraramos kafka" → chosen scope: "Kafka junto a RabbitMQ". The existing RabbitMQ flows (exchange `pedidos360.events`, queues `stock.reserve`, `stock.release`, `orders.stock-result`, retry/DLQ, `outbox_event` table and `OutboxPublisher`) MUST keep their current behavior unchanged. All existing tests stay green.
- **S2 — Event contract (shared, fixed):** topic `pedidos360.order-events` (3 partitions), record key = `orderId` as string (guarantees per-order ordering), value = JSON:
  ```json
  {
    "eventId": "uuid",
    "eventType": "ORDER_CREATED | ORDER_UPDATED | ORDER_ACCEPTED | ORDER_IN_PREPARATION | ORDER_DISPATCHED | ORDER_DELIVERED | ORDER_CANCELLED",
    "schemaVersion": 1,
    "orderId": "uuid",
    "status": "CREADO | ACEPTADO | EN_PREPARACION | DESPACHADO | ENTREGADO | CANCELADO",
    "previousStatus": "same enum or null (null for ORDER_CREATED)",
    "items": [{ "productId": "uuid", "quantity": 1 }],
    "occurredAt": "ISO-8601 instant, UTC"
  }
  ```
  Dead-letter topic: `pedidos360.order-events.DLT`. Consumer group of catalog: `catalog-sales-projection`.
- **S3 — Producer in orders (transactional outbox for Kafka):** a NEW table `kafka_outbox_event` (new Flyway migration `V5__create_kafka_outbox_event.sql`, Oracle- and H2-compatible like V1–V4), written in the SAME transaction as every order state change: creation (ORDER_CREATED), update (ORDER_UPDATED), and every status transition (ACEPTADO, EN_PREPARACION, DESPACHADO, ENTREGADO, CANCELADO — including cancellation caused by stock rejection). A `KafkaOutboxPublisher` (`@Scheduled`, ordered by `created_at`, stops the batch on first failure to keep order, waits for the broker ack before marking published, producer `acks=all` + idempotence enabled) publishes pending rows. Toggle `app.kafka.outbox.publisher-enabled` (default `true`; disabled in tests). Bootstrap via `spring.kafka.bootstrap-servers` (default `localhost:9092`). The existing `outbox_event` / RabbitMQ publisher is NOT modified (S1).
- **S4 — Consumer in catalog (sales projection):** `@KafkaListener` on `pedidos360.order-events`, group `catalog-sales-projection`, maintains per-product counters `unitsSold`, `unitsDelivered`, `unitsCancelled`:
  - `ORDER_ACCEPTED` → `unitsSold += quantity` per item.
  - `ORDER_DELIVERED` → `unitsDelivered += quantity` per item.
  - `ORDER_CANCELLED` with `previousStatus` in {ACEPTADO, EN_PREPARACION, DESPACHADO} → `unitsCancelled += quantity`; cancelled from CREADO → no change.
  - Any other eventType → no counter change.
  - Idempotent: each `eventId` is applied at most once (processed-event record in the same DB transaction as the counter update); a redelivered event changes nothing.
  - Malformed JSON, unknown `eventType`, or `schemaVersion` ≠ 1 → sent to `pedidos360.order-events.DLT` without retry; transient failures retried with backoff, then DLT.
  - Tables created the same way catalog already creates its schema (JPA entities; catalog has no Flyway).
- **S5 — Sales API in catalog:** `GET /api/catalog/sales` (list of all products with stats) and `GET /api/catalog/sales/products/{productId}` → `{ "productId", "unitsSold", "unitsDelivered", "unitsCancelled", "netUnits" }` where `netUnits = unitsSold - unitsCancelled`. Unknown product stats → 404 in the existing problem format. Authorization: roles Admin or Operador only; Cliente → 403; no token → 401. Must NOT fall under the public `GET /api/catalog/products/**` matcher. Mirror under `/aws/api/catalog/sales...` only if the existing controllers mirror `/aws` paths.
- **S6 — Local infra:** pedidos360-infra `docker-compose.yml` gains a single-node Kafka in KRaft mode (pinned released image tag, no Zookeeper), a one-shot topic-init service creating `pedidos360.order-events` (3 partitions) and its `.DLT`, and a Kafka UI published on 127.0.0.1 only. orders and catalog receive `SPRING_KAFKA_BOOTSTRAP_SERVERS`. Kafka sits on the internal `backend` network like RabbitMQ. *Assumption:* single broker (not a cluster) is enough for local/academic use; documented as a known limitation.
- **S7 — Docs:** `pedidos360-infra/docs/MENSAJERIA_KAFKA.md` (why Kafka vs RabbitMQ, contract S2, guarantees, how to verify with Kafka UI / console consumer), README messaging sections of orders, catalog, infra updated; help/config keys documented.

## Tasks

| ID | Specs | Repo | Route | Status | Commit / evidence |
|----|-------|------|-------|--------|-------------------|
| T1 | S1, S2, S3, S7 | pedidos360-orders | delegated (writer reason: parallel units, disjoint repos) | done | `63ce4d3` — RED 22 focused (15 fail, 2 err) → GREEN; `mvn -q test` 118/0 fail (parent spot check confirmed); extra keys `app.kafka.outbox.send-timeout-ms`, `max.block.ms`; guest create/cancel also emit events |
| T2 | S1, S2, S4, S5, S7 | pedidos360-catalog | delegated (writer reason: parallel units, disjoint repos) | done | `8cca352` — RED 23/28 fail → GREEN 28/28; `mvn -q test` 87/0 fail (parent spot check confirmed); embedded-Kafka test proves topic+group+DLT; deviations: env alias `KAFKA_SALES_PROJECTION_ENABLED`, configurable retry, listener off via `autoStartup` |
| T3 | S2, S6, S7 | pedidos360-infra | delegated (writer reason: parallel units, disjoint repos) | done | `88a95b3` — compose config OK (`--env-file .env.example`), live smoke: 3 partitions + DLT, produce/consume OK, offset reset OK; images apache/kafka:4.3.2, kafbat/kafka-ui:v1.5.0 |
| T4 | S1–S7 | all | independent verifier (high risk: dependencies, security, persisted schema) + parent seam check | done | verifier: S1–S7 pass, 0 blockers; advisories noted. Correction batch `bb1ec4f` (catalog): S5 list now includes zero-sale products and zero stats instead of 404. Recheck: 89 tests green |

## Log

- **L1 (2026-11-05, user, verbatim):** "mi profesor pidio k integraramos kafka"
- **L2 (2026-11-05, user decision):** scope = "Kafka junto a RabbitMQ"; consumer = "Catalog: ventas por producto (Recommended)".
- **L3 (2026-11-05, evidence):** no Kafka mention in course guides/EP1 PDF or repos. Orders outbox `outbox_event` has a single `published_at` and `event_type` = RabbitMQ routing key → a separate Kafka outbox table avoids touching the RabbitMQ flow. Only accepted/cancelled/orphan-reservation emit events today. Catalog `GET /api/catalog/products/**` is public (SecurityConfiguration.java:76) → sales API placed under `/api/catalog/sales`. Env: Java 17.0.21, Maven 3.10.0, Docker 29.9.3 daemon OK. Forecast ≈ orders 400, catalog 500, infra 200 authored lines → one PR per repo.
- **L4:** branches `feat/kafka-domain-events` created from clean `main` in orders, catalog, infra. T1–T3 launched in parallel.
- **L5 (2026-11-05, user, verbatim):** "los pondre en pausa devo viajar y apagar el pc" → feature PAUSED. At pause time all three repos had a clean working tree on `feat/kafka-domain-events` (no files written by T1–T3; workers interrupted by shutdown). **Next step on resume:** verify branches still clean (`git status`), then relaunch T1, T2, T3 in parallel with the same prompts (spec by reference to this document), then T4.
- **L6 (2026-11-05, correction):** T3 finished before shutdown and was committed as `88a95b3` on infra `feat/kafka-domain-events`. Topic auto-creation is DISABLED on the broker → T1/T2 topic names must match S2 exactly. `.env.example` not updated (KAFKA_UI_PORT/KAFKA_CLUSTER_ID have defaults). T1 (orders) and T2 (catalog) were still running with uncommitted files when the user shut down. **Resume:** inspect `git status`/`git diff` in orders and catalog — if partial work exists, relaunch T1/T2 telling the writer to continue from the existing diff; then T4 (end-to-end with built images).
- **L7 (2026-11-05):** T1 `63ce4d3` and T2 `8cca352` finished and committed; all three repos clean on `feat/kafka-domain-events`. T4 NOT started (user paused). **Resume:** launch T4 independent verifier with this whole document, baselines = `main` of each repo, probes end-to-end (build jars/images, compose up, create order → transitions → GET /api/catalog/sales counters; duplicate/poison event → DLT; RabbitMQ stock flow unchanged). Known advisories: optimistic-lock contention across partitions may exhaust retries → DLT; catalog README pre-existing wrong note "cualquier autenticado" for public product GETs.
- **L8 (2026-11-07, T4 result):** verifier ran the full stack in an isolated scratch copy. S1 pass (118/87 candidate vs 99/59 baseline; RabbitMQ reserve/release verified live), S2 pass (5 events, same partition, key=orderId, `previousStatus:null` present), S3 pass (V5 on Oracle 23 + H2, publisher recovers from broker down, rejected transition emits nothing), S4 pass (counters, duplicate no-op, 3 poison records to DLT, consumer alive after), S5 pass (200/403/401/404/400, product GET still public), S6 pass (kafka not published, UI loopback, init idempotent), S7 pass. 0 blockers. Advisories: `/aws` non-Cognito token → 500 is pre-existing local quirk; `.env.example` `catalog_user` rejected by `ensure-oracle-users.sh` (pre-existing); `KAFKA_UI_PORT`/`KAFKA_CLUSTER_ID` not in `.env.example`; sales DLT re-partitions by key.
- **L9 (2026-11-07, correction, user asked to continue):** advisory 4 judged as an unmet S5 item ("list of all products with stats"), so it was fixed: RED (2 failing tests) → GREEN, `bb1ec4f` in catalog, 89 tests green, README updated. Remaining advisories accepted as pre-existing or documented.
- **L10 (2026-11-08, delivery, user verbatim):** "si as push si ya todo quedo funcionando" → branches pushed and PRs opened: infra #7 https://github.com/emilio-araya/pedidos360-infra/pull/7, orders #11 https://github.com/emilio-araya/pedidos360-orders/pull/11, catalog #8 https://github.com/emilio-araya/pedidos360-catalog/pull/8. Merge order: infra → orders → catalog. Push used the `gh` credential helper because the non-interactive shell has no SSH agent; `origin` remotes remain SSH. **Merge is the user's decision.**
