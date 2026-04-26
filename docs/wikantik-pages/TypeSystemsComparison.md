---
title: Type Systems Comparison
type: article
cluster: programming-languages
status: active
date: '2026-04-25'
tags:
- type-systems
- typescript
- rust
- haskell
- go
summary: Static vs dynamic, structural vs nominal, gradual typing — and the
  type-system features (generics, sum types, traits, dependent types) that
  matter to working programmers.
related:
- ReactBestPractices
- DesignPatternsOverview
- AbstractAlgebra
hubs:
- ProgrammingLanguages Hub
---
# Type Systems Comparison

A type system is the rules a language uses to classify values and prevent operations from being applied to incompatible types. Type systems vary widely in expressiveness, when they check, what they check, and how much they get in your way.

This page is the working comparison: features that matter, tradeoffs, and what choosing a language with a given type system actually costs and buys.

## Static vs dynamic

- **Static**: types checked at compile time. Errors caught before run. Languages: Java, Rust, Go, Swift, TypeScript, Kotlin.
- **Dynamic**: types checked at runtime. Errors caught when the bad operation happens. Languages: Python, JavaScript, Ruby, PHP.

Static catches more errors earlier; tooling (autocomplete, refactoring) is better. Dynamic is faster to prototype and more flexible.

The 2020s consensus: most production codebases benefit from static typing. JS → TypeScript, Python → typed Python (gradual), Ruby → still mostly dynamic but with Sorbet / RBS adoption. The gap has narrowed; gradual typing solutions provide static-like benefits in dynamic languages.

## Strong vs weak

- **Strong**: implicit type conversions are minimised; mismatches error.
- **Weak**: implicit conversions abound (JavaScript's `"5" + 1 === "51"`).

Most modern languages are strong-ish. Weak typing introduces classes of bugs (silent miscoercion) that are nearly absent in strong languages.

## Structural vs nominal

- **Nominal**: types are equal if they have the same name. `class Person {...}` and `class User {...}` with same fields are different types.
- **Structural**: types are equal if they have the same shape. TypeScript, Go interfaces.

Nominal: more discipline; clearer intent. Structural: more flexibility; "duck typing" at compile time.

Most languages are mostly nominal. TypeScript is famously structural. Go interfaces are structural; structs are nominal.

## Gradual typing

Add type annotations to a dynamically-typed language. Untyped code coexists with typed.

- **TypeScript** — gradual typing on top of JavaScript. The type system is not sound (escape hatches via `any`, `as`); the practical value of types is huge regardless.
- **Python** — type hints (PEP 484+); checked by mypy, pyright, ruff. Optional; not enforced at runtime.
- **Sorbet, RBS** for Ruby. Less mature; growing.
- **Hack** for PHP, by Meta.

Gradual typing's value: incremental adoption, type benefits without full rewrite. Cost: type system limited by what's expressible without breaking dynamic semantics.

## Type-system features that matter

### Generics / parametric polymorphism

`List<T>` instead of `List` (where T is unknown until use). Type-safe collections, functions that work on any element type.

Every modern statically-typed language has them. Go held out until 1.18 (2022); now has them. Lack of generics in pre-1.18 Go was a notable deficiency.

### Sum types / tagged unions / algebraic data types

A type that's "either A or B or C," and the compiler forces you to handle each case.

```rust
enum Result<T, E> {
    Ok(T),
    Err(E),
}

match result {
    Ok(value) => ...,
    Err(error) => ...,
}
```

Eliminates entire bug categories: forgotten error cases, null-pointer-when-it-should-be-Some.

Languages with: Rust, Haskell, OCaml, Swift, Scala, Kotlin (sealed classes), TypeScript (discriminated unions), Python (with TypedDict and Literal — limited).

Languages without: Java (until pattern matching landed), Go, classic dynamic languages.

This is one of the highest-impact type features for code quality. Lacking it forces error-handling via exceptions, returning null/None, or convention.

### Traits / interfaces / type classes

A way to say "any type that has these methods." Polymorphism without inheritance.

- **Java / C# interfaces** — must explicitly implement.
- **Go interfaces** — structurally implemented; if the type has the methods, it implements the interface.
- **Rust traits** — explicitly implemented; can be added to existing types via `impl Trait for Type`.
- **Haskell type classes** — most flexible; instances declared separately from the type.

Trait-based polymorphism is generally cleaner than inheritance-based. Modern OO languages (Kotlin, Swift) increasingly favour traits / protocols over deep class hierarchies.

### Null safety

The "null reference" was called "the billion-dollar mistake" by its inventor (Tony Hoare). Modern languages address it:

- **Languages with non-nullable types by default**: Kotlin (`String` vs `String?`), Swift (`String` vs `String?`), Rust (`Option<T>`), TypeScript (with strict null checks).
- **Languages without**: Java (until `Optional` and `@NonNull` annotations; still non-default), Go (zero values; no nullability for compound types).

Non-null-by-default catches a huge class of bugs at compile time. The pain of being forced to handle nullability is dramatically less than the pain of NullPointerExceptions in production.

### Pattern matching

Decompose a value while binding variables. Exhaustiveness checked.

```rust
match user {
    User::Admin { permissions } => grant_admin_access(permissions),
    User::Regular { email } if is_verified(email) => grant_user_access(),
    User::Regular { .. } => deny(),
}
```

Most useful when combined with sum types. Java has pattern matching as of 21+; Python 3.10+ has match statements.

### Dependent types

Types that depend on values. `Vec<T, n>` where `n` is the length, known at compile time.

Languages: Idris, Coq, Lean (proof assistants); Rust (sort of, via const generics); F* (research).

Powerful for proving program properties; rarely used in industry. The marginal benefit doesn't justify the productivity cost for most engineering work.

### Type inference

The compiler figures out the type without explicit annotation.

```typescript
const x = 42;  // x is number, no annotation needed
```

All modern statically-typed languages have local type inference. Some go further:

- **Hindley-Milner** style (Haskell, OCaml, Rust): infers types across function boundaries.
- **Local-only** (Java, C#, Go): infers within expressions; function signatures must be annotated.

Type inference makes static typing less verbose. Without it, statically-typed code is annotation-heavy and feels like punishment.

## Choosing a language by type system

### Strong static + strict null + sum types + good inference

The sweet spot for correctness-sensitive work.

- **Rust** — most rigorous; borrow checker also prevents data races. Cost: steep learning curve.
- **Kotlin** — pleasant; null safety; sealed classes; runs on JVM with Java interop.
- **Swift** — Apple ecosystem; similar guarantees to Kotlin.
- **TypeScript** — gradual; less rigorous than the above (any escape hatch); but pragmatic for JavaScript ecosystems.
- **Scala** — powerful but complex; worth it for some teams; overkill for others.

### Strong static, simpler model

When you want safety without exotic features.

- **Go** — minimal; explicit; productive. Lacks generics until 1.18; lacks sum types; lacks null safety. Pragmatic for backend services where the simplicity is the point.
- **Java** — mainstream; mature ecosystem. Modern Java (21+) has pattern matching, sealed types, records — closer to Kotlin / Scala than ever.
- **C#** — similar story; strong typing; F# for the more functional flavour.

### Dynamic with optional types

For prototyping, scripting, data work.

- **Python** — dominant for data science, ML, scripting. Type hints + mypy / pyright catch many static errors.
- **JavaScript** — dominant for frontend; backend mostly TypeScript these days.
- **Ruby** — declined for new projects; Rails ecosystem still significant.

### Functional purity

For correctness through different means.

- **Haskell** — pure functional; effect tracking via monads. Rigorous; small industry footprint; valuable to learn.
- **OCaml** — practical functional; used in finance, compilers, type-system research.
- **Elixir / Erlang** — actor model; concurrent; dynamic but with strong runtime guarantees.

## What's worth your time

For practical impact:

- **Use a typed language for production code.** TypeScript over JavaScript, typed Python over untyped.
- **Use sum types where available.** They prevent more bugs than any other feature.
- **Use null safety where available.** Same.
- **Use pattern matching where available.** Cleaner than chains of `instanceof`.
- **Don't over-engineer types.** Type-level Sudoku produces unreadable code. Just because Haskell can express something doesn't mean it should.

The trend: mainstream languages are slowly absorbing what was specialty 10 years ago — null safety, sum types, pattern matching, traits. Java in 2026 looks more like Scala in 2016.

## What about runtime types vs static types

Runtime introspection (Python's `isinstance`, JavaScript's `typeof`, Java reflection) lets code make decisions based on type at runtime. Useful for serialisation, generic frameworks, dependency injection.

Modern static type systems (Rust, Haskell) intentionally have weak runtime introspection — the runtime doesn't know the type, because the type was a compile-time concept. Erased types: less flexible but more efficient and predictable.

Different trade-off. For framework-heavy code (Rails, Spring), runtime types help. For systems code, erased types help.

## Further reading

- [ReactBestPractices] — TypeScript in real frontend code
- [DesignPatternsOverview] — patterns that type systems can replace
- [AbstractAlgebra] — the math behind functional / dependent types
