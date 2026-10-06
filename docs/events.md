# Events

## `attendance.punch.recorded.v1`

Published once for every punch stored for the first time. A punch that was already
stored, or that went to quarantine, produces no event. A quarantined punch produces its
event when it is reprocessed successfully.

| | |
|---|---|
| Key | `employeeId`. All punches of one person go to one partition and keep their order. |
| Value | JSON, UTF-8 |
| Partitions | 3 |
| Delivery | At least once. Consumers must ignore an `eventId` they have already processed. |

```json
{
  "eventId": "6f0c1c1e-2f65-4a39-9d55-0c5f3f2f4f0a",
  "punchId": "b0a1d0a4-8a3e-4f43-8f0a-6a1f0d2c9e11",
  "deviceId": "0d2f6b6e-5a43-4c60-9a57-3b7d4d0a1c22",
  "siteCode": "HQ",
  "employeeId": "EMP-17",
  "punchedAtLocal": "2026-09-01T07:58:00",
  "punchedAtUtc": "2026-09-01T11:58:00Z",
  "type": "IN",
  "recordedAt": "2026-09-01T11:58:03.412Z"
}
```

| Field | Meaning |
|---|---|
| `eventId` | Identifies the event. Stable across redeliveries. |
| `punchId` | Identifies the punch in this service. |
| `deviceId`, `siteCode` | The clock that reported the punch and its site. |
| `employeeId` | The employee, already resolved from the clock's own code. |
| `punchedAtLocal` | Wall-clock time at the device. It decides which work day the punch belongs to. |
| `punchedAtUtc` | The same moment as an instant. |
| `type` | `IN` or `OUT`. |
| `recordedAt` | When this service stored the punch. A punch from a clock that was offline can be recorded long after it happened. |

### Ordering

Punches are published in the order they were stored, not the order they happened: a
clock that was offline delivers old punches late. Consumers that care about the sequence
within a day must sort by `punchedAtUtc`.

### Compatibility

Fields may be added. A field is never removed, renamed, or given a different meaning; a
change like that is published as a new topic with the next version number, and both
topics run side by side while consumers move over.

## `attendance.punch.recorded.v1.dlq`

Records that the daily summary consumer could not process. An unreadable message goes
here at once. Any other failure is retried with growing pauses for about a minute, and
then the records of that batch go here. The original value is kept
unchanged, and the headers added by Spring Kafka carry the source topic, partition,
offset and the exception.
