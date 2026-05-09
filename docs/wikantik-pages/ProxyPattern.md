---
cluster: design-patterns
canonical_id: 01KQ0P44V0P5BHCQ8FDA3B6G3E
title: "The Smart Surrogate: The Proxy Pattern"
type: article
tags:
- proxy-pattern
- design-patterns
- structural-patterns
- lazy-loading
- access-control
summary: Managing access and lifecycle through indirection. This guide explores Virtual, Protection, and Dynamic proxies with concrete implementation patterns.
related:
- DesignPatternsOverview
- DecoratorPattern
- CircuitBreakerPattern
auto-generated: false
date: '2026-05-22'
---

# The Smart Surrogate: The Proxy Pattern

A **Proxy** provides a placeholder for another object to control access to it. Unlike a Decorator, which adds behavior, a Proxy manages the **lifecycle** or **authorization** of the underlying subject.

## I. Three Primary Proxy Types

1.  **Virtual Proxy:** Defers the creation of an expensive object until it is actually needed (Lazy Loading).
2.  **Protection Proxy:** Checks if the caller has the necessary permissions before delegating the call.
3.  **Remote Proxy:** Provides a local representative for an object in a different address space (e.g., gRPC stubs).

## II. Concrete Example: The Secure Lazy Proxy

Combining protection and lazy initialization in a single surrogate.

```java
public class SecureImageProxy implements Image {
    private RealImage realImage;
    private final String filename;
    private final User currentUser;

    public SecureImageProxy(String filename, User user) {
        this.filename = filename;
        this.currentUser = user;
    }

    @Override
    public void display() {
        // 1. Protection Check
        if (!currentUser.hasPermission("VIEW_IMAGES")) {
            throw new SecurityException("Access Denied");
        }

        // 2. Virtual Proxy (Lazy Load)
        if (realImage == null) {
            realImage = new RealImage(filename); // Expensive I/O happens here
        }

        realImage.display();
    }
}
```

## III. Dynamic Proxies in Java

For cross-cutting concerns that apply to many services (e.g., logging every method call), use `java.lang.reflect.Proxy`. This allows you to create a proxy at runtime for any interface.

```java
Service original = new RealService();
Service proxy = (Service) Proxy.newProxyInstance(
    Service.class.getClassLoader(),
    new Class<?>[]{Service.class},
    (p, method, args) -> {
        long start = System.nanoTime();
        Object result = method.invoke(original, args);
        System.out.println(method.getName() + " took " + (System.nanoTime() - start) + "ns");
        return result;
    }
);
```

## IV. Technical Integrity

1.  **Copy-on-Write and Thread Safety:** If multiple threads access a Virtual Proxy simultaneously, ensure the initialization of the `RealSubject` is synchronized to avoid duplicate creation of expensive resources.
2.  **Transparency:** The Proxy must implement the same interface as the Subject. If the client code relies on concrete classes instead of interfaces, you must use bytecode-level proxying (e.g., ByteBuddy or CGLIB).
3.  **Avoid "Invisible" Side Effects:** A proxy should be predictable. A proxy that silently adds significant latency or modifies global state can be difficult to debug.

---
**See Also:**
- [Decorator Pattern](DecoratorPattern) — For adding behavior rather than managing access.
- [Circuit Breaker Pattern](CircuitBreakerPattern) — A specialized proxy for fault tolerance.
- [Design Patterns Overview](DesignPatternsOverview) — Structural pattern context.
