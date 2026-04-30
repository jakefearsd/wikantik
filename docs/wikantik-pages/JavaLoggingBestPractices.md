---
canonical_id: 01KQ0P44RA8J70Y4M6CMRBMBKD
title: Java Logging Best Practices
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How to do Java logging well — SLF4J as the standard, structured logging,
  the levels that actually matter, and the patterns that survive operational scale.
tags:
- java
- logging
- slf4j
- observability
- structured-logging
related:
- JavaExceptionHandlingPatterns
- DebuggingStrategies
- CodeDocumentationBestPractices
- SpringBootFundamentals
hubs:
- JavaHub
---
# Java Logging Best Practices

Logging is the most-used feature of any Java application and one of the most consistently mishandled. Bad logging — too much, too little, unstructured, expensive — costs production time when nobody can debug an issue or when log volume drives infrastructure cost.

This page covers the working patterns for Java logging at scale.

## Use SLF4J

SLF4J is the de facto standard logging facade for Java. Library code should depend on `slf4j-api`; the application picks the implementation (Logback, Log4j2).

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public void process(Order order) {
        log.info("Processing order {}", order.id());
    }
}
```

Key points:
- `Logger` is `static final` — one logger per class
- The `LoggerFactory.getLogger(Class)` form is conventional
- Use parameterized messages (`{}`), not string concatenation

## Parameterized messages

```java
// Wrong: string concat happens always
log.debug("Processing order " + order.id());

// Right: format only happens if debug is enabled
log.debug("Processing order {}", order.id());
```

The parameterized form skips the format work when the level is disabled. For DEBUG/TRACE messages, this matters at scale.

## Levels that actually matter

The SLF4J levels: TRACE, DEBUG, INFO, WARN, ERROR.

- **ERROR**: something failed; needs operator attention. Pages should be triggered by these in production.
- **WARN**: something unexpected but recoverable. Worth investigating in aggregate; not page-worthy individually.
- **INFO**: routine state changes. Major lifecycle events, configuration loaded, requests received (sometimes).
- **DEBUG**: details for development troubleshooting. Off in production by default.
- **TRACE**: very fine detail. Almost never on in production.

The most common failure: logging too much at INFO level. INFO should be sparse — major events, not every step.

## Structured logging

For modern observability, structured logs (JSON-formatted with key-value fields) beat free-form text. They're queryable; they integrate with log aggregation systems.

With Logback + JSON encoder:

```java
log.atInfo()
    .addKeyValue("orderId", order.id())
    .addKeyValue("amount", order.amount())
    .log("Order processed");
```

The output is JSON; tools like Datadog, Splunk, ELK can index by key.

For typical use, MDC (Mapped Diagnostic Context) provides per-thread context that's added to every log message:

```java
MDC.put("requestId", requestId);
try {
    // work
} finally {
    MDC.clear();
}
```

Combined with structured logging, every log message in a request is correlated by `requestId`. Essential for distributed tracing.

## Logging exceptions correctly

```java
try {
    process(order);
} catch (ProcessingException e) {
    log.error("Failed to process order {}", order.id(), e);
}
```

The exception parameter goes last; SLF4J recognizes it and logs the full stack trace. Anti-patterns:

- `log.error(e.getMessage())` — no stack trace
- `log.error("Failed: " + e)` — stack trace lost
- `log.error("Failed", e)` — fine, but missing context

## What to log

### Always

- Application startup/shutdown
- Major errors with full context
- Security-relevant events (auth failures, permission denials)
- External service interactions (with response status)
- Long-running operations (start, finish, duration)

### Sometimes

- Successful HTTP requests (controlled by infrastructure-level logging usually; not the app)
- State transitions on important business objects
- Cache hits/misses (in dev or for analysis)
- Validation failures from user input

### Almost never

- Every method entry/exit (use tracing)
- Successful low-level operations (database queries, cache reads)
- Redundant info (logged at multiple layers of the stack)
- PII or secrets (passwords, full SSN, credit cards)

## Logging PII / secrets

Never log:
- Passwords
- Full credit card numbers (last 4 only if at all)
- Authentication tokens
- Personal data subject to compliance (GDPR, HIPAA)

Use redaction filters or structured logging that explicitly marks sensitive fields. Logs persist; sensitive data in logs becomes a compliance liability.

## Performance considerations

### Async logging

For high-throughput applications, synchronous logging becomes a bottleneck. Logback's `AsyncAppender` queues messages on a separate thread.

The trade-off: messages may be lost on crash; the async queue can fill up. For most production applications, async logging is the right default.

### Disabling expensive evaluations

Even with parameterized logging, building the parameter can be expensive:

```java
// expensive computation happens always
log.debug("Order detail: {}", expensiveSerialization(order));
```

Guard with `isDebugEnabled()`:

```java
if (log.isDebugEnabled()) {
    log.debug("Order detail: {}", expensiveSerialization(order));
}
```

Or use the lambda-based logging API (modern SLF4J):

```java
log.atDebug().log(() -> "Order detail: " + expensiveSerialization(order));
```

## Configuration

Externalize log configuration. `logback.xml` (or `logback-spring.xml` in Spring Boot) for Logback. Settings should be:

- Different per environment (DEBUG locally; INFO in production; ERROR for noisy categories)
- Configurable without redeployment (file watching, dynamic config)
- Output to appropriate destinations (stdout for containerized; file for legacy)

## Common failure patterns

- **Too much INFO.** Log volume drives cost; signal drowns in noise.
- **String concatenation in log calls.** Always evaluates the string.
- **Logging exceptions twice** — once at the catch site, once at the top-level handler.
- **Free-form text without structure.** Hard to query; hard to alert on.
- **No request correlation.** Cannot follow a request through logs.
- **Logging PII or secrets.** Compliance and security risk.
- **Missing key information.** "Failed" with no context. Always include the operation, the key inputs, the relevant state.

## Further Reading

- [JavaExceptionHandlingPatterns](JavaExceptionHandlingPatterns) — Logging exceptions
- [DebuggingStrategies](DebuggingStrategies) — Logs as debug tool
- [CodeDocumentationBestPractices](CodeDocumentationBestPractices) — Logs as runtime documentation
- [SpringBootFundamentals](SpringBootFundamentals) — Spring Boot's logging configuration
- [Java Hub](JavaHub) — Cluster index
