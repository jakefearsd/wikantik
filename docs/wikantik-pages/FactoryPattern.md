---
canonical_id: 01KQ0P44QADM98JDF5E508DNRR
title: Factory Pattern
type: article
cluster: design-patterns
status: active
date: '2026-04-26'
summary: When factories add value vs. when they're ceremony — simple factory methods,
  factory classes, abstract factories, and the cases where each fits.
tags:
- factory-pattern
- design-patterns
- creational
- dependency-injection
related:
- BuilderPatternAndFluentApis
- RepositoryPattern
- SpecificationPattern
- CleanCodePrinciples
hubs:
- DesignPatternsHub
---
# Factory Pattern

A factory is anything that creates objects on behalf of callers. The pattern hides construction details and lets callers ask for the right type without knowing the implementation. Several variants exist with different complexity-vs-flexibility trade-offs.

## Simple factory method

A static method that creates an object:

```java
public class HttpClient {
    public static HttpClient defaultClient() {
        return new HttpClient(defaultConfig());
    }

    public static HttpClient withTimeout(Duration timeout) {
        return new HttpClient(configWithTimeout(timeout));
    }
}
```

Hides the construction logic; provides named alternatives. Lower ceremony than constructors with cryptic parameters.

When this fits:
- Multiple ways to construct the same type with different defaults
- Construction involves logic too complex for a constructor
- Want to return a subtype based on parameters
- Want to cache or pool instances

## Factory class

A separate class whose only job is to create objects:

```java
public class OrderFactory {
    private final OrderRepository repository;
    private final NotificationService notifications;

    public OrderFactory(OrderRepository repository, NotificationService notifications) {
        this.repository = repository;
        this.notifications = notifications;
    }

    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order(/* ... */);
        repository.save(order);
        notifications.sendCreated(order);
        return order;
    }
}
```

Useful when:
- Construction depends on injected services
- Construction has side effects (persistence, notifications)
- The construction logic is significant enough to warrant its own class

In Spring/dependency-injected applications, factory classes are common — they're injected like any other service.

## Abstract Factory

A factory that creates families of related objects:

```java
public interface UIFactory {
    Button createButton();
    Window createWindow();
    Menu createMenu();
}

public class MacUIFactory implements UIFactory { /* ... */ }
public class WindowsUIFactory implements UIFactory { /* ... */ }
```

Used for:
- Cross-platform abstractions (UI per platform)
- Theming/skinning
- Multiple coherent variants of a type family

Abstract Factory is heavier than simple factory. For most applications, simpler patterns work; Abstract Factory earns its place when you have multiple related types that vary together.

## Factory vs. constructor: when

Direct construction (`new Foo(...)`) is fine when:
- The class is final or essentially so
- Construction is simple
- Callers don't need alternatives

Factory method is better when:
- You may want to return subtypes
- Naming the construction style helps clarity (`HttpClient.defaultClient()` vs `new HttpClient(defaults)`)
- Construction logic is non-trivial

Factory class is better when:
- Construction depends on injected services
- Construction has side effects

## Factories and dependency injection

In modern Java with Spring or similar DI containers, the framework is essentially a factory:
- `@Autowired` says "give me a Foo"
- The container constructs Foo according to its rules
- The container can return subtypes (interfaces with implementations)

Most "factory pattern" use cases in DI-heavy code are handled by the DI framework. Explicit factory classes are needed when:
- Construction varies by runtime parameters (per-request)
- DI doesn't fit (e.g., creating user-input-driven objects)

## Common failure patterns

- **Factories for trivial construction.** `OrderFactory.create(amount)` when `new Order(amount)` works.
- **Static factory methods masquerading as singletons.** Test-unfriendly.
- **Abstract factories without multiple variants.** Speculative; remove when only one implementation exists.
- **Factories that hide too much.** Caller can't customize because the factory makes all decisions.

## Further Reading

- [BuilderPatternAndFluentApis](BuilderPatternAndFluentApis) — Adjacent creational pattern
- [RepositoryPattern](RepositoryPattern) — Often paired with factories
- [SpecificationPattern](SpecificationPattern) — Constructed by factories
- [CleanCodePrinciples](CleanCodePrinciples) — When factories help readability
- [DesignPatterns Hub](DesignPatternsHub) — Cluster index
