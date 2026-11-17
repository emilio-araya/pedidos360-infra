# Kafka admin API for messaging-admin

Objective: close EP5's missing Kafka administrator — create/list/describe/delete topics, alter topic configs, increase partitions, and inspect/reset consumer groups — without touching the existing RabbitMQ admin.

Branch: `feat/kafka-admin` in `pedidos360-infra` · Delivery: one PR · Runner: `mvn test` (JUnit 5, Mockito, `spring-kafka-test`).

## Specs

- **S1 — Topic administration.** `POST /api/admin/kafka/topics` (201 + `Location`), `GET /topics?includeInternal=`, `GET /topics/{name}`, `PATCH /topics/{name}/configs`, `POST /topics/{name}/partitions`, `DELETE /topics/{name}` (204).
- **S2 — Consumer groups.** `GET /consumer-groups` (state, members, total lag), `GET /consumer-groups/{groupId}` (coordinator, members, per-partition lag), `POST /consumer-groups/{groupId}/offsets/reset` with `EARLIEST | LATEST | OFFSET | TIMESTAMP`. Reset only when the group state is `EMPTY`; otherwise 409.
- **S3 — Protected names.** Prefixes `pedidos360.`, `orders.`, `stock.`, `__` belong to the running system: readable, never created/modified/deleted through this API (409).
- **S4 — Validation.** Topic name `^[A-Za-z0-9._-]{1,249}$`; partitions 1..1000 (configurable); replication factor 1..broker count; config allowlist `retention.ms`, `retention.bytes`, `cleanup.policy`, `segment.ms`, `max.message.bytes`, `min.insync.replicas`; `min.insync.replicas <= replicationFactor`; cannot decrease partitions.
- **S5 — Errors.** RFC 9457 `ProblemDetail`: 400 invalid input, 404 unknown topic/group, 409 protected/exists/group not empty, 503 Kafka unreachable.
- **S6 — Infrastructure.** `spring-kafka` dependency; `AdminClient` bean from `spring.kafka.*`; `messaging-admin` receives `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` and waits for `kafka-init`; `kafka-init` sets `retention.ms`/`cleanup.policy` on both topics.
- **S7 — Documentation.** `docs/KAFKA_ADMIN.md` with endpoint contract and examples; README messaging section updated.
- **S8 — Tests.** Service unit tests with a mocked gateway, controller slice tests, and one embedded-Kafka adapter test. Existing RabbitMQ admin tests stay green.
- **S9 — No RabbitMQ regression.** Rabbit admin endpoints, messages and protected-name behavior unchanged.

## Tasks

| ID | Specs | Route | Status | Evidence |
|----|-------|-------|--------|----------|
| T1 | S1-S5 | inline | done | `b5cd807` — gateway, service, domain types, 18 service tests |
| T2 | S1, S2, S5 | inline | done | `b5cd807` — DTOs, controller, error handler, 10 slice tests |
| T3 | S6, S7 | inline | done | `70e3cd6` — pom, yml, compose, kafka-init, `docs/KAFKA_ADMIN.md`, READMEs |
| T4 | S1-S9 | inline | done | `mvn test` 61/61 green (Rabbit 27 + Kafka 34), `docker compose --env-file .env.example config` OK |

## Log

- **L1 (2026-11-13, user, verbatim):** "implementarlos"
- **L2:** scope confirmed by the approved design in chat: extend `messaging-admin`, layered like the Rabbit admin, single `AdminClient` adapter, reserved prefixes, allowlisted topic configs, consumer-group offsets reset only on empty groups.
- **L3 (2026-11-14):** test compile fixes: `TopicPartitionInfo` lives in `org.apache.kafka.common`; `deleteTopic` is void so the controller test needs `doThrow(...).when(...)`; a leading dash is a legal Kafka topic name, so it left the invalid-name list.
- **L4 (2026-11-14):** `@EmbeddedKafka` autowiring needs a Spring context; the adapter test starts `EmbeddedKafkaKraftBroker` directly. Kafka deletes topics asynchronously, so the delete case waits with a bounded 15 s deadline instead of asserting immediately.
- **L5 (2026-11-14):** feature complete on `feat/kafka-admin`; not pushed, no PR yet (user decision).
- **L6 (2026-11-15):** branch pushed and PR opened: https://github.com/emilio-araya/pedidos360-infra/pull/10 (base `main`).
- **L7 (2026-11-16, user, verbatim):** "1 arreglalo el 2 dejalo asi tal cual yo ya able con el profe y me dejo dejarlo haci"
- **L8 (2026-11-16):** gap 1 closed in `abd82fd`: `docker-compose.kafka-zk.yml` (ZooKeeper + broker on Kafka 3.9, Confluent images). The official `apache/kafka` image only boots in KRaft and fails with "legacy cluster", so Confluent images are required. Verified: `kafka-init` exits 0 and ZooKeeper lists both topics under `/brokers/topics`. Gap 2 (Angular frontend) left as is by user decision, already agreed with the teacher.
- **L9 (2026-11-16):** `abd82fd` committed but not pushed. First diagnosis ("SSH key not registered") was wrong: `ssh -v` shows `Server accepts key` and then `Permission denied`, because the private key is passphrase-protected and no agent held it (BatchMode cannot prompt). The chat token was also revoked (401).
- **L10 (2026-11-17):** resolved. User ran `ssh-agent` + `ssh-add`; push with `SSH_AUTH_SOCK` succeeded (`70e3cd6..abd82fd`). PR #10 now carries 3 commits (`b5cd807`, `70e3cd6`, `abd82fd`). Feature closed; the only remaining rubric gap is the Angular frontend, left as is by user agreement with the teacher.
- **L11 (2026-11-17, user, verbatim):** "si margea os pr"
- **L12 (2026-11-17):** PR #10 merged into `main` with a merge commit (`c33502e`, "Merge pull request #10 from emilio-araya/feat/kafka-admin"); `main` is now `c33502e` and the remote PR reads `merged=true`. Bundle `messaging-admin` and `docker-compose.kafka-zk.yml` are on `main`. Working tree clean.
