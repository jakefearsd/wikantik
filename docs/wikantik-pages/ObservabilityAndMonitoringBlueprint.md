---
canonical_id: 01KQRPCRTW9GSE359WVS9FJQRM
date: 2026-05-03T00:00:00Z
cluster: devops-sre
type: blueprint
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
relations:
- type: part-of
  target_id: 01KZHC6PVV4SBQM9R0F3T7K8Z8
- type: implements
  target_id: 01KQ0P44QVWKBREPTPKPKX8P6N
- type: derived-from
  target_id: 01KQ0P44NFST15NVWTXRXWPJM7
summary: A unified technical standard for production monitoring across the Wikantik
  project ecosystem. Defines consistent instrumentation using OpenTelemetry, Prometheus
  metrics standards, and Grafana dashboard patterns to ensure high-fidelity operational
  visibility.
status: active
---

# Observability & Monitoring: The Unified Blueprint

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

Metrics alone are insufficient. Services must implement the **Health Check Triad** as defined in [[HealthCheckPatterns]]:

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
- [[HealthCheckPatterns]] — Deep-dive on probe implementation.
- [[CloudMonitoring]] — Survey of available cloud tools.
- [[AiObservabilityInProduction]] — Specific metrics for LLM and Agentic systems.
