---
canonical_id: 01KQ0P44R8VTQ8AJS4F10MBQBB
title: Java Annotation Processing
type: article
cluster: java
status: active
date: '2026-04-26'
summary: Compile-time code generation in Java — what annotation processors do, the
  popular processors (Lombok, MapStruct, Dagger), and when to write your own.
tags:
- java
- annotation-processing
- code-generation
- compile-time
related:
- JavaReflectionAndProxies
- JavaTwentyOneFeatures
- MavenMultiModuleProjects
- JavaBuildToolComparison
hubs:
- Java Hub
---
# Java Annotation Processing

Annotation processing is Java's compile-time code generation mechanism. Annotated source code is read by an annotation processor that emits additional source code or modifies bytecode. The result is the developer-facing benefits of reflection (less boilerplate) without the runtime cost.

This page is about how annotation processing works, the major processors that have stuck, and when writing your own pays.

## How it works

The Java compiler invokes annotation processors during compilation:

1. Compiler reads annotated source
2. Processors registered via `META-INF/services/javax.annotation.processing.Processor` get invoked
3. Each processor reads the AST, can emit new source files
4. New sources go through the same compilation rounds

The output: code generated at build time that the user never writes manually but uses directly.

## The major processors

### Lombok

The most-used Java annotation processor. Annotations like `@Getter`, `@Setter`, `@Builder`, `@Data`, `@Slf4j` generate the corresponding boilerplate at compile time.

```java
@Data
public class User {
    private final String email;
    private int loginCount;
}

// Lombok generates:
//   - constructor
//   - getEmail(), getLoginCount(), setLoginCount()
//   - equals(), hashCode(), toString()
```

The trade-offs:
- Less boilerplate to read and maintain
- IDE support requires a Lombok plugin
- Some operations modify bytecode in non-standard ways
- Records (Java 14+) replace much of Lombok's value-object use case

For new code, prefer records over `@Data`. Lombok still has uses (`@Slf4j`, `@Builder` on non-records, `@With`), but the case for `@Data` has weakened.

### MapStruct

Generates type-safe mappers between similar types — typically DTO ↔ entity:

```java
@Mapper
public interface OrderMapper {
    OrderDTO toDto(Order order);
    Order toEntity(OrderDTO dto);
}
```

MapStruct generates the implementation at compile time. Faster than reflection-based mapping (e.g., ModelMapper), with errors at compile time rather than runtime.

### Dagger / Hilt

Compile-time dependency injection. Faster than Spring at startup; generates code that does the wiring. Common in Android and performance-sensitive backends.

The trade-off: less flexible than runtime DI; the dependency graph must be expressible at compile time.

### Annotation processors in frameworks

Many frameworks use annotation processors:
- **JPA**: validation of `@Entity` annotations, generation of static metamodel classes
- **Spring**: Spring Boot's auto-configuration uses annotation processing for configuration metadata
- **Immutables**: generates immutable value classes from interface declarations
- **AutoValue**: similar to Immutables; alternative pattern

## When to write your own

Most teams should not. The cases where it pays:

- **Repeated boilerplate that doesn't fit existing processors.** Maybe domain-specific value types or DTO patterns.
- **Compile-time validation.** Catch invalid annotation usage at build time rather than runtime.
- **Code generation for repetitive APIs.** REST controllers, CLI commands, etc. derived from a single source of truth.

The cost:
- Annotation processors are non-trivial to write
- Debugging generated code is harder than handwritten code
- Build complexity increases

## Patterns to avoid

- **Annotation processing for runtime behavior.** Write the code if you mean it.
- **Generated code that hides important behavior.** When something goes wrong, the error message points at code the user didn't write.
- **Processors that depend on order of invocation.** Round-based compilation makes this fragile.
- **Stale generated sources.** Old generated code that doesn't match annotations causes compile failures or weird runtime behavior.

## A modern reasonable position

For most Java teams:

- Use Lombok selectively (`@Slf4j`, `@Builder`); prefer records for value objects
- Use MapStruct for non-trivial DTO mapping
- Don't write annotation processors unless the use case is clear
- Prefer compile-time generation over runtime reflection where the choice is clean

## Common failure patterns

- **Lombok everywhere without considering records.** Records often beat Lombok now.
- **MapStruct for trivial mappings.** Sometimes a simple constructor is clearer than annotation-driven generation.
- **Annotation processing as a maintenance burden.** Generated code that nobody understands.
- **IDE issues.** Some processors require IDE plugins; setup friction.
- **Build dependency confusion.** Processor JARs vs. generated-code JARs; getting the Maven scope wrong.

## Further Reading

- [JavaReflectionAndProxies](JavaReflectionAndProxies) — Runtime alternative
- [JavaTwentyOneFeatures](JavaTwentyOneFeatures) — Records reduce Lombok need
- [MavenMultiModuleProjects](MavenMultiModuleProjects) — Build-tool annotation processor configuration
- [JavaBuildToolComparison](JavaBuildToolComparison) — Annotation processor support across tools
- [Java Hub](Java+Hub) — Cluster index
