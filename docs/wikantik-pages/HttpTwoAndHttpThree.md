---
canonical_id: 01KQ0P44QZAJR25CFSD029J16E
title: HTTP/2 and HTTP/3
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: What HTTP/2 and HTTP/3 actually changed — multiplexing, server push, QUIC,
  head-of-line blocking — and the practical impact on application performance and
  infrastructure choices.
tags:
- http2
- http3
- quic
- networking
- performance
related:
- TcpIpFundamentals
- WebServicesAndApisHub
- CdnArchitecture
- LoadBalancingStrategies
hubs:
- NetworkingHub
---
# HTTP/2 and HTTP/3

HTTP/1.1 was the web's protocol for two decades. HTTP/2 (2015) and HTTP/3 (standardized 2022) brought significant changes — multiplexing, header compression, QUIC. The performance gains are real for typical web traffic.

This page covers what changed and the practical impact.

## HTTP/1.1 limitations

The problems HTTP/2 was designed to solve:

### Head-of-line blocking

HTTP/1.1 sends requests one at a time per connection. A slow request blocks subsequent ones.

Browser workaround: open 6 connections per origin in parallel. Better than 1, but each connection has its own TCP overhead.

### Header overhead

Headers are repeated on every request. For APIs sending small JSON responses, headers dominate.

### Plain text

Easy to debug; less efficient on the wire.

## HTTP/2

The major changes:

### Single connection, multiplexing

One TCP connection per origin. Multiple requests/responses in parallel over that connection.

```
Connection 1
├── Stream 1: GET /index.html
├── Stream 3: GET /style.css
├── Stream 5: GET /app.js
└── Stream 7: GET /image.png
```

Each stream is independent. Slow image doesn't block fast JS.

### Header compression (HPACK)

Common headers are sent once and referenced by index. Big savings on header-heavy traffic.

### Binary framing

The protocol is binary instead of text. More compact; less ambiguous.

### Server push

Server can push resources the client will need. Largely deprecated — performance benefits didn't materialize; adds complexity.

### Stream priority

Streams have priorities; servers can serve high-priority first. Helps render-blocking resources.

### Limitations

The remaining problem: HTTP/2 multiplexes over one TCP connection. If TCP needs to retransmit a packet, all streams pause until retransmission. "TCP head-of-line blocking" — solved by HTTP/3.

## HTTP/3 / QUIC

QUIC is a new transport protocol (UDP-based) that replaces TCP for HTTP/3.

### Streams without TCP HOL blocking

QUIC has its own stream concept independent of underlying packets. A lost packet only affects its stream, not all of them.

### Built-in encryption

TLS 1.3 is integrated; the handshake is faster than TLS over TCP.

### Connection migration

A QUIC connection is identified by a connection ID, not by IP/port. Mobile clients switching networks (WiFi → cellular) keep the connection alive.

### 0-RTT resumption

Repeat connections can include data on the first packet, eliminating the handshake round-trip.

## Practical impact

### For typical web pages

HTTP/2 vs. HTTP/1.1: noticeable improvement for pages with many resources. Most sites use HTTP/2 today.

HTTP/3 vs. HTTP/2: marginal improvement for most pages. More benefit on lossy networks (mobile).

### For APIs

HTTP/1.1 vs. HTTP/2: HTTP/2 wins if you make many parallel requests. For sequential request/response patterns, less benefit.

For HTTP/2 APIs, gRPC builds on HTTP/2 and benefits from multiplexing.

### For real-time / streaming

HTTP/3's stream-isolation matters more. SSE, WebSocket-over-HTTP/3, and streaming use cases benefit.

### For mobile / lossy networks

HTTP/3's resilience to packet loss is a real win. Mobile users on weak signals see better performance.

## Adoption considerations

### Server support

- Web servers: Nginx, Apache, Caddy support HTTP/2 well; HTTP/3 is more recent
- Application servers: depends; many add HTTP/2 via reverse proxy
- CDNs: CloudFront, Cloudflare, Fastly all support both

### Client support

- Modern browsers: HTTP/2 universal; HTTP/3 widespread
- HTTP libraries: vary; check specific library

### TLS requirement

HTTP/2 in browsers requires TLS. Plain HTTP/2 (no TLS) is allowed by spec but browsers don't implement it. So HTTPS is implicit for HTTP/2 web traffic.

HTTP/3 is always encrypted (TLS 1.3 baked in).

### Operational considerations

- Debugging is harder: binary protocol, encrypted, harder to inspect
- Tooling exists (Wireshark with TLS keys, browser DevTools)
- Monitoring needs to be HTTP/2-aware for stream-level metrics

## When old HTTP/1.1 still wins

- Internal services where simplicity wins
- Debugging with curl or telnet
- Specific tooling that doesn't support HTTP/2

For external-facing traffic, HTTP/2 is the default; HTTP/3 is increasing adoption. For internal traffic, HTTP/1.1 is fine.

## Common failure patterns

- **Treating HTTP/2 as drop-in compatibility.** Mostly is, but server push and other features need explicit handling.
- **Server push enthusiasm.** Largely deprecated; don't build around it.
- **Single big stream blocking many small ones.** Stream priority helps but isn't always implemented well.
- **HTTP/3 without testing on lossy networks.** The benefits are most visible there.
- **Misconfiguring TLS for HTTP/2.** ALPN must offer h2.

## Further Reading

- [TcpIpFundamentals](TcpIpFundamentals) — Below HTTP
- [Web Services and APIs Hub](WebServicesAndApisHub) — Application protocols
- [CdnArchitecture](CdnArchitecture) — CDNs handle HTTP/2 and HTTP/3
- [LoadBalancingStrategies](LoadBalancingStrategies) — Load balancers and HTTP/2
- [Networking Hub](NetworkingHub) — Cluster index
