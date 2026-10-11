# Webhooks

For systems that cannot consume Kafka. A subscription is an HTTP address that is sent
every event published to `attendance.punch.recorded.v1` from the moment it is created.
The events are the same ones, byte for byte, described in [events.md](events.md).

## Subscribing

```bash
curl -s -X POST localhost:8080/api/v1/webhooks \
  -H 'X-Admin-Key: dev-admin-key' -H 'Content-Type: application/json' \
  -d '{"url":"https://hr.example.com/hooks/attendance","description":"HR system"}'
# -> {"subscription":{"id":"<ID>","status":"ACTIVE",...},"secret":"whsec_..."}
```

The secret is shown in this response and in the response of a rotation, and nowhere
else.

| | |
|---|---|
| `GET /api/v1/webhooks`, `GET /api/v1/webhooks/{id}` | Status, consecutive failures, last error, last delivery and how many events are queued. |
| `POST /api/v1/webhooks/{id}/rotate-secret` | Replaces the secret. Requests sent from then on are signed with the new one. |
| `POST /api/v1/webhooks/{id}/resume` | Starts sending again at once, from the oldest queued event. |
| `DELETE /api/v1/webhooks/{id}` | Removes the subscription and its queued events. |

## The request

```http
POST /hooks/attendance
Content-Type: application/json
X-Attendance-Signature: t=1790000000,v1=81bae186ea58f8c1d7197e1a4277f22f94b00e36734942329ca96618ba29ca1a

{"events":[{"eventId":"6f0c1c1e-...","employeeId":"EMP-17","type":"IN",...}]}
```

One request carries up to 100 events, oldest first. Answer with any `2xx` status once
the events are safely stored; the response body is ignored. Any other status, a
redirect, or no answer within 5 seconds counts as a failure.

## Verifying the signature

`v1` is the HMAC-SHA256, in lowercase hex, of the text `<t>.<body>` with the
subscription's secret as the key, where `<t>` is the number in the header and `<body>`
is the request body exactly as received. Verify it against the raw bytes, before
parsing the JSON.

```python
import hashlib, hmac, time

def verify(secret: str, header: str, body: bytes, tolerance: int = 300) -> bool:
    fields = dict(part.split("=", 1) for part in header.split(","))
    signed = fields["t"].encode() + b"." + body
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    fresh = abs(time.time() - int(fields["t"])) <= tolerance
    return fresh and hmac.compare_digest(expected, fields["v1"])
```

Checking that `t` is recent is what stops someone who captured a request from sending
it again later. The header in the example above is the real signature of the body
`{"events":[]}` with the secret `whsec_test`, and can be used to test an implementation
(with the freshness check turned off).

## Delivery

**At least once.** A batch is marked delivered only after the endpoint accepted it. If
the answer is lost, or the service stops in between, the batch is sent again. Ignore an
`eventId` that was already processed.

**In order, per subscription.** A failed batch is not skipped: the same events are sent
again, and newer ones wait behind them. Events of one employee arrive in the order they
were published. As with Kafka, that is the order punches were stored, not the order
they happened; see [Ordering](events.md#ordering).

**Retries.** After a failed request the service waits 1 second, then doubles the wait
with each further failure, up to 5 minutes. After 10 failures in a row, about 9
minutes, the subscription becomes `SUSPENDED` and nothing more is sent. Events keep
being queued for it. `POST .../resume` starts again from the oldest one.

A later batch can contain more events than the failed one did, so a retry is not
guaranteed to have the same body as the request before it.

## Operating it

| Metric | |
|---|---|
| `attendance_webhook_attempts_total{result}` | Requests sent, `succeeded` or `failed`. |
| `attendance_webhook_events_delivered_total` | Events accepted by an endpoint. |
| `attendance_webhook_events_pending` | Events queued and not yet accepted. |
| `attendance_webhook_subscriptions_suspended` | The one to alert on. |

The defaults above are the `attendance.webhooks.*` properties in
`application.properties`.
