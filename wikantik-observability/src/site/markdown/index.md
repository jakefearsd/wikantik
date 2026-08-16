# Wikantik Observability

Health checks, Prometheus metrics via Micrometer, request correlation, and
structured logging support. Includes per-subsystem health checks (database,
engine, search index), a metrics servlet, and request-scoped filters for
correlation IDs, rate limiting, and backpressure.

This module supplies the machinery; the previous standalone monitoring
stack has since been extracted into a separate repository, so this module
now covers in-process health/metrics only.
