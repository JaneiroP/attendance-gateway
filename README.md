# attendance-gateway

A service that sits between time clocks and the systems that need attendance data.
Clocks send punches that are duplicated, late, and stamped in local time under the
clock's own code for each person. This service stores each punch exactly once, converts
it to UTC, resolves the employee, sets aside what it cannot accept with the reason, and
publishes a clean event to Kafka for every punch it stores.

**Status: milestone 4 of 4, in progress.** Ingestion, idempotency, quarantine, event
publication, the daily summary consumer, metrics, a clock simulator and signed webhooks
are done and tested. No downstream system consumes the events yet and the service is
not deployed anywhere; see [Roadmap](#roadmap).

```text
clocks --HTTP--> ingest --> PostgreSQL (punch + outbox_event, one transaction)
                                   |
                            outbox publisher
                                   v
                     Kafka  attendance.punch.recorded.v1
                          |                        |
          daily summary consumer            webhook fan-out
                          |                        |
          PostgreSQL (daily_summary)   PostgreSQL (webhook_delivery)
                          |                        |
        failures --> ...recorded.v1.dlq    webhook dispatcher --signed HTTP--> subscribers
```

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
| Events | Every newly stored punch is published to `attendance.punch.recorded.v1`, keyed by employee. The contract is in [docs/events.md](docs/events.md). |
| Daily summary | A consumer builds, per employee and day, first in, last out and worked minutes. `GET /api/v1/daily-summaries?employeeId=&from=&to=` reads it. |
| Webhooks | For systems that cannot consume Kafka: the same events, in batches, posted to a registered address and signed with HMAC-SHA256. Failed requests are retried with growing pauses; an endpoint that keeps failing is suspended and loses nothing once resumed. The contract is in [docs/webhooks.md](docs/webhooks.md). |
| Operations | Prometheus metrics at `/actuator/prometheus`: punches by outcome, outbox backlog and the age of its oldest event, events consumed, webhook attempts and backlog, Kafka client metrics. Every request has a correlation id on its log lines and in the `X-Request-Id` response header. JSON logs with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. |

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

**Events go through an outbox, not straight to Kafka.** The event row is written in the
same transaction as the punch, and a separate publisher sends it. If the broker is down,
clocks still get their `202` and nothing is lost. `BrokerOutageTest` freezes the Kafka
container, ingests 40 punches, checks that the publisher fails and the events wait, then
unfreezes it and checks that all 40 reach the summary exactly once.

**Delivery is at least once, and the consumer is idempotent.** An event is marked
published only after the broker acknowledged it, so a timeout sends it again. The
consumer stores each event id it has applied; a redelivery inserts nothing.

**The summary is recomputed from all of the day's punches.** A clock that was offline
delivers a morning punch after the evening one. Recomputing from the full set gives the
same result in any arrival order, which an incremental update would not.

**The producer is not allowed to block for long.** With Kafka's default
`max.block.ms` of 60 seconds, the first send during an outage held a database
transaction open for a minute. It is set to 5 seconds here. The outage test found this.

**The consumer works per batch, not per event.** The first version applied one event
per database transaction and committed one Kafka offset per event. Under load it
managed about 65 events per second while ingestion took in thousands, and the backlog
only grew. It now takes each poll as a batch: one transaction, one recomputation per
affected day, one offset commit. Idempotency is what makes that safe, because a batch
that is delivered again changes nothing. The load test found this.

**The consumer does not read the ingest tables.** It keeps its own copy of what the
events carry. It lives in this service for now, and could move out with its tables.

**Webhooks are fed from Kafka, not from the ingest path.** The fan-out is one more
consumer group on the topic. A subscriber over HTTP sees exactly what a Kafka consumer
sees, and a slow endpoint cannot hold back ingestion or the daily summary.

**Retry state belongs to the subscription, not to each event.** An endpoint is either
reachable or it is not. A failed batch stops that subscriber's queue and is sent again
after a pause that doubles each time; newer events wait behind it instead of
overtaking it. Per-event retries would be simpler to write and would deliver events
out of order after every hiccup.

**No database lock is held during an HTTP request.** The dispatcher claims a
subscription with a single `UPDATE` that moves its next attempt to the end of a lease,
sends without a transaction, and records the outcome afterwards. If it dies in between,
the lease runs out and the batch is sent again. Each subscriber is sent to on its own
virtual thread, so one endpoint that takes its whole timeout delays only itself.

**An endpoint that keeps failing is suspended, not retried forever.** After ten
failures in a row the subscription stops and waits for a person. Its events keep being
queued, and `WebhookDeliveryTest` checks that resuming delivers all of them, in order.

## Running it

Requirements: JDK 25 and Docker.

```bash
docker compose --profile app up -d --build   # PostgreSQL, Kafka and the service
```

The service listens on `localhost:8080` with the administration key `dev-admin-key`.
To run it from source instead, start only the dependencies:

```bash
docker compose up -d                         # PostgreSQL and Kafka
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

curl -s 'localhost:8080/api/v1/daily-summaries?employeeId=EMP-17&from=2026-09-01&to=2026-09-01' \
  -H 'X-Admin-Key: dev-admin-key'
```

API documentation is served at `/swagger-ui.html`.

If `ATTENDANCE_ADMIN_API_KEY` is not set, every administration request is rejected.

## Clock simulator and measured throughput

`tools/ClockSimulator.java` plays a fleet of clocks against a running service: every
clock sends at once, batches are shuffled, and one batch in five is sent twice. It has
no dependencies and needs no build:

```bash
java tools/ClockSimulator.java --devices 20 --punches-per-device 5000
```

It exits non-zero unless every distinct punch was accepted exactly once, every event was
published, and the summary consumer applied every event exactly once.

Three consecutive runs of that command, 100,000 distinct punches each in batches of 100
from 20 concurrent clocks:

| | Range over three runs |
|---|---|
| Ingestion, distinct punches stored | 5,700 to 7,800 per second |
| Ingestion, including the duplicates sent | 6,900 to 9,300 punches per second |
| Request latency for a 100-punch batch | p50 190 to 266 ms, p99 477 to 668 ms |
| Events published to Kafka | 2,900 to 3,400 per second |
| Punch received to summary updated, whole run | 2,700 to 2,850 events per second |

All three runs passed the exactly-once check, and afterwards the database held the same
number of punches, consumed events and punches counted in summaries.

Read these as what one laptop does, not as capacity: an Intel i5-10310U (4 cores, 8
threads) with 16 GB, running the service, PostgreSQL, Kafka and the simulator together
under Docker Desktop on Windows, one service instance, default JVM settings. Ingestion
is faster than publication, so under sustained load the outbox publisher is the limit:
it is a single thread that sends 200 events and waits for the broker before the next
200.

## Tests

```bash
./mvnw verify
```

The integration tests go through real HTTP against a real PostgreSQL and a real Kafka
started by Testcontainers, because the behavior under test is `ON CONFLICT`, row
locking and broker delivery. They are skipped when Docker is not available. The time,
key and summary logic has plain unit tests.

## Roadmap

1. **Ingestion** — done.
2. **Events** — done.
3. **Operations** — done.
4. **Integration** — signed webhooks are done. Still open: a downstream system that
   consumes the events, and a deployment.

## Limitations

- Single tenant.
- One JSON format for punches. No vendor protocol is implemented.
- The administration API uses one shared key from configuration.
- `/actuator/prometheus` and the API documentation are open. In a deployment they
  belong behind the network boundary or on a separate management port.
- One outbox publisher. A second instance would not lose or duplicate work, but events
  could be published out of insertion order.
- Published outbox rows and delivered webhook rows are never deleted.
- A webhook signing secret is stored as it is, because signing needs it. In a
  deployment it belongs in an encrypted column or a secrets manager.
- The service posts to whatever address an administrator registers, including ones
  only reachable from inside its own network.
- The measured throughput below was taken before webhooks existed and with no
  subscribers. Webhook delivery has not been load tested.
- A work day is the calendar day at the device, so a shift that crosses midnight is
  split across two summaries.
- Two punches of the same type, by the same person, on the same device, with the same
  device timestamp are one punch. A clock that reports whole seconds cannot record two
  of them in one second.

## Stack

Java 25, Spring Boot 4, Spring Kafka, Apache Kafka 4, PostgreSQL 17, Flyway, JUnit 5,
Testcontainers, springdoc.
