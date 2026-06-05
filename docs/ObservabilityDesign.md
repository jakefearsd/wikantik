> **Retired 2026-05-19.** The self-hosted Promtail/Loki/Prometheus/Grafana overlay was
> removed from this repository. Monitoring is now handled by the external **jakemon** stack
> (Grafana Alloy agent → central Prometheus + Loki + Grafana on host `inference`), which
> scrapes the wikantik `/metrics` endpoint. See `docs/WikantikOperations.md` for current
> monitoring notes. The large configuration sections (Promtail YAML, Loki config, Prometheus
> prometheus.yml, docker-compose.observability.yml, systemd units) from the original design
> have been removed. The instrumentation shipped in `wikantik-observability` remains active
> and is documented below.

# Wikantik Observability — Shipped Instrumentation

## What is implemented

The `wikantik-observability` module provides three runtime capabilities that are
deployed and active in every environment.

### 1. Health checks — `/api/health`

Served by `HealthServlet`. Returns a JSON body with the aggregate status
(`UP` / `DOWN`) and per-check detail for:

- `EngineHealthCheck` — wiki engine initialized
- `DatabaseHealthCheck` — JNDI DataSource reachable
- `SearchIndexHealthCheck` — Lucene index available

A non-200 response or `"status": "DOWN"` indicates a degraded instance.

```bash
curl http://localhost:8080/api/health
```

### 2. Prometheus metrics — `/metrics`

Served by `MetricsServlet`. The endpoint is gated by `InternalNetworkFilter`,
which allows only loopback (`127.x.x.x` / `::1`) and configured private CIDR
ranges. External requests receive 403.

The jakemon stack's Grafana Alloy agent scrapes this endpoint from the host
network and forwards metrics to central Prometheus on host `inference`.

```bash
# From the local host (loopback is allowed)
curl http://localhost:8080/metrics
```

### 3. Request correlation — `X-Request-ID`

`RequestCorrelationFilter` attaches a UUID to every inbound request. If the
client already sent an `X-Request-ID` header, that value is echoed back;
otherwise a fresh UUID is generated. The ID is propagated through the Log4j2
MDC so every log line for the request carries the same correlation key.

---

## What was retired (2026-05-19)

The following were design-only or optional overlays and have been removed:

- `docker-compose.observability.yml` — Prometheus + Grafana compose overlay
- `observability/` config directory — Promtail YAML, Loki config, Prometheus
  `prometheus.yml`, Grafana provisioning
- systemd unit files for Loki, Promtail, Prometheus, node_exporter
- Loki/Promtail log-aggregation tier (was design-only, never deployed)

The jakemon repo on host `inference` now owns the full monitoring stack. See
`docs/WikantikOperations.md` for the current monitoring architecture.
