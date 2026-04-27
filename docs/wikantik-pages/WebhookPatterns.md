---
canonical_id: 01KQ0P44YVR5EHVTJH8ZSR5W6X
title: Webhook Patterns
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How to design webhooks — delivery guarantees, retry logic, signature verification,
  and the patterns that make webhook-based integrations reliable for both senders
  and receivers.
tags:
- webhooks
- api
- integration
- retry
- signature
related:
- IdempotencyPatterns
- ApiProtocolComparison
- ServerSentEventsPatterns
- WebSocketPatterns
hubs:
- WebServicesAndApis Hub
---
# Webhook Patterns

A webhook is an HTTP callback. The producer (Stripe, GitHub, your application) sends an HTTP POST to a consumer-provided URL when something happens. The consumer responds with HTTP 2xx to acknowledge.

Webhooks are the standard pattern for server-to-server event notifications. The mechanics are simple; the operational details — delivery guarantees, retries, signatures — are where the complexity lives.

This page is about the patterns from both sides: designing webhooks that work, and consuming webhooks reliably.

## Delivery guarantees

Webhooks are at-least-once. The producer sends; if delivery fails (timeout, 5xx), the producer retries. Eventually most events are delivered; sometimes events are delivered multiple times.

Consumers must handle:
- Duplicate deliveries (idempotency)
- Out-of-order arrivals
- Eventual consistency (events may be delayed)

Senders should not assume:
- Delivery order
- Exactly-once delivery
- Synchronous processing

## Retry strategy

Standard retry approach for senders:

- Initial delivery: immediate
- 5xx response: retry with exponential backoff
- 4xx response: don't retry (consumer error; not transient)
- Timeout: retry like 5xx

Backoff schedule example: 1m, 5m, 15m, 1h, 6h, 24h, 48h, 72h. After ~3 days of retries, mark as failed.

Document the retry policy. Consumers need to know how long to expect retries to continue.

## Signature verification

Webhooks must be signed; consumers must verify. Without signatures, anyone can POST to the webhook URL and trigger consumer logic.

Standard pattern (Stripe, GitHub, Slack):

```
1. Producer creates HMAC-SHA256 of payload using shared secret
2. Producer includes signature in HTTP header
3. Consumer recomputes HMAC and verifies equality
```

```http
POST /webhooks/orders
X-Signature: sha256=<hex-encoded-hmac>
Content-Type: application/json

{ "event": "order.shipped", ... }
```

Consumer code:
```python
expected = hmac.new(secret, payload, hashlib.sha256).hexdigest()
if not hmac.compare_digest(expected, received):
    return 401
```

`compare_digest` is constant-time; `==` is timing-attack-vulnerable.

## Replay attacks

Even with signatures, an attacker who captures a valid webhook can replay it. Defenses:

- **Timestamp + tolerance**: include timestamp in signature; reject events older than ~5 minutes
- **Nonce tracking**: server stores recent event IDs; rejects duplicates within window

Stripe's pattern: signature includes timestamp; consumer rejects if `|now - timestamp| > 300s`.

## Consumer requirements

Reliable webhook consumers need:

### Quick acknowledgment

Respond with 2xx as soon as the webhook is received and stored — typically under 5 seconds. Producers often timeout faster than that.

```python
def handle_webhook():
    verify_signature()
    enqueue_for_processing()  # async work
    return 200  # ack now
```

Don't do the actual work synchronously; enqueue and process asynchronously. Otherwise slow processing causes producer retries (and duplicate work).

### Idempotency

Same event delivered twice should not double-process. Use the event ID:

```python
if not seen_event_ids.exists(event_id):
    seen_event_ids.add(event_id, ttl=7days)
    process(event)
```

See [IdempotencyPatterns](IdempotencyPatterns).

### Persistent queue

Don't process events directly from the HTTP handler. Persist to a queue (database, Kafka, SQS) and process from there. If processing fails, the event is preserved for retry.

### Logging and monitoring

- Log every received event with its ID
- Alert on delivery failures (4xx responses, parsing errors)
- Track processing latency

### Disaster recovery

If your webhook endpoint is down, events are eventually lost (after producer retry exhaustion). Mitigations:

- High availability (multiple endpoint instances behind load balancer)
- Event log endpoint on the producer side ("get all events since X") for backfill
- Manual replay tools

## Producer responsibilities

### Idempotency keys

Even with at-least-once delivery, include an event ID:

```json
{
    "event_id": "evt_8d4f...",
    "event_type": "order.shipped",
    "data": {...}
}
```

Consumers use the event ID for deduplication.

### Event ordering

Within a single resource (one order's events), events should be sequential. Events to different resources can be parallel.

If strict ordering matters, include a sequence number or rely on retry-with-backoff to maintain order.

### Webhook management

Provide a UI or API for:

- Subscribing to events (which event types)
- Configuring endpoint URLs
- Viewing delivery history
- Manually retrying failed deliveries
- Disabling/re-enabling a webhook

The Stripe/GitHub-style management UI sets the bar.

### Versioning

Events may evolve. Version them:

- Include a `version` field in payload
- Document what each version means
- Maintain compatibility for old subscribers

Or: namespace event types per major version.

## Common patterns to avoid

- **Synchronous processing in the handler.** Causes producer retries.
- **No signature verification.** Anyone can POST.
- **No idempotency.** Duplicate processing.
- **Trusting timestamps from the client without signing them.** Attacker forges them.
- **Single event per webhook.** Sometimes batching events is more efficient.
- **No delivery history.** Consumers can't audit; producers can't debug.

## Common failure patterns

- **Slow consumer.** Producer retries; duplicates pile up.
- **No replay protection.** Captured webhooks can be replayed.
- **Drop on signature mismatch silently.** Returns 401 to producer; producer retries.
- **No webhook documentation.** Consumers can't anticipate failure modes.
- **Brittle event schema.** Adding fields breaks consumers; document forward-compatibility.

## Further Reading

- [IdempotencyPatterns](IdempotencyPatterns) — Idempotency for retries
- [ApiProtocolComparison](ApiProtocolComparison) — Where webhooks fit
- [ServerSentEventsPatterns](ServerSentEventsPatterns) — Pull-based alternative
- [WebSocketPatterns](WebSocketPatterns) — Bidirectional alternative
- [WebServicesAndApis Hub](WebServicesAndApis+Hub) — Cluster index
