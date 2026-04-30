---
canonical_id: 01KQ0P44RB6G7KD29957V7XSEC
title: Java Reflection and Proxies
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How Java reflection works, when it earns its place, the dynamic proxy mechanism
  that powers JDK proxies and frameworks, and the cases where reflection is the wrong
  tool.
tags:
- java
- reflection
- dynamic-proxies
- frameworks
related:
- JavaModuleSystem
- JavaAnnotationProcessing
- JavaTwentyOneFeatures
- SpringBootFundamentals
hubs:
- JavaHub
---
# Java Reflection and Proxies

Reflection lets Java code inspect and manipulate types, methods, and fields at runtime. Combined with dynamic proxies, it's the foundation under most Java frameworks — Spring, Hibernate, JUnit, mocking libraries, serialization, dependency injection. Application code rarely uses reflection directly; understanding what frameworks do helps when something goes wrong.

## What reflection provides

The main APIs:

- `Class<?>` — runtime type information
- `Method`, `Field`, `Constructor` — type members
- `Method.invoke(target, args...)` — call a method by reference
- `Field.get(target)` / `Field.set(target, value)` — read/write fields
- `Constructor.newInstance(args...)` — create objects

```java
Class<?> clazz = Class.forName("com.example.MyClass");
Method m = clazz.getMethod("doSomething", String.class);
Object instance = clazz.getConstructor().newInstance();
m.invoke(instance, "argument");
```

This is opaque, slow, and fragile compared to direct method calls. Use sparingly.

## When reflection earns its place

- **Frameworks** that need to operate on user-defined types
- **Serialization** that must work without prior knowledge of types
- **Test frameworks** discovering and running annotated tests
- **Dependency injection** wiring up beans by type and qualifier
- **Plugin systems** loading classes at runtime

For application code, reflection is almost always the wrong choice. Direct method calls, interfaces, and explicit type handling are clearer, faster, safer.

## Dynamic proxies

`Proxy.newProxyInstance` creates an object that implements specified interfaces and routes all calls through an `InvocationHandler`:

```java
MyService service = (MyService) Proxy.newProxyInstance(
    classLoader,
    new Class[]{MyService.class},
    (proxy, method, args) -> {
        // do something before
        Object result = realImplementation.invoke(args);
        // do something after
        return result;
    });
```

Used by:
- AOP frameworks (Spring AOP, AspectJ)
- Mocking libraries (Mockito creates proxy objects)
- RPC clients (the proxy translates method calls into network requests)
- Lazy loading (Hibernate proxies)

Limitations: only works on interfaces. For concrete classes, libraries use bytecode generation (CGLIB, ByteBuddy) — similar concept, different mechanism.

## Modern alternatives

For some reflection use cases, modern Java has cleaner alternatives:

### MethodHandles

Faster than `Method.invoke`. The JVM can inline through MethodHandles in ways it cannot through reflection. Used by Java's `var` invocation, certain framework hot paths.

### VarHandles

For atomic field access. Replaces `Unsafe` for most cases.

### Sealed types + pattern matching

Where reflection was used for type dispatch (visitor pattern), modern Java often uses sealed interfaces + switch pattern matching. See [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses).

## Module system interaction

Java modules restrict reflective access. A module must `opens` a package for reflection from another module:

```java
opens com.example.entity to com.example.persistence;
```

Without this, frameworks doing reflection on entity classes get `IllegalAccessException`. This is why Spring Boot's documentation recommends `--add-opens` flags or proper module declarations for reflection-heavy frameworks.

## Performance

Reflection is slow:
- Method lookup is expensive (cache the Method object)
- Method invocation has 5–10× overhead vs. direct calls in tight loops
- Field access is similarly slower

For hot paths: cache lookups, pre-resolve at startup, use MethodHandle if real performance matters.

For cold paths (configuration, startup, occasional invocation): the performance cost is invisible.

## Common failure patterns

- **Using reflection in application code where direct calls would work.** Slower, less safe, harder to read.
- **Not setting `setAccessible(true)` for non-public members.** Reflection on private fields fails without this.
- **Catching reflection exceptions and ignoring them.** The exception usually points to a real problem.
- **Reflective access without `opens` in modular code.** Fails at runtime.
- **Overusing dynamic proxies.** Each layer of proxying adds overhead and obscurity.

## Further Reading

- [JavaModuleSystem](JavaModuleSystem) — Module access and reflection
- [JavaAnnotationProcessing](JavaAnnotationProcessing) — Compile-time alternative to reflection
- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Modern features that reduce reflection need
- [SpringBootFundamentals](SpringBootFundamentals) — A heavy reflection user
- [Java Hub](JavaHub) — Cluster index
