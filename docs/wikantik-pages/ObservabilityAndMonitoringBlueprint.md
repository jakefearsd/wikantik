---
cluster: devops-sre
type: reference
tags:
- observability
- monitoring
- opentelemetry
- prometheus
- grafana
- metrics
- health-checks
- sre
title: Observability and Monitoring Blueprint
summary: Target monitoring standard (RED/USE, OpenTelemetry, Prometheus naming, health
  probes) for several projects; Wikantik's actual setup is in the status note.
canonical_id: 01KQRPCRTW9GSE359WVS9FJQRM
status: active
date: '2026-05-03'
---
# Observability & Monitoring: The Unified Blueprint

> **Status (2026-10-13):** This blueprint is a target standard, and Wikantik does not follow it literally. Wikantik is instrumented with Micrometer, not the OpenTelemetry SDK (the codebase has no OpenTelemetry dependency). `GET /metrics` serves Prometheus text with `wikantik_`-prefixed meters, and there is one health endpoint, `GET /api/health` (JSON status `UP`, `DEGRADED` or `DOWN`, HTTP 503 when `DOWN`), rather than the startup/readiness/liveness triad in section 3. Both endpoints answer only to loopback and private-network clients. Wikantik ships no Grafana dashboards of its own: its self-hosted observability overlay was removed on 2026-05-19, and dashboards and alert rules now live in the jakemon observability plane (see [JakemonHub](JakemonHub) and [Simple Agility Observability Plane](SimpleAgilityObservabilityPlane)). The cross-product rules actually in force are in the [Simple Agility Telemetry Contract](SimpleAgilityTelemetryContract): a product-prefixed `/metrics` endpoint, ECS-style JSON logs on stdout, and one unauthenticated liveness endpoint whose path the product chooses (Wikantik's is `/api/health`). The plane attaches the `service` and `service_instance` labels; the product does not emit them itself.

To ensure consistent operational visibility across `wealthview`, `hud`, `operatorvoice`, and `Wikantik`, all services must adhere to this unified monitoring standard. We prioritize **OpenTelemetry (OTel)** for instrumentation and **Prometheus/Grafana** for collection and visualization.

## 1. Metric Standards: The RED and USE Methods

Every service in the ecosystem must produce metrics following these two industry-standard methodologies.

### 1.1 The RED Method (Request-Driven Services)
For synchronous services (APIs, Gateways), track:
- **(R)ate**: Number of requests per second.
- **(E)rrors**: Number of failed requests (non-2xx for HTTP, non-0 for gRPC).
- **(D)uration**: Time taken to process requests (P95 and P99 latencies).

### 1.2 The USE Method (Resource-Driven Components)
For infrastructure or background workers, track:
- **(U)tilization**: Average time the resource was busy (e.g., CPU, Thread Pool).
- **(S)aturation**: The degree to which extra work is queued (e.g., Queue Depth, Disk I/O Wait).
- **(E)rrors**: Count of error events at the resource level.

## 2. Implementation: OpenTelemetry (OTel)

Services must use the OTel SDK to ensure vendor-neutrality.

### 2.1 Standard Resource Attributes
Every exported metric must include these standard tags to enable unified filtering in Grafana:
```yaml
resource_attributes:
  service.name: "wealthview-api"
  service.namespace: "prod"
  deployment.environment: "production"
  host.name: "${HOSTNAME}"
```

### 2.2 Prometheus Metric Naming
Use the following naming convention to prevent metric collisions:
- `<service_name>_<subsystem>_<unit>_<type>`
- *Example*: `wealthview_ingestion_transactions_total` (Counter)
- *Example*: `hud_render_latency_ms_bucket` (Histogram)

## 3. Health Check Integration

Metrics alone are insufficient. Services must implement the **Health Check Triad** as defined in [HealthCheckPatterns](HealthCheckPatterns):

| Probe | Path | Logic |
| :--- | :--- | :--- |
| **Startup** | `/health/startup` | Returns 200 after internal caches/DB migrations are complete. |
| **Readiness** | `/health/ready` | Checks downstream connectivity (e.g., Plaid API, Redis). |
| **Liveness** | `/health/live` | Minimal check (e.g., thread-pool heartbeat). |

## 4. Grafana Visualization Standards

A "Golden Signal" dashboard must exist for every project, containing:

1.  **Traffic Overview**: RED metrics for the ingress layer.
2.  **Saturation Heatmaps**: USE metrics for database and message brokers.
3.  **Error Breakdown**: Rate of 4xx vs 5xx errors, or "Drift" alerts for AI models.
4.  **Health Ribbon**: Status of the 3 probes (Startup, Readiness, Liveness) across all replicas.

## 5. RAG Implementation Hook

For an agent instrumenting a new service (e.g., **operatorvoice**), the prompt should be:
> "Following the `ObservabilityAndMonitoringBlueprint`, instrument this Python service with OpenTelemetry to track RED metrics for the voice-interaction loops and expose a `/health/ready` endpoint that verifies the STT and TTS service connectivity."

## See Also
- [WealthViewArchitectureBaseline](WealthViewArchitectureBaseline) — Architecture baseline for the wealthview project.
- [HealthCheckPatterns](HealthCheckPatterns) — Deep-dive on probe implementation.
- [CloudMonitoring](CloudMonitoring) — Survey of available cloud tools.
- [AiObservabilityInProduction](AiObservabilityInProduction) — Specific metrics for LLM and Agentic systems.
