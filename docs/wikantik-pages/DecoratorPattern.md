---
canonical_id: 01KQE9TGD56ZPE63K9Z787MVXZ
related:
- DesignPatternsOverview
- ObserverPattern
- AdapterPattern
title: "Structural Composition: The Decorator Pattern"
hubs:
- SoftwareArchitectureHub
status: active
date: '2026-05-22'
summary: A guide to wrapping objects to add behavior dynamically. Explores the importance of stacking order in middleware and resilient client design.
tags:
- decorator-pattern
- design-patterns
- middleware
- composition
- resiliency
type: article
cluster: software-architecture
auto-generated: false
---

# Structural Composition: The Decorator Pattern

The **Decorator Pattern** allows behavior to be added to an individual object, dynamically, without affecting the behavior of other objects from the same class. It is the architectural foundation for **Middleware**, **Interceptors**, and **Resilient Clients**.

## I. Stacking Order Matters

In a decorator stack, the order of wrapping determines the execution flow.
*   **Outside-In:** The outermost decorator executes first on the request path and last on the response path.
*   **Example:** `Logging(Retry(Caching(Service)))` vs `Caching(Logging(Retry(Service)))`.
    *   In the first case, retries are logged. In the second, only cache misses are logged.

## II. Concrete Example: The Resilient HTTP Client

By composing simple decorators, we can build a production-grade HTTP client with multiple cross-cutting concerns.

```java
interface HttpClient { String get(String url); }

class BaseClient implements HttpClient {
    @Override public String get(String url) { /* actual I/O */ return ""; }
}

class LoggingDecorator implements HttpClient {
    private final HttpClient inner;
    public LoggingDecorator(HttpClient inner) { this.inner = inner; }

    @Override public String get(String url) {
        System.out.println("Calling: " + url);
        return inner.get(url);
    }
}

class RetryDecorator implements HttpClient {
    private final HttpClient inner;
    @Override public String get(String url) {
        return retry(() -> inner.get(url)); // Logic to retry on 5xx
    }
}

// Composition: Resilient Client
HttpClient client = new LoggingDecorator(new RetryDecorator(new BaseClient()));
```

## III. Transparency and the "Identity" Problem

A perfect decorator is **Transparent**: the client should not know it is talking to a wrapper.
*   **Challenge:** If your code uses `instanceof` or reflection to check for a specific implementation, decorators will break it.
*   **Solution:** Always code to the interface, never the implementation.

## IV. Dynamic Proxies (JDK Proxy)

For cross-cutting concerns that apply to many interfaces (e.g., generic metrics), use `java.lang.reflect.Proxy`. This avoids creating a manual decorator class for every interface in your system.

```java
HttpClient proxy = (HttpClient) Proxy.newProxyInstance(
    HttpClient.class.getClassLoader(),
    new Class<?>[]{HttpClient.class},
    (p, method, args) -> {
        // Intercept logic here
        return method.invoke(realObject, args);
    }
);
```

---
**See Also:**
- [Proxy Pattern](ProxyPattern) — For controlling access rather than adding behavior.
- [Design Patterns Overview](DesignPatternsOverview) — Context within structural patterns.
- [Adapter Pattern](AdapterPattern) — For translating interfaces rather than wrapping behavior.
