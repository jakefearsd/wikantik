---
canonical_id: 01KQ0P44RD0W7H088EW5N6NGTP
title: Java 21 Features
type: article
cluster: java
status: active
date: '2026-04-26'
summary: The Java 21 features that matter — virtual threads, pattern matching for
  switch, records, sealed classes, text blocks — and which ones change how production
  Java is written vs. which are nice-to-haves.
tags:
- java
- java-21
- virtual-threads
- pattern-matching
- records
related:
- JavaRecordsAndSealedClasses
- JavaStreamsAndFunctionalProgramming
- JavaCollectionsFramework
- JavaModuleSystem
- SpringBootFundamentals
hubs:
- Java Hub
---
# Java 21 Features

Java 21 (LTS, released September 2023) consolidated several previews into stable features. The result is a Java that looks meaningfully different from Java 8 — pattern matching, records, sealed classes, text blocks, virtual threads, and a steady accretion of API improvements. This page covers what changed and which features actually change how production code is written.

## Virtual threads (JEP 444)

The largest shift in Java's concurrency model in two decades. Virtual threads are JVM-managed lightweight threads — millions per process rather than thousands. The thread-per-request model that platform threads made expensive becomes cheap.

Practical impact:
- Servers can use simple thread-per-request without thread pools
- Blocking I/O is no longer a scalability problem (in most cases)
- The CompletableFuture / reactive frameworks lose some of their case
- Synchronization on virtual threads still works; pinning issues exist for `synchronized` blocks holding the carrier thread

The major caveat: virtual threads do not solve CPU-bound concurrency. Use platform threads (or parallel streams) for compute-heavy work.

## Pattern matching for switch (JEP 441)

Switch expressions can pattern-match on type, including record deconstruction:

```java
return switch (shape) {
    case Circle c -> Math.PI * c.radius() * c.radius();
    case Rectangle r -> r.width() * r.height();
    case Triangle t when t.isRight() -> 0.5 * t.base() * t.height();
    case null -> 0;
};
```

Combined with sealed classes, this enables exhaustive type-based dispatch with compile-time exhaustiveness checking — closer to ML-family pattern matching.

## Records (JEP 395, finalized earlier)

Already widely adopted. Records are immutable data carriers — concise syntax, automatic equals/hashCode/toString. See [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) for the full picture.

## Sealed classes (JEP 409)

Restricted inheritance — a sealed class names exactly which classes can extend it. Combined with pattern matching, gives you sum types in Java.

```java
sealed interface Shape permits Circle, Rectangle, Triangle {}
```

The compiler can verify exhaustive coverage in switches, catching missing cases at compile time.

## Text blocks (finalized in 17)

Multi-line string literals:

```java
String json = """
    {
        "name": "%s",
        "value": %d
    }
    """;
```

Removes most of the awkward `"foo\n" + "bar\n" +` patterns. Particularly useful for SQL, JSON, HTML embedded in code.

## Other notable features

### Sequenced collections (JEP 431)

`SequencedCollection`, `SequencedSet`, `SequencedMap` interfaces. `getFirst()`, `getLast()`, `addFirst()`, etc. directly on `List`, `LinkedHashSet`, etc. Ends some long-standing API gaps.

### String templates (preview in 21, may evolve)

```java
String name = "Alice";
String message = STR."Hello, \{name}!";
```

Cleaner than `String.format` for many cases. Still preview as of Java 21.

### Foreign Function & Memory API (preview)

Replaces JNI for calling native code. Better safety, simpler API. Useful for libraries that need native-code interop.

### Generational ZGC (JEP 439)

ZGC garbage collector with generational support — better throughput while preserving low latency. For latency-sensitive applications, often the right GC choice in Java 21.

## What this changes for production Java

The shifts that matter day-to-day:

1. **Records replace boilerplate value classes** — almost every "DTO with getters/setters" should now be a record
2. **Pattern matching replaces visitor pattern** — sealed + switch produces cleaner alternatives in many cases
3. **Text blocks replace string concatenation** for embedded content
4. **Virtual threads replace thread pools** for blocking I/O
5. **Sequenced collections** end some API ugliness

What hasn't changed: the standard library, the JVM internals, the deployment model. Java 21 looks more modern; the platform underneath is recognizable.

## Migration path

Java 8 → 11 → 17 → 21 has been the typical path. Each step is incremental:

- 8 → 11: var, modules (optional), some API additions
- 11 → 17: records, sealed classes, switch expressions, text blocks
- 17 → 21: pattern matching for switch, virtual threads, sequenced collections

Most codebases can move from 8 directly to 21 if dependencies cooperate. The breaking changes are limited; the new features are opt-in.

## Common adoption patterns

- Move pure value classes to records first — high payoff, low risk
- Adopt text blocks where SQL/JSON/HTML appear in code
- Add sealed interfaces when designing new domain models
- Adopt virtual threads in services that handle I/O-bound concurrent requests
- Defer pattern matching adoption until the team is comfortable with the syntax

## Further Reading

- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Records and sealed in detail
- [JavaStreamsAndFunctionalProgramming](JavaStreamsAndFunctionalProgramming) — Functional features
- [JavaCollectionsFramework](JavaCollectionsFramework) — Sequenced collections fit here
- [JavaModuleSystem](JavaModuleSystem) — Modules background
- [SpringBootFundamentals](SpringBootFundamentals) — Framework that's adopted these features
- [Java Hub](Java+Hub) — Cluster index
