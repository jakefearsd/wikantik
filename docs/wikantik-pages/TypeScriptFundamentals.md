---
canonical_id: 01KQ0P44Y39E5EY1XSMGSVJ2BQ
title: TypeScript Fundamentals
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: The TypeScript features that matter day-to-day — types you actually use,
  structural typing, generics, and the gradual adoption path for migrating JavaScript
  codebases.
tags:
- typescript
- javascript
- types
- frontend
related:
- ModernBundlersAndBuildTools
- WebComponents
- FormHandlingAndValidation
hubs:
- FrontendDevelopment Hub
---
# TypeScript Fundamentals

TypeScript adds static types to JavaScript. Compiled to JS for execution; types are checked at compile time. Modern frontend development is largely TypeScript; node back-ends increasingly are too.

This page covers the practical TypeScript that matters day-to-day.

## Why TypeScript

The case:
- Catches bugs at compile time (typos, wrong types, undefined access)
- Better IDE support (autocomplete, refactoring, navigation)
- Self-documenting (types are documentation)
- Refactoring safety (rename a field; compiler finds all uses)

The cost:
- Compilation step
- Initial learning curve
- Some edge cases require type gymnastics

For most non-trivial JavaScript projects, TypeScript pays for itself.

## The basics

### Types

```typescript
let name: string = "Alice";
let age: number = 30;
let active: boolean = true;
let scores: number[] = [1, 2, 3];
let user: { name: string, age: number } = { name: "Bob", age: 25 };
```

Most of the time, you don't write the type — TypeScript infers it.

### Functions

```typescript
function greet(name: string): string {
    return `Hello, ${name}`;
}

const greet2 = (name: string): string => `Hello, ${name}`;
```

Parameters and return types specified; everything else inferred.

### Interfaces and types

Two ways to declare object shapes:

```typescript
interface User {
    id: string;
    name: string;
    email?: string;  // optional
}

type User = {
    id: string;
    name: string;
    email?: string;
};
```

Mostly interchangeable. Use whichever is conventional in your codebase.

### Unions

```typescript
type Status = 'pending' | 'active' | 'cancelled';
```

A value of `Status` can only be one of those strings. Compiler checks.

### Generics

```typescript
function first<T>(items: T[]): T | undefined {
    return items[0];
}

const x = first([1, 2, 3]);  // T inferred as number
const y = first(['a', 'b']); // T inferred as string
```

The `<T>` is a type parameter — fills in based on usage.

## Structural typing

TypeScript types are structural, not nominal:

```typescript
interface Point { x: number; y: number; }

const p1: Point = { x: 1, y: 2 };
const p2: { x: number, y: number, z: number } = { x: 1, y: 2, z: 3 };

function distance(p: Point) { /* ... */ }
distance(p1);  // OK
distance(p2);  // Also OK — has the required fields
```

The shape matters; the name doesn't. This is unlike Java/C# (nominal typing).

## Common patterns

### Type narrowing

```typescript
function process(value: string | number) {
    if (typeof value === 'string') {
        // value is narrowed to string here
        return value.toUpperCase();
    }
    // value is narrowed to number here
    return value * 2;
}
```

The compiler tracks what type a variable has at each point.

### Discriminated unions

```typescript
type Result =
    | { kind: 'success', value: string }
    | { kind: 'error', error: string };

function handle(r: Result) {
    if (r.kind === 'success') {
        // r.value is accessible here
    } else {
        // r.error is accessible here
    }
}
```

The `kind` field discriminates between variants. Pattern matching emerges from narrowing.

### Optional chaining and nullish coalescing

```typescript
const value = obj?.prop?.nested ?? 'default';
```

`?.` returns undefined if anything in the chain is null/undefined.
`??` provides a default for null/undefined (not for empty strings or zero).

### Type assertions

```typescript
const elem = document.getElementById('foo') as HTMLDivElement;
```

Tell the compiler "trust me, it's this type." Use sparingly; it bypasses type checking.

### Utility types

TypeScript has built-in utility types:

```typescript
Partial<User>     // all fields optional
Required<User>    // all fields required
Readonly<User>    // all fields readonly
Pick<User, 'id' | 'name'>  // just specific fields
Omit<User, 'email'>        // exclude fields
```

Useful for transforming types.

## Strict mode

Enable strict checks in `tsconfig.json`:

```json
{
    "compilerOptions": {
        "strict": true
    }
}
```

This enables: noImplicitAny, strictNullChecks, strictFunctionTypes, etc. The strict mode catches dramatically more bugs than the loose mode.

For new projects, always start with strict.

## Adoption strategies

### New project

Start with TypeScript and strict mode. No transition.

### Existing JavaScript project

Two paths:

#### Big bang

Convert everything at once. Risky; usually delayed indefinitely.

#### Incremental

Add `tsconfig.json` with `allowJs: true`. Convert files one at a time. New files are TS; existing files stay JS until touched.

The incremental path is realistic. Some files may stay JS for years; that's fine.

### `any`

`any` is TypeScript's escape hatch — anything goes. Useful in transition; should be avoided in new code.

For unknown values, use `unknown` instead of `any`. `unknown` requires you to narrow before use.

## TypeScript and React

TypeScript works well with React:

```typescript
type Props = {
    name: string;
    age?: number;
};

const Greeting: React.FC<Props> = ({ name, age }) => (
    <div>Hello, {name}, age {age ?? 'unknown'}</div>
);

// Or
function Greeting({ name, age }: Props) {
    return <div>Hello, {name}</div>;
}
```

The function-component declaration is increasingly preferred over `React.FC`.

## What TypeScript doesn't fix

- Runtime errors that types can't model (network failures, etc.)
- Poor architecture (types don't make bad code good)
- Performance issues (type checking happens at compile time only)
- Bugs in third-party libraries

## Common failure patterns

- **`any` everywhere.** Defeats the purpose.
- **Type assertions to make errors go away.** Hides real bugs.
- **Over-engineered types.** Conditional types, mapped types when simple types would do.
- **Not using strict mode.** Misses many bugs.
- **Hand-writing types for API responses.** Generate from OpenAPI/GraphQL schema.

## Further Reading

- [ModernBundlersAndBuildTools](ModernBundlersAndBuildTools) — Build pipeline includes TS compilation
- [WebComponents](WebComponents) — Components benefit from typed props
- [FormHandlingAndValidation](FormHandlingAndValidation) — Type-safe form schemas
- [FrontendDevelopment Hub](FrontendDevelopment+Hub) — Cluster index
