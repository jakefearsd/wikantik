---
canonical_id: 01KQ0P44W96EZAFXN94VF1AAX9
title: Server-Sent Events Patterns
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How Server-Sent Events (SSE) work, when they beat WebSockets, the simple
  mechanics that make them reliable, and the cases where they're a clean fit.
tags:
- sse
- server-sent-events
- streaming
- real-time
- api
related:
- WebSocketPatterns
- ApiProtocolComparison
- WebhookPatterns
hubs:
- WebServicesAndApisHub
---
# Server-Sent Events Patterns

Server-Sent Events (SSE) is a one-way streaming protocol over HTTP. The server pushes events to the client; the client reads them as they arrive. Compared to WebSockets, SSE is simpler and works through HTTP infrastructure — proxies, load balancers, CDNs — without special configuration.

For server-to-client streaming use cases, SSE is often the better choice than WebSockets. This page covers when and why.

## The basics

SSE uses a long-lived HTTP response. The server writes events as they happen; the client (browser-native EventSource) parses them.

Server side:
```
GET /events
Accept: text/event-stream

Response (streaming, never closes):

data: {"order":"abc","status":"shipped"}

data: {"order":"def","status":"delivered"}

```

Client side:
```javascript
const events = new EventSource('/events');
events.onmessage = (e) => {
    const data = JSON.parse(e.data);
    handle(data);
};
```

The connection stays open; the server writes new events; the client receives them in order.

## Format

SSE format is line-oriented:

```
event: order_update
data: {"id":"123","status":"shipped"}
id: 42

event: order_update
data: {"id":"124","status":"pending"}
id: 43
```

Fields:
- `event`: optional event name for client-side dispatch
- `data`: payload (one or more lines)
- `id`: optional event ID for resumption
- `retry`: optional reconnection delay in milliseconds

Empty line separates events.

## Reconnection and resumption

EventSource automatically reconnects on disconnect. The client sends the last received event ID:

```
GET /events
Accept: text/event-stream
Last-Event-ID: 42
```

Server resumes from event 43. The reconnection logic is built-in to the browser EventSource — no client code needed.

The server must support `Last-Event-ID` for resumption to work; otherwise reconnection misses events.

## When SSE wins over WebSockets

| Concern | SSE | WebSocket |
|---------|-----|-----------|
| Direction | Server → client only | Bidirectional |
| Protocol | HTTP | Custom upgrade from HTTP |
| Auto-reconnect | Built-in | Manual |
| Last-event resumption | Built-in | Custom |
| Browser support | EventSource native | WebSocket native |
| Proxy/CDN compatibility | Excellent (just HTTP) | Variable |
| Complexity | Low | Higher |

For one-way streaming (notifications, live updates, AI streaming responses), SSE is simpler. WebSockets pay off when the client also needs to send data through the same channel.

## Common use cases

### Notifications

Push notifications from server to client. New messages, status changes, alerts.

### Live updates

Stock tickers, sports scores, dashboards. Periodic updates pushed as they happen.

### AI streaming

Modern LLM APIs (OpenAI, Anthropic) use SSE for streaming responses. The server emits tokens as the model generates them.

### Progress updates

Long-running operations (uploads, batch processing, builds). Server emits progress events; client renders.

## Specific patterns

### Heartbeats

Long idle connections may be closed by proxies. Send a periodic comment line:

```
:keep-alive

```

The empty colon-line is a comment; clients ignore it. Keeps the connection alive without affecting the data stream.

### Authentication

EventSource doesn't support custom headers. Two approaches:

- **Cookies**: standard browser auth applies
- **URL token**: `GET /events?token=...` — works but tokens in URLs are logged

For APIs requiring header-based auth, custom EventSource implementations or fetch+ReadableStream alternatives exist.

### Backpressure

SSE doesn't have native backpressure. If the client can't keep up, events queue or are dropped depending on infrastructure.

For streams where every event must be delivered, consider:
- Periodic snapshots + delta events (client recovers from snapshot if it falls behind)
- Server-side drop on overflow with explicit signaling

### Filtering

For multi-topic streams, the server filters per-client:

```
GET /events?topics=orders,shipments
```

Or use named events that clients can ignore:

```javascript
events.addEventListener('orders', handleOrder);
events.addEventListener('shipments', handleShipment);
```

## Limitations

- One-way only (use WebSocket for bidirectional)
- Browser limit on concurrent EventSource connections per origin (typically 6)
- Not suitable for binary data (UTF-8 text only)
- Some old proxies buffer streams (rare in modern infrastructure)

## Common failure patterns

- **Forgetting CORS.** EventSource hits CORS like fetch.
- **No keep-alive on long-idle connections.** Proxies close them.
- **Buffering responses on the server.** Some frameworks buffer; you need to flush per event.
- **Treating SSE as bidirectional.** It's not; client-to-server requires separate HTTP request.
- **No reconnection support on server.** `Last-Event-ID` not handled; clients miss events on reconnect.

## Further Reading

- [WebSocketPatterns](WebSocketPatterns) — Bidirectional alternative
- [ApiProtocolComparison](ApiProtocolComparison) — Where SSE fits
- [WebhookPatterns](WebhookPatterns) — Server-to-server streaming alternative
- [WebServicesAndApis Hub](WebServicesAndApisHub) — Cluster index
