---
type: article
tags:
- uncategorized
summary: JSPWiki Logging Configuration Guide
---
1. JSPWiki Logging Configuration Guide

  1. Understanding `jspwiki.use.external.logconfig`

    1. The Logging Stack

Your JSPWiki 3.0.2 uses this logging architecture:
```
Application Code → SLF4J API → log4j-slf4j-impl → Log4j2 Core
                              ↑
                   log4j-1.2-api (legacy bridge)
```

The JARs in your `/opt/tomcat/webapps/ROOT/WEB-INF/lib/`:
- `slf4j-api-2.0.17.jar` - Facade API
- `log4j-slf4j-impl-2.25.2.jar` - Routes SLF4J to Log4j2
- `log4j-api-2.25.2.jar` - Log4j2 API
- `log4j-core-2.25.2.jar` - Log4j2 implementation
- `log4j-1.2-api-2.25.2.jar` - Bridges old Log4j 1.x calls

    1. How `jspwiki.use.external.logconfig` Works

When JSPWiki starts, `WikiBootstrapServletContextListener.initWikiLoggingFramework()` reads this property:

  - When `false` (default):**
1. JSPWiki reads all properties from `jspwiki.properties` / `jspwiki-custom.properties`
2. It filters properties starting with: `appender`, `logger`, `rootLogger`, `filter`, `status`, `dest`, `name`, `properties`, `property`, or `log4j2`
3. These are fed into Log4j2's `PropertiesConfigurationFactory`
4. Log4j2 is reconfigured programmatically

  - When `true`:**
1. JSPWiki does nothing with logging configuration
2. Log4j2 uses its standard automatic configuration mechanism
3. It searches the classpath for: `log4j2-test.xml`, `log4j2.xml`, `log4j2.properties`
4. Or you can specify `-Dlog4j2.configurationFile=/path/to/config.xml`

---

  1. External XML Configuration Setup

Use a separate `log4j2.xml` file with full Log4j2 capabilities.

    1. Step 1: Set in jspwiki-custom.properties

```properties
jspwiki.use.external.logconfig = true
```

Also remove any existing broken logging lines (log4j.* properties are Log4j 1.x syntax and will be ignored).

    1. Step 2: Create /opt/tomcat/lib/log4j2.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" name="JSPWiki-Production">
    <Properties>
        <Property name="logDir">/var/log/jspwiki</Property>
        <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
    </Properties>

    <Appenders>
        <!-- Console for catalina.out -->
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%highlight{[%-5level]} %d{HH:mm:ss.SSS} [%t] %c{1.} - %msg%n"/>
        </Console>

        <!-- Main application log with daily rotation -->
        <RollingFile name="AppLog" fileName="${logDir}/jspwiki.log" filePattern="${logDir}/jspwiki-%d{yyyy-MM-dd}-%i.log.gz">
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
        <!-- JSPWiki core -->
        <Logger name="org.apache.wiki" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AppLog"/>
        </Logger>

        <!-- Security events (login, logout, access denied) -->
        <Logger name="SecurityLog" level="INFO" additivity="false">
            <AppenderRef ref="SecurityLog"/>
        </Logger>

        <!-- Page access tracking via WikiServlet -->
        <Logger name="org.apache.wiki.WikiServlet" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AccessLog"/>
        </Logger>

        <!-- Database connection issues -->
        <Logger name="org.apache.tomcat.dbcp" level="WARN"/>

        <!-- Lucene search indexing -->
        <Logger name="org.apache.wiki.search" level="INFO"/>

        <!-- Root: catch-all -->
        <Root level="WARN">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

    1. Step 3: Create the log directory

```bash
sudo mkdir -p /var/log/jspwiki
sudo chown tomcat:tomcat /var/log/jspwiki
```

    1. Step 4: Restart Tomcat

```bash
sudo systemctl restart tomcat
```

    1. Step 5: Verify

```bash
1. Check catalina.out for startup messages
tail -f /opt/tomcat/logs/catalina.out

1. Check new log files are created
ls -la /var/log/jspwiki/
```

---

  1. Customizing the Log Directory for Different Environments

The log directory is defined in the `<Properties>` section at the top of the XML file:

```xml
<Properties>
    <Property name="logDir">/var/log/jspwiki</Property>  <!-- Change this path -->
    <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
</Properties>
```

The `logDir` property is referenced throughout the config as `${logDir}`, so changing it in one place updates all appenders:

```xml
fileName="${logDir}/jspwiki.log"
filePattern="${logDir}/jspwiki-%d{yyyy-MM-dd}-%i.log.gz"
```

    1. Recommended Paths by Environment

| Environment | Path | Notes |
|-------------|------|-------|
| Production | `/var/log/jspwiki` | Standard Linux log location |
| Development | `/tmp/jspwiki-logs` | Easy access, cleared on reboot |
| Testing | `/opt/tomcat/logs/jspwiki` | Keeps logs with Tomcat |
| Portable | `${sys:catalina.base}/logs/jspwiki` | Relative to Tomcat install |
| Java temp | `${sys:java.io.tmpdir}/jspwiki` | Uses Java's temp directory |

    1. Using System Property Lookups

The `${sys:...}` syntax references Java system properties, making configs portable across environments:

| Lookup | Description | Example Value |
|--------|-------------|---------------|
| `${sys:catalina.base}` | Tomcat base directory | `/opt/tomcat` |
| `${sys:catalina.home}` | Tomcat home directory | `/opt/tomcat` |
| `${sys:java.io.tmpdir}` | Java temp directory | `/tmp` |
| `${sys:user.home}` | User home directory | `/home/tomcat` |

  - Example for development/testing:**

```xml
<Properties>
    <Property name="logDir">${sys:catalina.base}/logs/jspwiki</Property>
    <Property name="pattern">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n</Property>
</Properties>
```

    1. Environment Variables

You can also use environment variables with the `${env:...}` syntax:

```xml
<Property name="logDir">${env:JSPWIKI_LOG_DIR:-/var/log/jspwiki}</Property>
```

This uses `$JSPWIKI_LOG_DIR` if set, otherwise falls back to `/var/log/jspwiki`.

    1. Important Reminders

1. **Permissions**: Ensure the `tomcat` user has write permission to the chosen directory
2. **Create directory**: The directory must exist before Tomcat starts (Log4j2 won't create parent directories)
3. **Disk space**: Choose a location with adequate space for log rotation

```bash
1. Example setup for testing environment
sudo mkdir -p /opt/tomcat/logs/jspwiki
sudo chown tomcat:tomcat /opt/tomcat/logs/jspwiki
```

---

  1. Log4j2 Configuration Reference

    1. Log Levels (in order of severity)

| Level | Description |
|-------|-------------|
| TRACE | Finest-grained debug information |
| DEBUG | Detailed debug information |
| INFO  | General operational messages |
| WARN  | Warning conditions |
| ERROR | Error conditions |
| FATAL | Critical errors causing shutdown |

    1. Common Pattern Layout Tokens

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

    1. Useful JSPWiki Logger Names

| Logger | Purpose |
|--------|---------|
| `org.apache.wiki` | All JSPWiki classes |
| `org.apache.wiki.WikiServlet` | Page request handling |
| `org.apache.wiki.auth` | Authentication/authorization |
| `org.apache.wiki.search` | Lucene search indexing |
| `org.apache.wiki.providers` | Page/attachment providers |
| `SecurityLog` | Security audit events |
| `SpamLog` | Spam filter events |

---

  1. Advanced Features

    1. Hot Reload Configuration

Add `monitorInterval` to automatically reload config changes:

```xml
<Configuration status="WARN" monitorInterval="30">
```

    1. Async Logging (Performance)

Wrap appenders for non-blocking logging:

```xml
<Appenders>
    <RollingFile name="AppLogFile" .../>
    <Async name="AppLog">
        <AppenderRef ref="AppLogFile"/>
    </Async>
</Appenders>
```

    1. Syslog Appender (Centralized Logging)

```xml
<Syslog name="Syslog"
        host="logserver.example.com"
        port="514"
        protocol="UDP"
        facility="LOCAL0"
        appName="jspwiki"/>
```

    1. Filter Sensitive Data

```xml
<RollingFile name="AppLog" ...>
    <RegexFilter regex=".*password.*" onMatch="DENY" onMismatch="ACCEPT"/>
    <PatternLayout .../>
</RollingFile>
```

---

  1. Sources

- [JSPWiki Releases - Log4j2 configuration notes](https://github.com/apache/jspwiki/releases)
- [JSPWiki Dockerfile - External log config setup](https://github.com/apache/jspwiki/blob/master/Dockerfile)
- [Log4j2 Configuration Manual](https://logging.apache.org/log4j/2.x/manual/configuration.html)
- [External Log4j2 Configuration](https://gangmax.me/blog/2024/11/15/use-external-log4j2-configuration-file/)
- [JSPWiki jspwiki.properties defaults](https://github.com/apache/jspwiki/blob/master/jspwiki-main/src/main/resources/ini/jspwiki.properties)
