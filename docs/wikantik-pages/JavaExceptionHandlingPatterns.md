---
canonical_id: 01KQ0P44R9W6RAHG237ZG08CBZ
title: Java Exception Handling Patterns
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How to think about Java exceptions — checked vs. unchecked, when to wrap,
  the patterns that work in production, and the things that should never appear in
  catch blocks.
tags:
- java
- exceptions
- error-handling
- defensive-programming
related:
- JavaTwentyOneFeatures
- JavaRecordsAndSealedClasses
- DebuggingStrategies
- CleanCodePrinciples
hubs:
- Java Hub
---
# Java Exception Handling Patterns

Java's exception system has nuances — checked vs. unchecked, the controversial `throws` clause, exception chaining, the temptation to swallow exceptions in catch blocks. The right patterns are not particularly novel but they do require deliberate decisions; default behavior often produces poor outcomes.

## The mental model

Exceptions are flow control for unexpected conditions. They unwind the stack until caught. They carry information (type, message, stack trace, cause).

The decisions:
- When does a function throw vs. return an error type?
- When is the exception handled vs. propagated?
- What happens at the top of the stack?

## Checked vs. unchecked

### Checked exceptions

Subclasses of `Exception` (but not `RuntimeException`). Compiler-enforced — calling code must either handle them or declare them in `throws`.

The original idea: force callers to acknowledge possible failures. The reality: `throws IOException` everywhere becomes noise; teams routinely catch-and-ignore to satisfy the compiler.

### Unchecked exceptions

Subclasses of `RuntimeException`. No compiler enforcement.

Modern Java codebases lean toward unchecked. The argument: checked exceptions force ceremony without producing better error handling, since callers often don't have a meaningful response.

### When to use checked

- Recoverable failures the caller is expected to handle (rare in practice)
- Cross-API contracts where the failure modes are essential

### When to use unchecked

- Programmer errors (NullPointerException, IllegalArgumentException, IllegalStateException)
- Failures the caller is unlikely to recover from (out of memory, network down)
- Domain errors that propagate to a top-level handler

For most internal code, unchecked is the right default.

## The patterns that work

### Wrap-and-rethrow at boundaries

When code catches a low-level exception and the caller cares about a higher-level concept:

```java
try {
    return loadFromDatabase(id);
} catch (SQLException e) {
    throw new OrderLoadException("Failed to load order " + id, e);
}
```

Preserve the cause; add context. The high-level exception has a meaningful name; the low-level exception is in the chain.

### Domain-specific exception types

Define specific exceptions for specific failure modes:

```java
public class InvalidOrderException extends RuntimeException {
    public InvalidOrderException(String message) {
        super(message);
    }
}

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String id) {
        super("Order not found: " + id);
    }
}
```

Specific types let callers distinguish failure modes; the alternative — checking exception messages — is fragile.

### Try-with-resources for cleanup

```java
try (var connection = pool.getConnection();
     var statement = connection.prepareStatement(sql)) {
    return statement.executeQuery();
}
```

Resources implementing `AutoCloseable` are closed automatically. Replaces try-finally for cleanup. Always use this for resource management.

### Top-level handling

A single point in the application catches uncaught exceptions and translates them to user-facing errors, logs, metrics. Servlet filters, Spring's `@ControllerAdvice`, similar patterns. Centralizes the "what does the user see when something fails" decision.

## Anti-patterns

### Swallowing exceptions

```java
// Bad
try {
    riskyOperation();
} catch (Exception e) {
    // ignore
}
```

Information loss. The exception happened; nobody knows. If you genuinely want to ignore an exception, log it with rationale. Empty catch blocks are bugs.

### Catching `Exception` or `Throwable`

```java
// Almost always wrong
try {
    operation();
} catch (Exception e) {
    handle(e);
}
```

Catches too much: `RuntimeException`, `Error`, things you didn't anticipate. Be specific about what you're catching.

The exception: top-level handlers explicitly want to catch everything. There, the broad catch is intentional.

### Using exceptions for control flow

```java
// Wrong
try {
    int value = Integer.parseInt(input);
    handleNumeric(value);
} catch (NumberFormatException e) {
    handleNonNumeric(input);
}
```

Exceptions are expensive (stack trace generation). Using them for routine control flow is slow and obscures intent. Pre-check or return optional.

### Re-throwing without context

```java
catch (SQLException e) {
    throw new RuntimeException(e);
}
```

Loses information. The new exception has no message describing what was being attempted. Wrap with context.

### Logging and rethrowing

```java
catch (IOException e) {
    log.error("Failed", e);
    throw e;
}
```

Logged twice — once here, once at the top-level handler. Pick one: log here and translate to a different exception, or rethrow without logging.

## Result types as alternative

For internal APIs, sealed types as result types can be cleaner than exceptions:

```java
public sealed interface Result<T, E> {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}
}

public Result<Order, OrderError> load(String id) {
    if (!repository.exists(id)) {
        return new Err<>(new NotFound(id));
    }
    return new Ok<>(repository.find(id));
}
```

Callers handle both cases explicitly; no exception flow control. See [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses).

This pattern works well at internal boundaries. At external boundaries (servlets, controllers), exceptions integrate better with framework error handling.

## Logging exceptions correctly

```java
log.error("Failed to process order {}", orderId, e);
```

The exception parameter (last) is the standard SLF4J pattern. The full stack trace is logged.

Anti-patterns:
- `log.error(e.getMessage())` — loses the stack trace
- `log.error("Failed: " + e)` — loses the stack trace and the message-vs-trace distinction
- Excessive log levels — every exception logged at error becomes noise

## Common failure patterns

- **Empty catch blocks.** Always log; ideally rethrow or handle meaningfully.
- **Catching `Exception` or `Throwable`.** Almost always wrong outside top-level handlers.
- **Exceptions for control flow.** Slow and unclear.
- **Translating without context.** Wrap with the higher-level concern.
- **Multiple log statements per exception.** Pick one place; the top-level handler usually.
- **Custom checked exceptions everywhere.** Forces ceremony without payoff.

## Further Reading

- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Pattern matching enables result-type usage
- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Result types with records
- [DebuggingStrategies](DebuggingStrategies) — Exception traces in debugging
- [CleanCodePrinciples](CleanCodePrinciples) — Errors as part of the contract
- [Java Hub](Java+Hub) — Cluster index
