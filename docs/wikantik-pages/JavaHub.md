---
date: '2026-04-26'
summary: Index of the modern Java platform — language features through Java 21+, build
  and test tooling, and patterns for sustainable long-running codebases.
cluster: java
related:
- SoftwareEngineeringPracticesHub
- DesignPatternsHub
- WebServicesAndApisHub
canonical_id: 01KZHC6PVQ4SBQM9R0F3T7K8Z3
type: hub
title: Java
status: active
hubs:
- SoftwareEngineeringPracticesHub
- JavaMemoryManagementHub
- DesignPatternsHub
- WebServicesAndApisHub
tags:
- java
- hub
- jvm
- language
- standard-library
---
# Java Hub

The cluster covers modern Java — the language, the standard library, the build and test tooling, and the patterns that make long-running Java codebases sustainable. The orientation is practical: what you use day-to-day in production codebases, how the pieces fit together, and the choices that have aged well versus the ones that have not.

## Language features

- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Records, sealed classes, pattern matching, virtual threads, and what each is actually for
- [JavaRecordsAndSealedClasses](JavaRecordsAndSealedClasses) — Modern data modeling primitives
- [JavaStreamsAndFunctionalProgramming](JavaStreamsAndFunctionalProgramming) — Streams, collectors, and where functional patterns help vs. hurt
- [JavaCollectionsFramework](JavaCollectionsFramework) — The standard collections, when to use each, the immutable variants
- [JavaExceptionHandlingPatterns](JavaExceptionHandlingPatterns) — Checked vs. unchecked, when to wrap, when not to swallow

## Platform internals

- [JavaModuleSystem](JavaModuleSystem) — Modules in practice, when to adopt, when to skip
- [JavaMemoryManagement Hub](JavaMemoryManagementHub) — Heap, GC, off-heap, the JVM tuning that actually matters
- [JavaReflectionAndProxies](JavaReflectionAndProxies) — Where reflection earns its place; where it should be avoided
- [JavaSecurityModel](JavaSecurityModel) — Security manager era and what replaced it
- [JavaAnnotationProcessing](JavaAnnotationProcessing) — Compile-time code generation patterns

## Concurrency and IO

- [JavaLoggingBestPractices](JavaLoggingBestPractices) — SLF4J, structured logs, and the patterns that survive operational scale

## Build and test tooling

- [MavenMultiModuleProjects](MavenMultiModuleProjects) — Multi-module structure, BOM patterns, parent POM design
- [JavaBuildToolComparison](JavaBuildToolComparison) — Maven vs. Gradle vs. Bazel: when each is right
- [JunitFiveAdvancedFeatures](JunitFiveAdvancedFeatures) — Parameterized tests, nested test classes, extensions

## Persistence

- [JdbcBestPractices](JdbcBestPractices) — Connection management, prepared statements, batching
- [JpaAndHibernatePatterns](JpaAndHibernatePatterns) — When ORM helps; the patterns that make it survive

## Frameworks

- [SpringBootFundamentals](SpringBootFundamentals) — Starter dependencies, auto-configuration, the convention-over-configuration model
- [ServletArchitectureDeepDive](ServletArchitectureDeepDive) — Filters, listeners, the request lifecycle

## Adjacent clusters

- [Software Engineering Practices Hub](SoftwareEngineeringPracticesHub) — Language-agnostic practices that apply to Java codebases
- [Design Patterns Hub](DesignPatternsHub) — Pattern language with Java implementations
- [Web Services and APIs Hub](WebServicesAndApisHub) — REST, GraphQL, and protocol-level concerns
