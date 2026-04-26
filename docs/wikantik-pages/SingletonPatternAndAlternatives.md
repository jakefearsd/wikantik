---
title: Singleton Pattern And Alternatives
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- singleton
- design-patterns
- dependency-injection
- testing
summary: Singleton is the most-overused GoF pattern. The cost is hidden global
  state and untestable code. The modern replacement is dependency injection
  with composition-root configuration.
related:
- DesignPatternsOverview
- DependencyInjectionPatterns
hubs:
- SoftwareArchitecture Hub
---
# Singleton Pattern and Alternatives

The Singleton pattern guarantees a class has only one instance and provides global access to it. The GoF book popularised it; a generation of programmers used it everywhere; the same generation has spent years getting away from it.

By 2026, the consensus is: prefer dependency injection. Singleton specifically as a pattern is rarely the right answer. This page is the reasoning and the alternatives.

## What Singleton is

Classic implementation:

```java
public class Logger {
    private static Logger instance;
    
    private Logger() { /* private constructor */ }
    
    public static Logger getInstance() {
        if (instance == null) {
            instance = new Logger();
        }
        return instance;
    }
    
    public void log(String msg) { /* ... */ }
}

// Usage:
Logger.getInstance().log("hello");
```

Two assertions: (1) only one instance ever exists; (2) you access it via static method.

## Why it became popular

It solves a real problem: some things genuinely should be one. Database connection pools, configuration managers, loggers, caches.

It also makes them globally accessible. Easy to use anywhere; no need to pass the dependency through layers.

These two properties — single instance + global access — are what made it popular and what made it problematic.

## Why people regret it

### Hidden dependencies

A class that uses `Logger.getInstance()` has Logger as a dependency, but its constructor doesn't say so. You can't tell from the signature what a class needs.

When testing, you can't substitute a different Logger. The class hard-couples to the singleton.

### Untestable

In tests, you want to:
- Substitute fake implementations (mock logger that captures calls).
- Run tests in isolation (no shared state between tests).

Singletons defeat both. Tests run sequentially; one test's logger state leaks to the next; mocking requires reflection or special test infrastructure.

### Concurrent initialisation bugs

The naive `if (instance == null) { instance = new Logger(); }` is racy under multithreaded access. Two threads can create two instances. The fixes (synchronized methods, double-checked locking, holder idiom) add complexity for what should be trivial.

### Global mutable state

If the singleton has mutable state (most do), every part of the system shares that state. Bugs become "why did changing this here affect that there?"

### Order of initialisation problems

Singleton A depends on Singleton B; B depends on A. Static initialisation order is hard to control. Either you use lazy initialisation (with the concurrency issues above) or you carefully order initialisation (and pray nothing changes).

### Testing across processes

Even in test isolation, multiple test threads in one JVM share singletons. Surprises everywhere.

## What people actually mean by "we need a singleton"

Three different scenarios, often conflated:

1. **"There's logically only one of this thing."** A configuration loaded from a file. A database connection pool. A logger.
2. **"It's expensive to create; reuse it."** An HTTP client; a thread pool.
3. **"I need access from anywhere without passing it around."** Convenience.

Each has a better solution than Singleton.

## The modern alternative: dependency injection

Construct the single instance once in a "composition root" (the entry point of your application). Pass it where needed via constructor parameters or a DI container.

```java
public class Application {
    public static void main(String[] args) {
        // Composition root
        Logger logger = new ConsoleLogger();
        Database db = new PostgresDatabase(config.dbUrl);
        UserService users = new UserService(db, logger);
        OrderService orders = new OrderService(db, logger, users);
        // ... etc
        
        new HttpServer(orders, users).start();
    }
}
```

Properties:
- Single instance: only one is constructed.
- Explicit dependencies: every class declares what it needs.
- Testable: tests construct with fakes.
- No global state: each component holds its references; no shared mutable singleton.

DI containers (Spring, Guice, Dagger, .NET's built-in DI, NestJS) automate this for larger applications. They're "singleton machinery" done correctly — single instances, lifecycle management, automatic injection.

## When Singleton is acceptable

Specific narrow cases:

- **Truly stateless utilities.** A pure-function helper class. Singleton has no state to leak. (Also: just use static functions; no class needed.)
- **Hardware abstractions.** A class wrapping a single physical resource (printer, GPS sensor) where multiplicity is impossible.
- **JVM-/process-wide infrastructure** that must exist before any DI container could spin up. A logger that needs to be available during DI bootstrap.

Even here, modern frameworks usually handle these via DI lifecycle hooks. True hand-rolled Singleton in 2026 is a code smell.

## Specific alternatives by use case

### Logger

Singleton is common; not necessary.

```java
public class UserService {
    private final Logger logger;
    
    public UserService(Logger logger) {
        this.logger = logger;
    }
    
    public void createUser(...) {
        logger.info("Creating user");
    }
}
```

Tests substitute a test logger. Production wires the real one. No singleton.

### Configuration

Load once at startup; pass around.

```java
class Config { /* read-only fields */ }

class Application {
    public static void main(String[] args) {
        Config config = ConfigLoader.load();
        // Pass `config` to components that need it.
    }
}
```

Alternative: an immutable `record` / `data class` for config; no singleton needed.

### Database connection pool

Constructed once at startup; injected into components.

```java
DataSource dataSource = HikariDataSource.create(config);
UserRepository repo = new UserRepository(dataSource);
```

Spring / similar frameworks handle this with bean lifecycle = "singleton scope" — but the user-facing pattern is DI, not Singleton.

### HTTP client

Most HTTP client libraries are designed to be reused (connection pool inside). One instance per service / endpoint, injected.

### Caches

Same as DB connection pool. Construct once; inject.

## "Pseudo-singleton" via DI scope

Spring's bean scopes:

- `singleton` (default) — one instance per ApplicationContext.
- `prototype` — new instance per injection.
- Various contextual scopes (request, session, etc.).

The Spring `singleton` scope solves the "single instance" requirement of the GoF Singleton without the static-method access problem. Beans are injected; tests substitute; concurrent initialisation is handled by the container.

Result: most applications using DI have de-facto singletons (one DataSource, one Logger, one HTTP client) without using the Singleton pattern.

## When you really must implement Singleton

If for some reason you can't use DI:

```java
// Holder idiom — thread-safe, lazy
public class Singleton {
    private Singleton() {}
    
    private static class Holder {
        private static final Singleton INSTANCE = new Singleton();
    }
    
    public static Singleton getInstance() {
        return Holder.INSTANCE;
    }
}
```

The holder pattern handles concurrency cleanly via JVM class-loading semantics. Avoids double-checked locking complexity.

For Kotlin, `object Singleton { ... }` is the language-level singleton.

For Python, a module is implicitly a singleton (loaded once); functions and module-level state work.

For most languages, prefer module-level / static functions over Singleton classes when you genuinely need a single instance with no state.

## Pragmatic position

For new code in 2026:

1. **Don't reach for Singleton.** It's almost always wrong.
2. **Use a DI container** if your app is non-trivial. Spring, Guice, Dagger (Java); NestJS (Node); .NET DI; etc.
3. **Manual composition root** for smaller apps — explicit construction at the entry point.
4. **Module-level functions / objects** for truly stateless utilities.
5. **Static-only access** is a strong code smell in test-driven codebases.

When you encounter a Singleton in old code, gradually convert to DI. Add a constructor that takes the dependency; have the static `getInstance()` delegate to a configured instance; eventually retire the static.

## Further reading

- [DesignPatternsOverview] — broader pattern context
- [DependencyInjectionPatterns] — the modern alternative in depth
