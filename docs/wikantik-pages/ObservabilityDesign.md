---
date: '2026-05-15'
status: active
summary: Open-source observability stack for Wikantik — Log4j2 JSON logs shipped via
  Promtail to Loki, Prometheus metrics, and Grafana dashboards.
tags:
- observability
- monitoring
- prometheus
- grafana
- loki
type: article
canonical_id: 01KQ0P44T771NATW10QVZEVR5Y
cluster: devops-sre
title: 'Wikantik Observability Stack: Loki, Prometheus, Grafana'
hubs:
- InfrastructureSreHub
---
# Wikantik Observability System Design

## Overview

This document describes an open-source observability stack for Wikantik, leveraging the existing Log4j2 logging infrastructure. The design follows the three pillars of observability: **Logs**, **Metrics**, and **Traces**.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Observability Architecture                          │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│     Wikantik      │     │     Tomcat       │     │   System Host    │
│   (Log4j2)       │     │  Access Logs     │     │   (node_exporter)│
└────────┬─────────┘     └────────┬─────────┘     └────────┬─────────┘
         │                        │                        │
         │ JSON logs              │ Combined format        │ Metrics
         ▼                        ▼                        │
┌─────────────────────────────────────────────┐            │
│              Promtail                        │            │
│         (Log collector agent)                │            │
└────────────────────┬────────────────────────┘            │
                     │                                      │
                     │ Push logs                            │
                     ▼                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Grafana Stack                                  │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │      Loki       │  │   Prometheus    │  │    Grafana      │             │
│  │  (Log storage)  │  │ (Metrics store) │  │ (Visualization) │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Components

### 1. Log Collection Layer

#### Promtail (Log Shipper)
- **Purpose**: Collects logs from files and ships to Loki.
- **Mechanism**: Watches log files, extracts labels, and pushes to Loki using a gRPC stream.

### 2. Storage Layer

#### Loki (Log Aggregation)
- **Purpose**: Horizontally-scalable, highly-available log aggregation.
- **Advantage**: Unlike Elasticsearch, Loki only indexes labels (not full text), making it lightweight and cost-effective.

#### Prometheus (Metrics)
- **Purpose**: Time-series metrics storage and alerting.
- **Standard**: Industry standard for metrics, with native multi-dimensional data models and query language (PromQL).

### 3. Visualization Layer

#### Grafana (Dashboards)
- **Purpose**: Unified dashboards for logs, metrics, and alerts. Provides a single pane of glass for heterogeneous data sources.

### 4. System Metrics

#### node_exporter (Host Metrics)
- **Purpose**: Exposes Linux system metrics (CPU, memory, disk, network) in Prometheus-compatible format.

-----

## Configuration

### Step 1: Log4j2 Configuration for JSON Output

Update Wikantik's Log4j2 configuration to output JSON for machine parsing.

  - **File**: `/var/jspwiki/log4j2.properties`

```properties
status = warn
name = jspwiki-log4j2-observability

# Console appender for container environments
appenders = console, rolling

appender.console.type = Console
appender.console.name = Console
appender.console.layout.type = JsonTemplateLayout
appender.console.layout.eventTemplateUri = classpath:EcsLayout.json

# Rolling file appender with JSON format
appender.rolling.type = RollingFile
appender.rolling.name = RollingFile
appender.rolling.fileName = /var/log/jspwiki/jspwiki.log
appender.rolling.filePattern = /var/log/jspwiki/jspwiki-%d{yyyy-MM-dd}-%i.log.gz
appender.rolling.layout.type = JsonTemplateLayout
appender.rolling.layout.eventTemplateUri = classpath:EcsLayout.json
appender.rolling.policies.type = Policies
appender.rolling.policies.time.type = TimeBasedTriggeringPolicy
appender.rolling.policies.time.interval = 1
appender.rolling.policies.size.type = SizeBasedTriggeringPolicy
appender.rolling.policies.size.size = 50MB
appender.rolling.strategy.type = DefaultRolloverStrategy
appender.rolling.strategy.max = 14

rootLogger.level = info
rootLogger.appenderRef.console.ref = Console
rootLogger.appenderRef.rolling.ref = RollingFile

# Specific loggers for key components
logger.wiki.name = com.wikantik
logger.wiki.level = info

logger.auth.name = com.wikantik.auth
logger.auth.level = info

logger.pages.name = com.wikantik.pages
logger.pages.level = info
```

### Step 2: Tomcat Access Log Configuration

Configure Tomcat to output access logs in a parseable format, including Cloudflare headers for geographic data.

  - **File**: `tomcat/conf/server.xml` (inside `<Host>` element)

```xml
<Valve className="org.apache.catalina.valves.AccessLogValve"
       directory="/var/log/tomcat"
       prefix="access"
       suffix=".log"
       pattern="%{CF-Connecting-IP}i %l %u %t &quot;%r&quot; %s %b %D %{CF-IPCountry}i %{User-Agent}i %{Referer}i" />
```

### Step 3: Promtail Configuration

  - **File**: `/etc/promtail/promtail.yaml`

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /var/lib/promtail/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: jspwiki
    static_configs:
      - targets:
          - localhost
        labels:
          job: jspwiki
          app: jspwiki
          path: /var/log/jspwiki/*.log
    pipeline_stages:
      - json:
          expressions:
            level: level
            logger: logger_name
            message: message
            timestamp: "@timestamp"
            thread: thread_name
      - labels:
          level:
          logger:
      - timestamp:
          source: timestamp
          format: RFC3339Nano
```

-----

## Alerting Rules

### Prometheus Alert Rules

  - **File**: `/etc/prometheus/rules/jspwiki-alerts.yml`

```yaml
groups:
  - name: jspwiki
    rules:
      - alert: DiskSpaceLow
        expr: (1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100 > 80
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Disk space is running low"
          description: "Disk usage is {{ $value }}%"

      - alert: HighMemoryUsage
        expr: (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100 > 90
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High memory usage"
          description: "Memory usage is {{ $value }}%"
```

-----

## Future Enhancements

1. **[Distributed Tracing](DistributedTracing)**: Implement OpenTelemetry and Jaeger for request tracing.
2. **JMX Metrics**: Export JVM internal metrics (GC time, heap usage) using `jmx_exporter`.
3. **Synthetic Monitoring**: Use Prometheus `blackbox_exporter` for pro-active uptime monitoring.

---
**See Also:**
- [High Availability](HighAvailability)
- [Distributed Systems Hub](DistributedSystemsHub)
- [Logging Architecture](LoggingConfig)
