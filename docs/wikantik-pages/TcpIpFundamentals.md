---
canonical_id: 01KQ0P44XCAC6RJ2ZY0A92NECF
title: TCP/IP Fundamentals
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: The parts of TCP/IP that matter for application engineers — TCP vs. UDP,
  the OSI model in practice, ports and sockets, and the failure modes that surface
  in application code.
tags:
- tcp-ip
- networking
- protocol
- udp
- sockets
related:
- DnsDeepDive
- HttpTwoAndHttpThree
- LoadBalancingStrategies
- NetworkTroubleshooting
hubs:
- Networking Hub
---
# TCP/IP Fundamentals

TCP/IP is the networking foundation under almost everything. Application engineers don't usually care about the bit-level details — but the parts that surface in application behavior (timeouts, connection limits, retries, packet loss) require understanding the protocol.

This page covers the parts that matter for application work.

## The OSI model in practice

The textbook OSI model has 7 layers. In practice, networking happens in 4-5 functional layers:

| Layer | Responsibility | Examples |
|-------|----------------|----------|
| Application | What you're sending | HTTP, gRPC, DNS |
| Transport | Delivery semantics | TCP, UDP |
| Network | Routing | IP |
| Link | Local network frame | Ethernet, WiFi |
| Physical | Bits on wire | Cables, radio |

Application engineers work mostly at Application and Transport layers. Network/Link/Physical matters when debugging or designing infrastructure.

## TCP vs. UDP

The big transport choice.

### TCP

- **Connection-oriented**: 3-way handshake before data flows
- **Reliable**: retransmits lost packets
- **Ordered**: packets arrive in order
- **Flow control**: sender slows when receiver buffer fills
- **Congestion control**: backs off when network is busy

Used by HTTP, gRPC, SSH, most application protocols. The default for "just work" reliability.

### UDP

- **Connectionless**: send packets without setup
- **Unreliable**: packets may be lost; no automatic retransmit
- **Unordered**: packets may arrive out of order
- **No flow control**: sender goes as fast as it wants

Used by DNS, video streaming (real-time), QUIC (which underlies HTTP/3), gaming. Faster but caller handles reliability.

### When TCP is wrong

- Real-time data where stale > lost (video calls, gaming)
- One-shot queries where retry-at-application-level is fine (DNS)
- Multicast (TCP doesn't support it)

For most application code, TCP is right. UDP is for specific use cases.

## Ports and sockets

A network connection is identified by a 5-tuple:

```
(protocol, src_ip, src_port, dst_ip, dst_port)
```

Each application listens on a port (HTTP on 80, HTTPS on 443, etc.). Each connection has a unique combination.

### Ephemeral ports

When you make an outgoing connection, the OS picks a random unused port (typically 32768-60999). This is the source port. The destination is the well-known port (80, 443).

Ports are limited: ~28K ephemeral. A client making many connections to the same destination eventually runs out — the "ephemeral port exhaustion" problem.

### Connection state

Each connection is in a TCP state: SYN_SENT, ESTABLISHED, TIME_WAIT, etc. `netstat` or `ss` shows them.

`TIME_WAIT` is common after closing a connection. The OS holds the port for 2 × MSL (typically 60 seconds) to handle late packets. High-traffic servers can have many TIME_WAITs.

## Common application-level concerns

### Connection pooling

Establishing a TCP connection has cost (handshake, slow-start). Reusing connections amortizes the cost.

HTTP/1.1 keep-alive reuses connections. HTTP/2 multiplexes many requests over one. Both reduce per-request connection cost.

For DBs and other backends, application-level pools (HikariCP, etc.) hold persistent connections.

### Timeouts

Multiple timeouts are involved:

- **Connect timeout**: time to establish the connection
- **Read timeout**: time waiting for first byte after sending
- **Total timeout**: end-to-end limit

Defaults (often very high, like minutes) are wrong for production. Set explicit timeouts; fail fast on stuck connections.

### Retries

TCP retransmits packets automatically — at the protocol level. Application-level retries handle different failures (server returned error, connection broke entirely, timeout).

Retry policies vary by what failed. Idempotent operations are safe to retry; non-idempotent need idempotency keys.

### Connection limits

OS-level limits: file descriptors per process. Tune `ulimit -n` for high-connection servers.

Network limits: ports, conntrack table size on routers/firewalls.

### Latency vs. throughput

Latency = round-trip time. Throughput = bytes per second.

TCP's slow-start means a new connection takes time to ramp up. For small short-lived requests, latency dominates. For long-lived connections, throughput matters more.

## Common failure patterns

- **No timeouts.** Stuck connections accumulate.
- **Default timeouts way too long.** Failures invisible until they cascade.
- **No connection pooling.** Per-request connection cost adds up.
- **Ephemeral port exhaustion.** Source port limit hit on high-volume clients.
- **TIME_WAIT accumulation.** Kernel tuning needed for very high-traffic servers.
- **Unauthenticated UDP.** UDP responses not validated; spoofing possible.

## Further Reading

- [DnsDeepDive](DnsDeepDive) — DNS uses both TCP and UDP
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Application protocols above TCP
- [LoadBalancingStrategies](LoadBalancingStrategies) — How load balancers handle TCP
- [NetworkTroubleshooting](NetworkTroubleshooting) — TCP-level diagnostic tools
- [Networking Hub](Networking+Hub) — Cluster index
