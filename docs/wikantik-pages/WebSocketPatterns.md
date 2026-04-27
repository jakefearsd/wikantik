---
canonical_id: 01KQ0P44YTRFCC2JVBK5FR45A2
title: WebSocket Patterns
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How WebSockets work in production — connection management, backpressure,
  authentication, fallbacks, and the patterns that make WebSocket-based applications
  survivable at scale.
tags:
- websocket
- real-time
- streaming
- api
- connection-management
related:
- ServerSentEventsPatterns
- ApiProtocolComparison
- WebhookPatterns
- HttpTwoAndHttpThree
hubs:
- WebServicesAndApis Hub
---
# WebSocket Patterns

WebSockets provide a bidirectional, full-duplex communication channel between client and server. Once established, both sides can send messages independently. The protocol upgrades from HTTP to a long-lived TCP connection.

This page is about the patterns that make WebSocket applications work in production — not the protocol details, but the operational concerns.

## When WebSocket is right

- Bidirectional real-time communication (chat, collaborative editing)
- Server-initiated push that's part of an interactive flow
- Low-latency messaging where polling is too slow
- Applications where SSE's one-way constraint is limiting

## When WebSocket is wrong

- One-way server-to-client streams: use [SSE](ServerSentEventsPatterns); simpler.
- Request-response patterns: use HTTP; better tooling.
- Workloads dominated by request-response with occasional pushes: HTTP plus a fallback push mechanism is often simpler.

## Connection lifecycle

```
Client → HTTP GET with Upgrade headers → Server
Server → HTTP 101 Switching Protocols → Client
Connection upgraded to WebSocket
... message exchange ...
Either side → close frame → Other side
```

The HTTP-to-WebSocket upgrade requires:
- `Upgrade: websocket`
- `Connection: Upgrade`
- `Sec-WebSocket-Key` (handshake security)

Most clients/servers handle this automatically.

## Authentication

WebSocket authentication is done during the upgrade. Several patterns:

### Cookie-based

If your app already uses cookies for auth, they apply to the upgrade request. Simple.

### Token in subprotocol

```javascript
new WebSocket('wss://api/ws', ['token.your-jwt-here'])
```

The token is in the `Sec-WebSocket-Protocol` header. Cleaner than URL parameters.

### Token in URL

```
wss://api/ws?token=...
```

Works but logged everywhere; not great for sensitive tokens.

### Post-connect authentication

Connect first, then send an auth message:

```
{ "type": "auth", "token": "..." }
```

Server keeps the connection open only after successful auth. Useful for protocols where the upgrade itself is unauthenticated.

## Backpressure

What happens when the server sends faster than the client consumes? Or vice versa?

The TCP layer provides some backpressure (the OS buffers fill; sends block). Above that:

- **Drop on overflow**: discard messages when buffer is full. Useful for high-frequency state updates.
- **Slow down**: send rate limit; server stops sending until ACK received.
- **Disconnect on overflow**: aggressive but simple.

For real-time but loss-tolerant streams (cursor positions, presence), drop on overflow. For streams where every message matters, slow down or disconnect.

## Heartbeats

Long-idle WebSocket connections may be closed by NAT, proxies, or network equipment. Heartbeats keep the connection alive and detect failures.

```
Client → ping (every 30s) → Server
Server → pong → Client
```

If a pong is missed, reconnect. Specific timeouts depend on your infrastructure.

WebSocket has built-in ping/pong frames; some libraries handle this automatically.

## Reconnection

Always assume disconnects happen. Reconnection logic:

1. Detect disconnect (heartbeat failure, close frame, error)
2. Wait with exponential backoff (start at 1s, max ~30s)
3. Reconnect; re-authenticate
4. Resync state (request "what did I miss")

The "resync state" step is application-specific. Some patterns:

- **Sequence numbers**: server assigns IDs; client sends "give me everything after ID X"
- **Snapshot on reconnect**: server sends a state snapshot; client replaces local state
- **No resync**: client missed events; tolerate or reload

## Fan-out

Broadcasting to many connected clients. The challenge: each connection holds a TCP socket; servers can hold thousands but not millions.

For very high fan-out:
- Pub/sub backend (Redis, Kafka): server processes publish to topics; per-connection processes subscribe
- Specialized infrastructure: services like Pusher, Ably, AWS API Gateway WebSocket
- Server sharding by topic or user

## Library and framework support

- **Node.js**: ws, Socket.IO
- **Java/Spring**: Spring WebSocket, native Jakarta WebSocket
- **Python**: websockets, FastAPI's websocket support
- **Go**: gorilla/websocket
- **Browsers**: native WebSocket API

Socket.IO adds features (auto-reconnect, fallbacks to long-polling, rooms) at the cost of being its own protocol on top of WebSocket. Use vanilla WebSocket unless Socket.IO's features are specifically needed.

## Common operational issues

### Sticky sessions

If clients connect to one server and you have multiple servers, the connection is server-pinned. Load balancers need sticky sessions or the connection breaks.

For stateless WebSocket usage (using a pub/sub backend for shared state), any server can handle any connection — sticky sessions optional.

### Memory pressure

Each connection consumes memory. Estimate ~10-50 KB per idle connection plus per-message buffers. 100K concurrent connections = 1-5 GB just in baseline memory.

### Connection limits

Each TCP connection consumes a file descriptor. Tune `ulimit -n` and OS limits accordingly.

## Common failure patterns

- **No reconnection logic.** Disconnects mean lost functionality.
- **No heartbeats.** Stale connections accumulate.
- **Unbounded buffers.** Slow client or fast server fills memory.
- **Missing authentication on upgrade.** Connections without auth.
- **No connection limits per user.** Single user opens 1000 connections; DoS.
- **Treating WebSocket as request-response.** WebSocket is best for streams; for RPC-style, HTTP is usually clearer.

## Further Reading

- [ServerSentEventsPatterns](ServerSentEventsPatterns) — Simpler one-way alternative
- [ApiProtocolComparison](ApiProtocolComparison) — When WebSocket fits
- [WebhookPatterns](WebhookPatterns) — Server-to-server alternative for callbacks
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — HTTP/3 + QUIC have their own streaming model
- [WebServicesAndApis Hub](WebServicesAndApis+Hub) — Cluster index
