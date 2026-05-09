# Wikantik Logging Configuration Guide

## Understanding `wikantik.use.external.logconfig`

### The Logging Stack

Wikantik routes all logging through Log4j2:

```
Application Code → SLF4J API → log4j-slf4j-impl → Log4j2 Core
                              ↑
                   log4j-1.2-api (legacy bridge for transitive deps)
```

The relevant JARs ship in `WEB-INF/lib/` of the deployed WAR; exact
versions are pinned in `wikantik-bom/pom.xml`:

- `slf4j-api` — Facade API
- `log4j-slf4j-impl` — Routes SLF4J to Log4j2
- `log4j-api`, `log4j-core` — Log4j2 API + implementation
- `log4j-1.2-api` — Bridges legacy Log4j 1.x calls from transitive deps

### How `wikantik.use.external.logconfig` Works

When Wikantik starts, the bootstrap listener reads this property:

**When `false` (default):**
1. Wikantik reads all properties from `wikantik.properties` / `wikantik-custom.properties`
2. It filters properties starting with: `appender`, `logger`, `rootLogger`, `filter`, `status`, `dest`, `name`, `properties`, `property`, or `log4j2`
3. These are fed into Log4j2's `PropertiesConfigurationFactory`
4. Log4j2 is reconfigured programmatically

**When `true`:**
1. Wikantik does nothing with logging configuration
2. Log4j2 uses its standard automatic configuration mechanism
3. It searches the classpath for: `log4j2-test.xml`, `log4j2.xml`, `log4j2.properties`
4. Or you can specify `-Dlog4j2.configurationFile=/path/to/config.xml`

---

## External XML Configuration Setup

Use a separate `log4j2.xml` file with full Log4j2 capabilities.

### Step 1: Set in wikantik-custom.properties

```properties
wikantik.use.external.logconfig = true
```

Also remove any existing broken logging lines (log4j.* properties are Log4j 1.x syntax and will be ignored).

### Step 2: Create /opt/tomcat/lib/log4j2.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" name="Wikantik-Production">
    <Properties>
        <Property name="logDir">/var/log/wikantik</Property>
        <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
    </Properties>

    <Appenders>
        <!-- Console for catalina.out -->
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%highlight{[%-5level]} %d{HH:mm:ss.SSS} [%t] %c{1.} - %msg%n"/>
        </Console>

        <!-- Main application log with daily rotation -->
        <RollingFile name="AppLog" fileName="${logDir}/wikantik.log" filePattern="${logDir}/wikantik-%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="${pattern}"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1"/>
                <SizeBasedTriggeringPolicy size="50MB"/>
            </Policies>
            <DefaultRolloverStrategy max="30"/>
        </RollingFile>

        <!-- Security audit log -->
        <RollingFile name="SecurityLog" fileName="${logDir}/security.log" filePattern="${logDir}/security-%d{yyyy-MM-dd}.log.gz">
            <PatternLayout pattern="%d{ISO8601} %level %msg%n"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
            <DefaultRolloverStrategy max="90"/>
        </RollingFile>

        <!-- Access log for page views -->
        <RollingFile name="AccessLog" fileName="${logDir}/access.log" filePattern="${logDir}/access-%d{yyyy-MM-dd}.log.gz">
            <PatternLayout pattern="%d{ISO8601} %msg%n"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
        </RollingFile>
    </Appenders>

    <Loggers>
        <!-- Wikantik core -->
        <Logger name="com.wikantik" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AppLog"/>
        </Logger>

        <!-- Security events (login, logout, access denied) -->
        <Logger name="SecurityLog" level="INFO" additivity="false">
            <AppenderRef ref="SecurityLog"/>
        </Logger>

        <!-- Page access tracking via WikiServlet -->
        <Logger name="com.wikantik.WikiServlet" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AccessLog"/>
        </Logger>

        <!-- Database connection issues -->
        <Logger name="org.apache.tomcat.dbcp" level="WARN"/>

        <!-- Lucene search indexing -->
        <Logger name="com.wikantik.search" level="INFO"/>

        <!-- Root: catch-all -->
        <Root level="WARN">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

### Step 3: Create the log directory

```bash
sudo mkdir -p /var/log/wikantik
sudo chown tomcat:tomcat /var/log/wikantik
```

### Step 4: Restart Tomcat

```bash
sudo systemctl restart tomcat
```

### Step 5: Verify

```bash
# Check catalina.out for startup messages
tail -f /opt/tomcat/logs/catalina.out

# Check new log files are created
ls -la /var/log/wikantik/
```

---

## Customizing the Log Directory for Different Environments

The log directory is defined in the `<Properties>` section at the top of the XML file:

```xml
<Properties>
    <Property name="logDir">/var/log/wikantik</Property>  <!-- Change this path -->
    <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
</Properties>
```

The `logDir` property is referenced throughout the config as `${logDir}`, so changing it in one place updates all appenders:

```xml
fileName="${logDir}/wikantik.log"
filePattern="${logDir}/wikantik-%d{yyyy-MM-dd}-%i.log.gz"
```

### Recommended Paths by Environment

| Environment | Path | Notes |
|-------------|------|-------|
| Production | `/var/log/wikantik` | Standard Linux log location |
| Development | `/tmp/wikantik-logs` | Easy access, cleared on reboot |
| Testing | `/opt/tomcat/logs/wikantik` | Keeps logs with Tomcat |
| Portable | `${sys:catalina.base}/logs/wikantik` | Relative to Tomcat install |
| Java temp | `${sys:java.io.tmpdir}/wikantik` | Uses Java's temp directory |

### Using System Property Lookups

The `${sys:...}` syntax references Java system properties, making configs portable across environments:

| Lookup | Description | Example Value |
|--------|-------------|---------------|
| `${sys:catalina.base}` | Tomcat base directory | `/opt/tomcat` |
| `${sys:catalina.home}` | Tomcat home directory | `/opt/tomcat` |
| `${sys:java.io.tmpdir}` | Java temp directory | `/tmp` |
| `${sys:user.home}` | User home directory | `/home/tomcat` |

**Example for development/testing:**

```xml
<Properties>
    <Property name="logDir">${sys:catalina.base}/logs/wikantik</Property>
    <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
</Properties>
```

### Environment Variables

You can also use environment variables with the `${env:...}` syntax:

```xml
<Property name="logDir">${env:WIKANTIK_LOG_DIR:-/var/log/wikantik}</Property>
```

This uses `$WIKANTIK_LOG_DIR` if set, otherwise falls back to `/var/log/wikantik`.

### Important Reminders

1. **Permissions**: Ensure the `tomcat` user has write permission to the chosen directory
2. **Create directory**: The directory must exist before Tomcat starts (Log4j2 won't create parent directories)
3. **Disk space**: Choose a location with adequate space for log rotation

```bash
# Example setup for testing environment
sudo mkdir -p /opt/tomcat/logs/wikantik
sudo chown tomcat:tomcat /opt/tomcat/logs/wikantik
```

---

## Log4j2 Configuration Reference

### Log Levels (in order of severity)

| Level | Description |
|-------|-------------|
| TRACE | Finest-grained debug information |
| DEBUG | Detailed debug information |
| INFO  | General operational messages |
| WARN  | Warning conditions |
| ERROR | Error conditions |
| FATAL | Critical errors causing shutdown |

### Common Pattern Layout Tokens

| Token | Description |
|-------|-------------|
| `%d{pattern}` | Date/time with specified format |
| `%t` | Thread name |
| `%p` or `%level` | Log level |
| `%c{n}` | Logger name (n = precision) |
| `%C{n}` | Class name |
| `%M` | Method name |
| `%L` | Line number |
| `%m` or `%msg` | Log message |
| `%n` | Newline |
| `%x` | Thread context (NDC) |
| `%X{key}` | Thread context map (MDC) value |
| `%highlight{pattern}` | ANSI color highlighting |

### Useful Wikantik Logger Names

| Logger | Purpose |
|--------|---------|
| `com.wikantik` | All Wikantik classes |
| `com.wikantik.WikiServlet` | Page request handling |
| `com.wikantik.auth` | Authentication/authorization |
| `com.wikantik.search` | Lucene search indexing |
| `com.wikantik.providers` | Page/attachment providers |
| `SecurityLog` | Security audit events |
| `SpamLog` | Spam filter events |

---

## Advanced Features

### Hot Reload Configuration

Add `monitorInterval` to automatically reload config changes:

```xml
<Configuration status="WARN" monitorInterval="30">
```

### Async Logging (Performance)

Wrap appenders for non-blocking logging:

```xml
<Appenders>
    <RollingFile name="AppLogFile" .../>
    <Async name="AppLog">
        <AppenderRef ref="AppLogFile"/>
    </Async>
</Appenders>
```

### Syslog Appender (Centralized Logging)

```xml
<Syslog name="Syslog"
        host="logserver.example.com"
        port="514"
        protocol="UDP"
        facility="LOCAL0"
        appName="wikantik"/>
```

### Filter Sensitive Data

```xml
<RollingFile name="AppLog" ...>
    <RegexFilter regex=".*password.*" onMatch="DENY" onMismatch="ACCEPT"/>
    <PatternLayout .../>
</RollingFile>
```

---

## Sources

- The canonical `log4j2.xml.template` shipped with `bin/deploy-local.sh`:
  `wikantik-war/src/main/config/tomcat/log4j2-local.xml.template`
- The canonical `wikantik.properties` defaults:
  `wikantik-main/src/main/resources/ini/wikantik.properties`
- [Log4j2 Configuration Manual](https://logging.apache.org/log4j/2.x/manual/configuration.html)
- [External Log4j2 Configuration](https://gangmax.me/blog/2024/11/15/use-external-log4j2-configuration-file/)
