---
canonical_id: 01KQ0P44VTH8PPQSR3KZQ639N2
title: Reverse Proxy Patterns
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: What reverse proxies do beyond load balancing — TLS termination, caching,
  request modification, and the role they play in application architectures.
tags:
- reverse-proxy
- nginx
- envoy
- networking
- gateway
related:
- LoadBalancingStrategies
- CdnArchitecture
- WebApplicationFirewalls
- TcpIpFundamentals
hubs:
- NetworkingHub
---
# Reverse Proxy Patterns

A reverse proxy sits between clients and backend servers, accepting client requests and forwarding to backends. It's a load balancer's close relative — most reverse proxies do load balancing, but they often do more.

This page covers what reverse proxies do beyond load balancing and the architectural roles they play.

## The roles

A reverse proxy can do many things. Common patterns:

### Load balancing

Distribute requests across backend servers. See [LoadBalancingStrategies](LoadBalancingStrategies).

### TLS termination

Decrypt incoming HTTPS at the proxy; forward HTTP to backends. Centralizes cert management.

### Caching

Cache responses; serve subsequent identical requests without hitting backends. Significant load reduction for cacheable content.

### Compression

Gzip or Brotli responses. Saves bandwidth.

### Authentication / authorization

Check credentials at the proxy. Backends only see authenticated requests.

### Rate limiting

Limit requests per client. Protect backends from abuse.

### Request rewriting

Modify URLs, headers, or body before forwarding. Useful for migrations or legacy support.

### Header injection

Add headers backends need (X-Forwarded-For, request IDs, geo-location).

### Static file serving

Serve static assets directly without forwarding to backends.

## Common reverse proxies

### Nginx

The dominant open-source choice. Powerful; widely understood; well-documented. Uses event-driven architecture; very high concurrency.

```nginx
server {
    listen 443 ssl;
    server_name api.example.com;

    location / {
        proxy_pass http://backend_pool;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /static/ {
        root /var/www;
    }
}
```

### HAProxy

Strong on L4 and L7. Slightly more technical config than Nginx. Excellent at extreme scale.

### Envoy

Modern, cloud-native. Designed for microservices and service mesh. More complex; more features.

### Apache (httpd)

Older; still used. Less common as a reverse proxy than Nginx.

### Caddy

Modern; automatic HTTPS via Let's Encrypt. Simpler config than Nginx. Good for smaller deployments.

### Cloud-managed

ALB, Cloud Load Balancing, Application Gateway. Managed; less to operate.

## Architectural patterns

### Single reverse proxy per host

Each backend host has its own reverse proxy. The proxy serves static files, terminates TLS, and forwards to a local app.

### Frontend / API gateway

A single reverse proxy in front of all backend services. Routes by path:
- `/api/auth/*` → auth-service
- `/api/orders/*` → order-service
- `/static/*` → static file server

This is the "API gateway" pattern. Centralizes cross-cutting concerns.

### Sidecar proxy

A proxy runs alongside each application instance (typical in Istio, Linkerd service meshes). All inbound and outbound traffic goes through it.

Pros: cross-cutting concerns moved out of app code; consistent across languages.
Cons: more processes; latency overhead; operational complexity.

### Edge proxy

The proxy is at the network edge (CDN POPs, regional edges). Cloudflare, AWS CloudFront with Lambda@Edge.

Edge proxies do many of the same things as backend proxies but closer to users.

## TLS termination patterns

### Terminate at proxy; HTTP to backend

```
Client (HTTPS) → Proxy → Backend (HTTP)
```

Simple. Backends don't deal with TLS. Backend network must be trusted.

### Re-encrypt at proxy

```
Client (HTTPS) → Proxy → Backend (HTTPS)
```

Backend traffic also encrypted. Higher CPU; more cert management.

For internal traffic in a trusted VPC, terminate at proxy is fine. For zero-trust, re-encrypt.

### TLS passthrough

Proxy doesn't terminate; just routes encrypted traffic. The proxy can route based on SNI but can't inspect content.

Used when content inspection is not desired (end-to-end encryption mandate).

## Caching at the reverse proxy

```nginx
proxy_cache_path /var/cache levels=1:2 keys_zone=mycache:10m;

location / {
    proxy_cache mycache;
    proxy_cache_valid 200 5m;
    proxy_cache_use_stale error timeout updating;
    proxy_pass http://backend;
}
```

Pattern: cache 200 responses for 5 minutes; serve stale responses if backend is down.

For cacheable content, this dramatically reduces backend load. Be careful not to cache user-specific or authenticated content.

## Request manipulation

### Header injection

```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header Host $host;
```

Critical for backends to know the original client IP.

### URL rewriting

```nginx
location /old-path {
    rewrite ^/old-path/(.*) /new-path/$1 break;
    proxy_pass http://backend;
}
```

Useful for migrations.

## Common failure patterns

- **Misconfigured X-Forwarded-For.** Backend sees wrong client IP.
- **Wrong cache configuration.** User A sees user B's data.
- **Tight timeouts on backend that's slow.** Cascading 504s.
- **No health checks.** Dead backends still receive traffic.
- **Reverse proxy as single point of failure.** Use HA setup.
- **Insufficient connection limits.** Resource exhaustion.

## Further Reading

- [LoadBalancingStrategies](LoadBalancingStrategies) — Closely related
- [CdnArchitecture](CdnArchitecture) — Edge-level reverse proxy
- [WebApplicationFirewalls](WebApplicationFirewalls) — Often run as part of reverse proxy
- [TcpIpFundamentals](TcpIpFundamentals) — Underneath
- [Networking Hub](NetworkingHub) — Cluster index
