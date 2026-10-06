# attendance-gateway

A service that sits between time clocks and the systems that need attendance data.
Clocks send punches that are duplicated, late, and stamped in local time under the
clock's own code for each person. This service stores each punch exactly once, converts
it to UTC, resolves the employee, and sets aside what it cannot accept, with the reason.

**Status: milestone 1 of 4.** Ingestion, idempotency and quarantine are done and tested.
Publishing events to Kafka, daily summaries and load numbers are not built yet; see
[Roadmap](#roadmap).

## The problem

- A clock retries when it gets no answer, so the same punch arrives more than once.
- A clock that was offline sends hours of punches at once, sometimes while its earlier
  request is still in flight.
- Each clock reports wall-clock time in its own time zone.
- The clock knows a person by an enrollment code, not by the HR employee id.

The usual fix is a module inside the ERP that the clocks call directly. That ties device
traffic to the system the business runs on, and every other consumer of attendance data
repeats the cleanup.

## What it does today

| | |
|---|---|
| Device registry | Each clock has its own API key. Only a SHA-256 of the key is stored; the key is shown once. Keys can be rotated and devices disabled. |
| Ingestion | `POST /api/v1/devices/{deviceId}/punches` takes a batch and answers `202` with counts: accepted, duplicates, quarantined. |
| Idempotency | A punch is identified by device, clock code, device time and type. Sending a batch again stores nothing and reports duplicates. |
| Normalization | Device wall-clock time becomes UTC using the device's time zone. The clock code becomes an employee id through a per-site mapping. |
| Quarantine | A punch with no employee mapping, or dated in the future beyond a tolerance, is kept with its reason. After the mapping is fixed, `POST /api/v1/quarantine/reprocess` moves it through. |

## Design decisions

**The idempotency key is a database constraint, not a check in code.** A "look it up,
then insert" check passes for two copies of a batch that arrive together. A unique
constraint with `INSERT ... ON CONFLICT DO NOTHING` cannot be raced.

**Every transaction inserts its punches in the same order.** Two overlapping batches
that insert the same rows in different orders deadlock: each holds a row the other is
waiting for. Sorting before the insert makes them queue instead.
`ConcurrentIngestTest` sends eight shuffled copies of a 200-punch batch at once and
checks that exactly 200 rows exist; with the sort removed, PostgreSQL reports
`deadlock detected` and the test fails.

**A device is authenticated before its request body is read.** Authentication runs in
an interceptor, so a caller without a valid key gets `401` and no validation detail. An
unknown device and a wrong key get the same answer.

**Rejected punches are kept.** Dropping a punch loses someone's worked hours. Quarantine
keeps the original data and the reason, and a retry does not quarantine it twice.

**JDBC on the ingest path, JPA for the registry.** Ingestion is batch inserts that rely
on `ON CONFLICT`; the device registry is ordinary entity work.

## Running it

Requirements: JDK 25 and Docker.

```bash
docker compose up -d                         # PostgreSQL
ATTENDANCE_ADMIN_API_KEY=dev-admin-key ./mvnw spring-boot:run
```

Register a device, map an employee, send a punch:

```bash
curl -s -X POST localhost:8080/api/v1/devices \
  -H 'X-Admin-Key: dev-admin-key' -H 'Content-Type: application/json' \
  -d '{"name":"Front door","siteCode":"HQ","timeZone":"America/Santo_Domingo"}'
# -> {"device":{"id":"<DEVICE_ID>",...},"apiKey":"<DEVICE_KEY>"}

curl -s -X PUT localhost:8080/api/v1/sites/HQ/mappings/17 \
  -H 'X-Admin-Key: dev-admin-key' -H 'Content-Type: application/json' \
  -d '{"employeeId":"EMP-17"}'

curl -s -X POST localhost:8080/api/v1/devices/<DEVICE_ID>/punches \
  -H 'X-Device-Key: <DEVICE_KEY>' -H 'Content-Type: application/json' \
  -d '{"punches":[{"userCode":"17","punchedAt":"2026-09-01T07:58:00","type":"IN"}]}'
# -> {"received":1,"accepted":1,"duplicates":0,"quarantined":0}
# Send it again: {"received":1,"accepted":0,"duplicates":1,"quarantined":0}
```

API documentation is served at `/swagger-ui.html`.

If `ATTENDANCE_ADMIN_API_KEY` is not set, every administration request is rejected.

## Tests

```bash
./mvnw verify
```

The integration tests go through real HTTP against a real PostgreSQL started by
Testcontainers, because the behavior under test is `ON CONFLICT` and row locking. They
are skipped when Docker is not available. The time and key logic has plain unit tests.

## Roadmap

1. **Ingestion** — done.
2. **Events** — transactional outbox, Kafka topic `attendance.punch.recorded.v1`, a
   consumer that builds a daily summary per employee, dead-letter topic.
3. **Operations** — metrics, structured logs, a clock simulator, measured throughput.
4. **Integration** — signed webhooks for downstream systems.

## Limitations

- Single tenant.
- One JSON format for punches. No vendor protocol is implemented.
- The administration API uses one shared key from configuration.
- Two punches of the same type, by the same person, on the same device, with the same
  device timestamp are one punch. A clock that reports whole seconds cannot record two
  of them in one second.

## Stack

Java 25, Spring Boot 4, PostgreSQL 17, Flyway, JUnit 5, Testcontainers, springdoc.
