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

### Log4j2 configuration file locations

| Environment | Config file path |
|-------------|-----------------|
| Bare-metal local Tomcat | `tomcat/tomcat-11/lib/log4j2.xml` |
| Container (`${CATALINA_HOME}`) | `/usr/local/tomcat/lib/log4j2.xml` |
| Container (baked config) | `docker/config/log4j2-docker.xml` |

For the bare-metal install, `bin/deploy-local.sh` copies
`wikantik-war/src/main/config/tomcat/log4j2-local.xml.template` to
`tomcat/tomcat-11/lib/log4j2.xml` on first deploy (the file is preserved on
subsequent deploys unless you delete it). The template uses a portable path,
`${sys:catalina.base}/logs/wikantik`, so it works regardless of the absolute
location of the Tomcat directory.

The container build bakes `docker/config/log4j2-docker.xml` into the image;
container operators can volume-mount a custom `log4j2.xml` into
`/usr/local/tomcat/lib/` to override it.

### How `wikantik.use.external.logconfig` works

**When `false` (default):**
1. Wikantik reads all properties from `wikantik.properties` / `wikantik-custom.properties`
2. It filters properties starting with: `appender`, `logger`, `rootLogger`, `filter`,
   `status`, `dest`, `name`, `properties`, `property`, or `log4j2`
3. These are fed into Log4j2's `PropertiesConfigurationFactory`
4. Log4j2 is reconfigured programmatically

**When `true`:**
1. Wikantik does nothing with logging configuration
2. Log4j2 uses its standard automatic configuration mechanism
3. It searches the classpath for: `log4j2-test.xml`, `log4j2.xml`, `log4j2.properties`
4. Or you can specify `-Dlog4j2.configurationFile=/path/to/config.xml`

Because `log4j2.xml` is placed in `tomcat/tomcat-11/lib/` (bare-metal) or
`/usr/local/tomcat/lib/` (container), it is on the server classloader path and
Log4j2 picks it up automatically when `wikantik.use.external.logconfig = true`.

---

## External XML Configuration Setup

Use a separate `log4j2.xml` file with full Log4j2 capabilities.

### Step 1: Set in wikantik-custom.properties

```properties
wikantik.use.external.logconfig = true
```

Also remove any existing broken logging lines (`log4j.*` properties are Log4j 1.x
syntax and will be ignored).

### Step 2: Override the auto-generated config

`bin/deploy-local.sh` places the auto-generated config at:
- **Bare-metal:** `tomcat/tomcat-11/lib/log4j2.xml`
- **Container:** `/usr/local/tomcat/lib/log4j2.xml`

To customize logging, edit that file in place (it is not overwritten by subsequent
`bin/redeploy.sh` runs). If you need to start from the template again, copy it:

```bash
# Bare-metal: restore from template
cp wikantik-war/src/main/config/tomcat/log4j2-local.xml.template \
   tomcat/tomcat-11/lib/log4j2.xml
```

The template uses `${sys:catalina.base}/logs/wikantik` as the log directory,
which resolves correctly regardless of the absolute install path.

### Example XML configuration

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" name="Wikantik-Production" monitorInterval="30">
    <Properties>
        <!-- Portable path — resolves to tomcat/tomcat-11/logs/wikantik on bare-metal -->
        <Property name="logDir">${sys:catalina.base}/logs/wikantik</Property>
        <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
    </Properties>

    <Appenders>
        <!-- Console for catalina.out -->
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%highlight{[%-5level]} %d{HH:mm:ss.SSS} [%t] %c{1.} - %msg%n"/>
        </Console>

        <!-- Main application log with daily rotation -->
        <RollingFile name="AppLog"
                     fileName="${logDir}/wikantik.log"
                     filePattern="${logDir}/wikantik-%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="${pattern}"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1"/>
                <SizeBasedTriggeringPolicy size="50MB"/>
            </Policies>
            <DefaultRolloverStrategy max="30"/>
        </RollingFile>

        <!-- Security audit log -->
        <RollingFile name="SecurityLog"
                     fileName="${logDir}/security.log"
                     filePattern="${logDir}/security-%d{yyyy-MM-dd}.log.gz">
            <PatternLayout pattern="%d{ISO8601} %level %msg%n"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
            <DefaultRolloverStrategy max="90"/>
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

### Step 3: Restart Tomcat

```bash
# Bare-metal
tomcat/tomcat-11/bin/shutdown.sh && tomcat/tomcat-11/bin/startup.sh

# Or use the fast redeploy path (also rotates catalina.out)
bin/redeploy.sh
```

### Step 4: Verify

```bash
# Check catalina.out for startup messages (bare-metal)
tail -f tomcat/tomcat-11/logs/catalina.out

# Check new log files are created
ls -la tomcat/tomcat-11/logs/wikantik/
```

---

## Path reference by environment

| Environment | Tomcat base | Log directory | Config file |
|-------------|-------------|---------------|-------------|
| Bare-metal local | `tomcat/tomcat-11/` | `tomcat/tomcat-11/logs/wikantik/` | `tomcat/tomcat-11/lib/log4j2.xml` |
| Container | `/usr/local/tomcat/` | `/usr/local/tomcat/logs/wikantik/` (or volume) | `/usr/local/tomcat/lib/log4j2.xml` |

The `${sys:catalina.base}` lookup resolves to the Tomcat base directory at runtime,
so the same config file works in both environments without editing.

---

## Customizing the log directory

The `logDir` property is referenced throughout the config as `${logDir}`, so changing
it in one place updates all appenders:

```xml
<Properties>
    <!-- Options: -->
    <!-- Portable (recommended): -->
    <Property name="logDir">${sys:catalina.base}/logs/wikantik</Property>
    <!-- Standard Linux system log location: -->
    <!-- <Property name="logDir">/var/log/wikantik</Property> -->
    <!-- Environment variable with fallback: -->
    <!-- <Property name="logDir">${env:WIKANTIK_LOG_DIR:-/var/log/wikantik}</Property> -->
</Properties>
```

### System property lookups

| Lookup | Description | Example (bare-metal local) |
|--------|-------------|---------------------------|
| `${sys:catalina.base}` | Tomcat base directory | `<repo>/tomcat/tomcat-11` |
| `${sys:catalina.home}` | Tomcat home directory | `<repo>/tomcat/tomcat-11` |
| `${sys:java.io.tmpdir}` | Java temp directory | `/tmp` |
| `${sys:user.home}` | User home directory | `/home/jakefear` |

**Important:** Ensure the Tomcat process has write permission to the chosen log
directory. `bin/deploy-local.sh` creates `tomcat/tomcat-11/logs/wikantik/`
automatically.

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
| `%X{key}` | Thread context map (MDC) value — used by RequestCorrelationFilter for `X-Request-ID` |
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

Add `monitorInterval` to automatically reload config changes (value in seconds):

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

- The `log4j2.xml` template deployed by `bin/deploy-local.sh`:
  `wikantik-war/src/main/config/tomcat/log4j2-local.xml.template`
- The container baked config:
  `docker/config/log4j2-docker.xml`
- The canonical `wikantik.properties` defaults:
  `wikantik-main/src/main/resources/ini/wikantik.properties`
- [Log4j2 Configuration Manual](https://logging.apache.org/log4j/2.x/manual/configuration.html)
