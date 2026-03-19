---
summary: JSPWiki Observability System Design
tags:
- uncategorized
type: article
---
1. JSPWiki Observability System Design

  1. Overview

This document describes an open-source observability stack for JSPWiki, leveraging the existing Log4j2 logging infrastructure. The design follows the three pillars of observability: **Logs**, **Metrics**, and **Traces**.

  1. Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Observability Architecture                          │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│     JSPWiki      │     │     Tomcat       │     │   System Host    │
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

  1. Components

    1. 1. Log Collection Layer

      1. Promtail (Log Shipper)
- **Purpose**: Collects logs from files and ships to Loki
- **Source**: https://grafana.com/oss/promtail/
- **License**: AGPL-3.0

Promtail watches log files, extracts labels, and pushes to Loki.

    1. 2. Storage Layer

      1. Loki (Log Aggregation)
- **Purpose**: Horizontally-scalable, highly-available log aggregation
- **Source**: https://grafana.com/oss/loki/
- **License**: AGPL-3.0
- **Why Loki**: Unlike Elasticsearch, Loki only indexes labels (not full text), making it lightweight and cost-effective

      1. Prometheus (Metrics)
- **Purpose**: Time-series metrics storage and alerting
- **Source**: https://prometheus.io/
- **License**: Apache-2.0
- **Why Prometheus**: Industry standard for metrics, excellent Grafana integration

    1. 3. Visualization Layer

      1. Grafana (Dashboards)
- **Purpose**: Unified dashboards for logs, metrics, and alerts
- **Source**: https://grafana.com/oss/grafana/
- **License**: AGPL-3.0

    1. 4. System Metrics

      1. node_exporter (Host Metrics)
- **Purpose**: Exposes Linux system metrics (CPU, memory, disk, network)
- **Source**: https://github.com/prometheus/node_exporter
- **License**: Apache-2.0

---

  1. Configuration

    1. Step 1: Log4j2 Configuration for JSON Output

Update JSPWiki's Log4j2 configuration to output JSON for machine parsing.

  - File**: `/var/jspwiki/log4j2.properties`

```properties
status = warn
name = jspwiki-log4j2-observability

1. Console appender for container environments
appenders = console, rolling

appender.console.type = Console
appender.console.name = Console
appender.console.layout.type = JsonTemplateLayout
appender.console.layout.eventTemplateUri = classpath:EcsLayout.json

1. Rolling file appender with JSON format
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

1. Specific loggers for key components
logger.wiki.name = org.apache.wiki
logger.wiki.level = info

logger.auth.name = org.apache.wiki.auth
logger.auth.level = info

logger.pages.name = org.apache.wiki.pages
logger.pages.level = info
```

  - Note**: Add log4j2 JSON template layout dependency to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-layout-template-json</artifactId>
    <version>${log4j2.version}</version>
</dependency>
```

    1. Step 2: Tomcat Access Log Configuration

Configure Tomcat to output access logs in a parseable format, including Cloudflare headers for geographic data.

  - File**: `tomcat/conf/server.xml` (inside `<Host>` element)

```xml
<Valve className="org.apache.catalina.valves.AccessLogValve"
       directory="/var/log/tomcat"
       prefix="access"
       suffix=".log"
       pattern="%{CF-Connecting-IP}i %l %u %t &quot;%r&quot; %s %b %D %{CF-IPCountry}i %{User-Agent}i %{Referer}i" />
```

Pattern fields:
- `%{CF-Connecting-IP}i` - Real client IP (from Cloudflare)
- `%{CF-IPCountry}i` - Country code (e.g., US, DE, GB) - **automatic from Cloudflare**
- `%t` - Time
- `%r` - Request line
- `%s` - Status code
- `%b` - Bytes sent
- `%D` - Request duration (ms)

  - Cloudflare Headers Available:**
| Header | Description |
|--------|-------------|
| `CF-Connecting-IP` | Real visitor IP address |
| `CF-IPCountry` | Two-letter country code (ISO 3166-1 alpha-2) |
| `CF-IPCity` | City name (Enterprise plan only) |
| `CF-IPContinent` | Continent code (Enterprise plan only) |

    1. Step 3: Promtail Configuration

  - File**: `/etc/promtail/promtail.yaml`

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /var/lib/promtail/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  # JSPWiki application logs (JSON format)
  - job_name: jspwiki
    static_configs:
      - targets:
          - localhost
        labels:
          job: jspwiki
          app: jspwiki
          **path**: /var/log/jspwiki/*.log
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

  # Tomcat access logs with Cloudflare geo data
  - job_name: tomcat-access
    static_configs:
      - targets:
          - localhost
        labels:
          job: tomcat
          app: jspwiki
          log_type: access
          **path**: /var/log/tomcat/access*.log
    pipeline_stages:
      # Parse access log with Cloudflare headers
      - regex:
          expression: '^(?P<client_ip>\S+) \S+ (?P<user>\S+) \[(?P<timestamp>[^\]]+)\] "(?P<method>\S+) (?P<path>\S+) (?P<protocol>\S+)" (?P<status>\d+) (?P<bytes>\S+) (?P<duration>\d+) (?P<country>\S+)'
      # Extract wiki page name from path
      - regex:
          source: path
          expression: '(?:Wiki\.jsp\?page=|/wiki/)(?P<page>[^&\s]+)'
      # Add labels for filtering and grouping
      - labels:
          method:
          status:
          country:
          page:
      # Create metrics from access logs
      - metrics:
          http_request_duration_ms:
            type: Histogram
            description: "HTTP request duration in milliseconds"
            source: duration
            config:
              buckets: [10, 50, 100, 250, 500, 1000, 2500, 5000]
          http_requests_by_country:
            type: Counter
            description: "HTTP requests by country"
            config:
              action: inc
          http_requests_by_page:
            type: Counter
            description: "HTTP requests by wiki page"
            config:
              action: inc

  # Tomcat catalina logs
  - job_name: tomcat-catalina
    static_configs:
      - targets:
          - localhost
        labels:
          job: tomcat
          app: jspwiki
          log_type: catalina
          **path**: /var/log/tomcat/catalina*.log
```

    1. Step 4: Prometheus Configuration

  - File**: `/etc/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          # - alertmanager:9093

rule_files:
  - "/etc/prometheus/rules/*.yml"

scrape_configs:
  # Prometheus self-monitoring
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  # Node exporter for system metrics
  - job_name: 'node'
    static_configs:
      - targets: ['localhost:9100']
    relabel_configs:
      - source_labels: [**address**]
        target_label: instance
        regex: '(.+):.*'
        replacement: '${1}'

  # Loki metrics
  - job_name: 'loki'
    static_configs:
      - targets: ['loki:3100']

  # JMX exporter for JVM metrics (optional)
  - job_name: 'jvm'
    static_configs:
      - targets: ['localhost:9404']
```

    1. Step 5: Loki Configuration

  - File**: `/etc/loki/loki.yaml`

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /var/lib/loki
  storage:
    filesystem:
      chunks_directory: /var/lib/loki/chunks
      rules_directory: /var/lib/loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

query_range:
  results_cache:
    cache:
      embedded_cache:
        enabled: true
        max_size_mb: 100

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

ruler:
  alertmanager_url: http://localhost:9093

limits_config:
  retention_period: 30d
  ingestion_rate_mb: 10
  ingestion_burst_size_mb: 20
```

    1. Step 6: Grafana Data Sources

  - File**: `/etc/grafana/provisioning/datasources/datasources.yaml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false

  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    editable: false
    jsonData:
      maxLines: 1000
```

---

  1. Installation (Ubuntu/Debian)

    1. Prerequisites

```bash
1. Create directories
sudo mkdir -p /var/log/jspwiki /var/log/tomcat
sudo mkdir -p /var/lib/loki /var/lib/promtail /var/lib/prometheus
sudo mkdir -p /etc/promtail /etc/loki /etc/prometheus

1. Install dependencies
sudo apt-get update
sudo apt-get install -y curl wget gnupg2 apt-transport-https
```

    1. Install Grafana

```bash
1. Add Grafana GPG key and repository
wget -q -O - https://apt.grafana.com/gpg.key | sudo apt-key add -
echo "deb https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list

sudo apt-get update
sudo apt-get install -y grafana

sudo systemctl enable grafana-server
sudo systemctl start grafana-server
```

    1. Install Loki and Promtail

```bash
1. Download latest releases
LOKI_VERSION="2.9.3"
cd /tmp

1. Loki
wget https://github.com/grafana/loki/releases/download/v${LOKI_VERSION}/loki-linux-amd64.zip
unzip loki-linux-amd64.zip
sudo mv loki-linux-amd64 /usr/local/bin/loki

1. Promtail
wget https://github.com/grafana/loki/releases/download/v${LOKI_VERSION}/promtail-linux-amd64.zip
unzip promtail-linux-amd64.zip
sudo mv promtail-linux-amd64 /usr/local/bin/promtail
```

    1. Install Prometheus and node_exporter

```bash
PROMETHEUS_VERSION="2.48.1"
NODE_EXPORTER_VERSION="1.7.0"

1. Prometheus
wget https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS_VERSION}/prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz
tar xzf prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz
sudo mv prometheus-${PROMETHEUS_VERSION}.linux-amd64/prometheus /usr/local/bin/
sudo mv prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool /usr/local/bin/

1. node_exporter
wget https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER_VERSION}/node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64.tar.gz
tar xzf node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64.tar.gz
sudo mv node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64/node_exporter /usr/local/bin/
```

    1. Create Systemd Services

  - File**: `/etc/systemd/system/loki.service`

```ini
[Unit](Unit)
Description=Loki Log Aggregation System
After=network.target

[Service](Service)
Type=simple
User=loki
ExecStart=/usr/local/bin/loki -config.file=/etc/loki/loki.yaml
Restart=on-failure
RestartSec=5

[Install](Install)
WantedBy=multi-user.target
```

  - File**: `/etc/systemd/system/promtail.service`

```ini
[Unit](Unit)
Description=Promtail Log Collector
After=network.target

[Service](Service)
Type=simple
User=promtail
ExecStart=/usr/local/bin/promtail -config.file=/etc/promtail/promtail.yaml
Restart=on-failure
RestartSec=5

[Install](Install)
WantedBy=multi-user.target
```

  - File**: `/etc/systemd/system/prometheus.service`

```ini
[Unit](Unit)
Description=Prometheus Monitoring System
After=network.target

[Service](Service)
Type=simple
User=prometheus
ExecStart=/usr/local/bin/prometheus \
    --config.file=/etc/prometheus/prometheus.yml \
    --storage.tsdb.path=/var/lib/prometheus \
    --storage.tsdb.retention.time=30d
Restart=on-failure
RestartSec=5

[Install](Install)
WantedBy=multi-user.target
```

  - File**: `/etc/systemd/system/node_exporter.service`

```ini
[Unit](Unit)
Description=Node Exporter
After=network.target

[Service](Service)
Type=simple
User=node_exporter
ExecStart=/usr/local/bin/node_exporter
Restart=on-failure
RestartSec=5

[Install](Install)
WantedBy=multi-user.target
```

    1. Create Users and Set Permissions

```bash
1. Create service users
sudo useradd --no-create-home --shell /bin/false loki
sudo useradd --no-create-home --shell /bin/false promtail
sudo useradd --no-create-home --shell /bin/false prometheus
sudo useradd --no-create-home --shell /bin/false node_exporter

1. Set ownership
sudo chown -R loki:loki /var/lib/loki /etc/loki
sudo chown -R promtail:promtail /var/lib/promtail /etc/promtail
sudo chown -R prometheus:prometheus /var/lib/prometheus /etc/prometheus

1. Allow promtail to read log files
sudo usermod -a -G adm promtail
```

    1. Start Services

```bash
sudo systemctl daemon-reload

sudo systemctl enable --now loki
sudo systemctl enable --now promtail
sudo systemctl enable --now prometheus
sudo systemctl enable --now node_exporter
sudo systemctl enable --now grafana-server
```

---

  1. Grafana Dashboards

    1. Access Grafana

- URL: http://your-server:3000
- Default credentials: admin / admin

    1. Recommended Dashboards

Import these community dashboards from grafana.com:

1. **Node Exporter Full** (ID: 1860)
   - System metrics: CPU, memory, disk, network

2. **Loki Logs** (ID: 13639)
   - Log exploration and searching

3. **JVM Micrometer** (ID: 4701)
   - JVM metrics (if using JMX exporter)

    1. Custom JSPWiki Dashboard

Create a custom dashboard with these panels:

      1. Log Panels (Loki)

1. **Error Rate**
   ```logql
   sum(rate({app="jspwiki"} |= "ERROR" [5m]))
   ```

2. **Authentication Events**
   ```logql
   {app="jspwiki", logger=~".*auth.*"}
   ```

3. **Page Access Frequency**
   ```logql
   {job="tomcat", log_type="access"} |~ "Wiki.jsp"
   ```

4. **Recent Errors**
   ```logql
   {app="jspwiki"} | json | level="ERROR"
   ```

      1. Traffic Analytics Panels (Loki + Cloudflare Geo Data)

These panels provide visibility into article traffic and geographic distribution of visitors.

1. **Top Wiki Pages by Traffic (Table)**
   ```logql
   topk(20, sum by (page) (count_over_time({job="tomcat", log_type="access"} | regexp `Wiki\.jsp\?page=(?P<page>\S+)` [24h])))
   ```

2. **Traffic by Country (Pie Chart or World Map)**
   ```logql
   sum by (country) (count_over_time({job="tomcat", log_type="access"} [24h]))
   ```
   *Use Grafana's Geomap panel with country code mapping for a world map visualization.*

3. **Top Pages per Country (Table)**
   ```logql
   topk(10, sum by (country, page) (count_over_time({job="tomcat", log_type="access"} [24h])))
   ```

4. **Traffic Over Time by Country (Time Series)**
   ```logql
   sum by (country) (rate({job="tomcat", log_type="access"} [5m]))
   ```

5. **Page Traffic Trend (Time Series)**
   ```logql
   # Replace "Main" with any page name to track
   count_over_time({job="tomcat", log_type="access"} |~ "page=Main" [1h])
   ```

6. **Unique Countries Today (Stat Panel)**
   ```logql
   count(sum by (country) (count_over_time({job="tomcat", log_type="access"} [24h])))
   ```

7. **Geographic Traffic Heatmap**

   For a world map visualization in Grafana:
   - Panel Type: **Geomap**
   - Data Source: Loki
   - Query: `sum by (country) (count_over_time({job="tomcat", log_type="access"} [24h]()))`
   - Field mapping: Map `country` field to ISO 3166-1 alpha-2 country codes
   - Layer: **Markers** with size based on request count

      1. Metric Panels (Prometheus)

1. **HTTP Request Rate**
   ```promql
   rate(promtail_custom_http_request_duration_ms_count[5m])
   ```

2. **Request Duration (p95)**
   ```promql
   histogram_quantile(0.95, rate(promtail_custom_http_request_duration_ms_bucket[5m]))
   ```

3. **Requests by Country (from Promtail metrics)**
   ```promql
   sum by (country) (rate(promtail_custom_http_requests_by_country[5m]))
   ```

4. **Requests by Page (from Promtail metrics)**
   ```promql
   topk(10, sum by (page) (rate(promtail_custom_http_requests_by_page[5m])))
   ```

5. **System Resources**
   ```promql
   # CPU Usage
   100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)

   # Memory Usage
   (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100

   # Disk Usage
   (1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100
   ```

---

  1. Alerting Rules

    1. Prometheus Alert Rules

  - File**: `/etc/prometheus/rules/jspwiki-alerts.yml`

```yaml
groups:
  - name: jspwiki
    rules:
      # High error rate
      - alert: JSPWikiHighErrorRate
        expr: sum(rate({app="jspwiki"} |= "ERROR" [5m])) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High error rate in JSPWiki logs"
          description: "Error rate is {{ $value }} errors per second"

      # Disk space warning
      - alert: DiskSpaceLow
        expr: (1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100 > 80
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Disk space is running low"
          description: "Disk usage is {{ $value }}%"

      # High memory usage
      - alert: HighMemoryUsage
        expr: (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100 > 90
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High memory usage"
          description: "Memory usage is {{ $value }}%"

      # Service down
      - alert: JSPWikiDown
        expr: up{job="jvm"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "JSPWiki service is down"
          description: "The JSPWiki JVM exporter is not responding"
```

---

  1. Docker Compose Alternative

For easier deployment, here's a Docker Compose configuration:

  - File**: `observability/docker-compose.yml`

```yaml
version: '3.8'

services:
  loki:
    image: grafana/loki:2.9.3
    ports:
      - "3100:3100"
    volumes:
      - ./loki:/etc/loki
      - loki-data:/var/lib/loki
    command: -config.file=/etc/loki/loki.yaml
    restart: unless-stopped

  promtail:
    image: grafana/promtail:2.9.3
    volumes:
      - ./promtail:/etc/promtail
      - /var/log:/var/log:ro
      - promtail-positions:/var/lib/promtail
    command: -config.file=/etc/promtail/promtail.yaml
    restart: unless-stopped
    depends_on:
      - loki

  prometheus:
    image: prom/prometheus:v2.48.1
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus:/etc/prometheus
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--storage.tsdb.retention.time=30d'
    restart: unless-stopped

  node-exporter:
    image: prom/node-exporter:v1.7.0
    ports:
      - "9100:9100"
    volumes:
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/rootfs:ro
    command:
      - '--path.procfs=/host/proc'
      - '--path.sysfs=/host/sys'
      - '--path.rootfs=/rootfs'
    restart: unless-stopped

  grafana:
    image: grafana/grafana:10.2.3
    ports:
      - "3000:3000"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
      - grafana-data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_USERS_ALLOW_SIGN_UP=false
    restart: unless-stopped
    depends_on:
      - loki
      - prometheus

volumes:
  loki-data:
  promtail-positions:
  prometheus-data:
  grafana-data:
```

---

  1. Verification Checklist

After installation, verify each component:

```bash
1. Check service status
sudo systemctl status loki promtail prometheus node_exporter grafana-server

1. Verify Loki is receiving logs
curl http://localhost:3100/ready

1. Verify Prometheus targets
curl http://localhost:9090/api/v1/targets

1. Check node_exporter metrics
curl http://localhost:9100/metrics | head -20

1. Check Grafana
curl http://localhost:3000/api/health
```

---

  1. Summary

| Component | Port | Purpose |
|-----------|------|---------|
| Grafana | 3000 | Dashboards and visualization |
| Prometheus | 9090 | Metrics storage and alerting |
| Loki | 3100 | Log aggregation |
| Promtail | 9080 | Log collection agent |
| node_exporter | 9100 | System metrics |

    1. Data Flow

1. **Logs**: JSPWiki (Log4j2 JSON) → Promtail → Loki → Grafana
2. **Access Logs**: Tomcat → Promtail → Loki → Grafana
3. **Metrics**: node_exporter → Prometheus → Grafana

    1. Traffic Analytics Capabilities

With this design, you can answer:

| Question | How |
|----------|-----|
| Which wiki pages get the most traffic? | Promtail extracts `page` label from access logs |
| Where are my visitors located? | Cloudflare's `CF-IPCountry` header provides country codes |
| What's trending this week? | Time-series queries on page access counts |
| Which countries read specific pages? | Cross-reference `country` and `page` labels |
| What's my peak traffic time? | Time-series visualization of request rates |

  - Geographic Data Source**: Cloudflare automatically adds the `CF-IPCountry` header to every request, providing accurate country-level geolocation without any additional GeoIP database or service required.

    1. Retention

- Logs: 30 days (configurable in Loki)
- Metrics: 30 days (configurable in Prometheus)

---

  1. Future Enhancements

1. **Distributed Tracing**: Add Jaeger or Tempo for request tracing
2. **JMX Metrics**: Add jmx_exporter for JVM metrics
3. **Synthetic Monitoring**: Add blackbox_exporter for uptime monitoring
4. **Alertmanager**: Configure alert routing (email, Slack, PagerDuty)
5. **Log-based Metrics**: Extract custom metrics from logs in Promtail

---

- Document created: November 2024*
