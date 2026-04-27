---
canonical_id: 01KQ0P44RB0EC2WE3DW85CTDWS
title: Java Module System
type: article
cluster: java
status: active
date: '2026-04-26'
summary: How the Java Module System (Project Jigsaw, Java 9+) works, the cases where
  modules pay off, and the practical path for adopting modules vs. staying on the
  classpath.
tags:
- java
- modules
- jpms
- jigsaw
- packaging
related:
- JavaTwentyOneFeatures
- JavaCollectionsFramework
- MavenMultiModuleProjects
- JavaBuildToolComparison
hubs:
- Java Hub
---
# Java Module System

The Java Module System (JPMS), introduced in Java 9 as Project Jigsaw, was the largest change to Java's structure in a decade. It introduced *modules* as a unit larger than packages: a named, versioned, encapsulated unit with explicit imports and exports. The promise: better encapsulation, smaller deployments, faster startup.

The reality: modules are useful for some codebases and overhead for others. Many production Java codebases stay on the classpath. This page is about how modules work, when they pay off, and the practical path for adoption.

## The basics

A module is declared by a `module-info.java` file at the root of its source tree:

```java
module com.example.orders {
    requires com.example.common;
    requires java.sql;

    exports com.example.orders.api;
    exports com.example.orders.model;
}
```

This module:
- Depends on `com.example.common` and `java.sql`
- Exposes its `api` and `model` packages to consumers
- Hides everything else (internal packages are inaccessible to other modules)

## What modules add

### Strong encapsulation

`exports` is explicit. Code in `com.example.orders.internal` is not visible to other modules unless explicitly exported. This is real encapsulation — even reflection is restricted unless the module opens packages.

Pre-modules, `public` was effectively module-public; any code on the classpath could access it. Post-modules, `public` plus `exports` is the boundary.

### Reliable configuration

The module graph is verified at startup. Missing dependencies fail fast; conflicting versions are caught. Compare to classpath, where missing classes appear as runtime exceptions far from the cause.

### Smaller runtime images

`jlink` produces a JRE containing only the modules your application uses — sometimes 30–50% smaller than a full JRE. Useful for containerized deployments.

### Service loading

Modules can declare provider/consumer relationships:

```java
provides com.example.SomeService with com.example.MyImpl;
uses com.example.SomeService;
```

Cleaner than the older `META-INF/services` mechanism.

## When modules pay off

- **Library distribution.** Library authors benefit from explicit API surfaces; consumers cannot accidentally depend on internals.
- **Large applications with clear boundaries.** Modular architectures where the boundaries are real.
- **Constrained deployment.** Embedded, container, or other size-sensitive environments where `jlink` matters.
- **Strong encapsulation needs.** Security-sensitive code where reflection access to internals must be prevented.

## When modules don't pay off

- **Small applications.** The ceremony of `module-info.java` files in every module exceeds the benefit.
- **Ecosystem with classpath-era libraries.** Many third-party libraries are not modularized; running them on the classpath is fine, but mixing modular and unmodular causes friction.
- **Heavy reflection usage.** Frameworks (Spring, Hibernate) need reflective access; opens-clauses become extensive.
- **Existing codebases without strong boundaries.** Adding modules to a tangled codebase exposes the tangling rather than fixing it.

## Path forward for typical codebases

Three modes:

### 1. Stay on the classpath (most common)

For most application code, the classpath approach (no `module-info.java`) continues to work. Java has full backwards compatibility. The encapsulation features of modules are not available, but neither are the costs.

This is what most Spring Boot applications do. Spring's reflection-heavy approach means modules add friction without compensating benefit.

### 2. Automatic modules

A JAR without `module-info.java` becomes an "automatic module" when placed on the module path — its name is derived from the JAR filename. Useful for transitional periods where some dependencies are modular and some are not.

The names are unstable (depend on JAR filenames); for libraries this is dangerous. For applications, automatic modules are a reasonable migration step.

### 3. Full modularization

Adding `module-info.java` to every module. For libraries this is now expected; for applications it is opt-in.

The migration cost: every module gets a `module-info.java` file with its dependencies and exports. The benefit: strong encapsulation, smaller deployments, reliable configuration.

## Practical patterns

### `requires transitive`

When module A re-exports parts of B that consumers also need:

```java
requires transitive com.example.api;
```

Saves consumers from having to require the transitive dependency themselves.

### `opens` for reflection

Frameworks need reflective access to non-public members:

```java
opens com.example.entity to com.example.persistence;
```

Allows persistence frameworks to read private fields. Without this, reflection fails.

### Service consumer/provider

For pluggable architectures:

```java
// Provider module
provides com.example.Plugin with com.example.MyPlugin;

// Consumer module
uses com.example.Plugin;
```

The consumer iterates over `ServiceLoader<Plugin>` to find providers.

## Common failure patterns

- **Modularizing a tangled codebase.** Modules don't fix bad design; they expose it.
- **Mixing modular and unmodular dependencies haphazardly.** Causes runtime errors at module boundaries.
- **Heavy reflection without `opens`.** Spring/Hibernate failures are common.
- **`requires` for things you don't actually use.** Overstates the dependency graph.
- **`exports` too liberally.** Defeats encapsulation.
- **Adopting modules because "it's the modern way."** Without specific benefit, you're paying ceremony for nothing.

## A reasonable position

For most application development:

- Stay on the classpath unless there's a specific reason to modularize
- Prefer libraries that have proper modules (the `Automatic-Module-Name` manifest entry indicates a stable name even without `module-info`)
- Re-evaluate modules every few years as the ecosystem stabilizes

For library development:

- Add `Automatic-Module-Name` to the manifest as a minimum
- Add full `module-info.java` if your consumers benefit from it

The Java module system is a real feature with real benefits in specific contexts. For most production Java applications, the costs exceed the benefits.

## Further Reading

- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Modern Java features that work with or without modules
- [JavaCollectionsFramework](JavaCollectionsFramework) — Collections work the same in both worlds
- [MavenMultiModuleProjects](MavenMultiModuleProjects) — Maven modules vs. JPMS modules (different concepts)
- [JavaBuildToolComparison](JavaBuildToolComparison) — Build tools and module support
- [Java Hub](Java+Hub) — Cluster index
