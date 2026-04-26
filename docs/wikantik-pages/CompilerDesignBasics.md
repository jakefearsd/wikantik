---
title: Compiler Design Basics
type: article
cluster: programming-languages
status: active
date: '2026-04-25'
tags:
- compiler
- parser
- lexer
- ir
- codegen
summary: The compiler pipeline — lex, parse, semantic analysis, IR, optimisation,
  codegen — explained for engineers who'll never write a production compiler
  but encounter compiler-shaped problems regularly.
related:
- DataStructures
- TypeSystemsComparison
- AbstractAlgebra
hubs:
- ProgrammingLanguages Hub
---
# Compiler Design Basics

Most engineers won't write a compiler for a real language. They will write parsers, evaluators, query optimisers, DSL processors, configuration interpreters, code transformations — all compiler-shaped problems.

Knowing the canonical compiler pipeline helps. This page is the working tour: what each phase does, what tools handle it, and what each phase fails at.

## The phases

A traditional compiler's pipeline:

```
source code
    │
    ▼ lexer (tokenise)
tokens
    │
    ▼ parser (parse to AST)
abstract syntax tree
    │
    ▼ semantic analysis (type-check, resolve names)
annotated AST
    │
    ▼ lowering to IR
intermediate representation
    │
    ▼ optimisation passes
optimised IR
    │
    ▼ codegen (target-specific)
machine code / bytecode / target language
```

Each phase has well-known techniques, tools, and failure modes.

## Lexer (tokeniser)

Converts text into tokens — keywords, identifiers, literals, punctuation.

```
"if (x > 5) { return; }"

→ IF, LPAREN, IDENT("x"), GT, INT(5), RPAREN, LBRACE, RETURN, SEMICOLON, RBRACE
```

Implementation:

- **Regular expressions** for token patterns.
- **State machine** built from those regexes; runs in linear time.

Tools:

- **`re2c`** — generates C lexers.
- **`flex`** — UNIX classic.
- **`lex.rs`, `logos`** — Rust.
- **`ply.lex`** — Python.
- **`antlr4`** lexer subset.

For ad-hoc parsing, hand-rolled lexers are common; for production languages, generators help.

## Parser

Converts tokens into a structured tree (Abstract Syntax Tree).

Two main parsing styles:

### Recursive descent (top-down)

Hand-write functions for each grammar rule:

```python
def parse_expression():
    left = parse_term()
    while peek() in (PLUS, MINUS):
        op = consume()
        right = parse_term()
        left = BinaryOp(left, op, right)
    return left

def parse_term():
    # ... etc
```

Strengths: easy to write, debug, and understand. Good error messages. Used by most modern compilers (Rust, Go, GCC, clang) for production use.

Weaknesses: have to handle precedence and associativity manually; can be tedious.

### Generated parsers (bottom-up)

Specify grammar in a generator's syntax; tool produces a parser.

Tools:

- **`yacc` / `bison`** — UNIX classic; LALR(1).
- **`antlr4`** — modern; LL(*); more flexibility.
- **`tree-sitter`** — incremental parsing for editors; recovery is excellent.
- **PEG parsers** (`pest`, `lark`, `peg.js`) — easier-to-read grammars; potentially slower.

Generated parsers handle precedence in the grammar; less hand-coding. Trade-offs: error messages from generated parsers are often worse; debugging is awkward; a grammar conflict surfaces as a generator error you have to interpret.

For most production compilers, recursive descent has won. For DSLs and quick languages, generators are faster to ship.

### Pratt parsing

A specific recursive-descent style that elegantly handles operator precedence. Once-niche; increasingly the default for new compilers.

```
prefix table: { LPAREN: parse_paren, MINUS: parse_unary, ... }
infix table:  { PLUS: (binop, 10), TIMES: (binop, 20), ... }

parse_expr(min_precedence) {
    left = prefix[token]()
    while infix[peek()].precedence >= min_precedence:
        ...
}
```

Concise; flexible; production-grade. Worth learning.

## Semantic analysis

After parsing, the AST is structurally valid but not semantically correct. This phase:

- **Resolves names**: identifiers refer to which declarations.
- **Type-checks**: do types match where they're used.
- **Catches static errors**: undefined variables, type mismatches, illegal references.

Implementation: walk the AST; maintain a symbol table per scope; emit errors when invariants violated.

For complex type systems (Rust's borrow checker, Haskell's type inference), this phase contains most of the language's complexity. Hindley-Milner inference, generic resolution, trait bound checking — all here.

## Intermediate Representation (IR)

After type-checking, the AST is lowered to a simpler representation. The IR strips syntax noise and expresses computation in a canonical form.

Common IR shapes:

- **Three-address code (TAC)** — flat sequences of `result = op a b` instructions.
- **Static Single Assignment (SSA)** — each variable assigned once; common in modern optimising compilers.
- **Continuation-passing style (CPS)** — control flow via continuations.

The IR is what optimisation passes operate on. AST optimisations are limited; IR optimisations compose well.

Production compilers:

- **LLVM IR** — the dominant IR for new compilers (Rust, Swift, modern C/C++).
- **MLIR** — Multi-Level IR, for higher-level abstractions before going to LLVM.
- **JVM bytecode** for Java family.
- **CIL** for .NET.

Targeting LLVM means you skip the codegen phase — LLVM's backend produces the actual machine code.

## Optimisation

Transformations that preserve semantics while improving performance / size / clarity.

Common optimisations:

- **Constant folding** — compute compile-time-constant expressions.
- **Dead code elimination** — remove code that has no effect.
- **Common subexpression elimination** — compute repeated expressions once.
- **Loop unrolling** — replace small loops with their unrolled form.
- **Function inlining** — replace function calls with the function's body.
- **Strength reduction** — replace expensive operations (multiplication) with cheaper ones (shifts).
- **Tail-call optimisation** — turn tail-recursive functions into loops.

Each is well-understood; LLVM has decades of accumulated passes.

For your hand-rolled language: constant folding and dead-code elimination are worth implementing; the rest depends on what your language does.

## Codegen

The final phase: generate the target output.

Targets:

- **Machine code** (x86-64, ARM64). Hardest; most compilers offload to LLVM.
- **Bytecode** (JVM, .NET, WebAssembly). Simpler; portable.
- **Another high-level language** (transpilers — TypeScript→JavaScript, ReScript→JavaScript).
- **An interpreter's IR** for direct execution.

Codegen for native machine code involves register allocation, instruction selection, scheduling. Each is a research area; LLVM handles all of them.

For most modern language projects: target LLVM IR; LLVM does the codegen for every supported architecture.

## Common compiler-shaped problems

When you'll write compiler-like code without knowing it:

### Query optimisers

A query is parsed; a logical plan is built; rewrites apply; cost-based optimisation picks an execution plan.

Compiler patterns: parsing, semantic analysis, IR transformations, plan selection. PostgreSQL, BigQuery, every database has one.

### Templating engines

Mustache, Jinja2, Handlebars. Parse the template; walk the AST applying values; produce output.

### Configuration languages

YAML / JSON / TOML / HCL / Cue. Each is parsed; some have semantics (HCL has expressions; Cue has constraints); generate effective config.

### DSL processors

A specific in-house mini-language. Parse, validate, execute / transpile.

### Code transformers

Babel for JavaScript, AST tools for Python, Rust's macro_rules and proc_macro. Walk an AST; apply transformations; emit modified code.

Knowing compiler basics makes these tasks tractable instead of feeling exotic.

## A pragmatic mini-compiler

For a small expression language:

```
Source: "(2 + 3) * 4"

Lexer (regex / hand-rolled):
  LPAREN, INT(2), PLUS, INT(3), RPAREN, TIMES, INT(4)

Parser (recursive descent / Pratt):
  BinaryOp(BinaryOp(2, '+', 3), '*', 4)

Evaluator (walk AST):
  visit(BinaryOp(2, '+', 3)) → 5
  visit(BinaryOp(5, '*', 4)) → 20
```

That's interpretation. For compilation, instead of evaluating, emit code:

```
PUSH 2
PUSH 3
ADD
PUSH 4
MUL
```

This minimal pipeline scales surprisingly far. Most production DSLs aren't much more complicated.

## What to skip if you're not building a compiler

- **Optimisation passes for fun.** Use LLVM if you need them.
- **Register allocation.** LLVM, again.
- **Assembly output.** LLVM.

Most engineers writing compiler-shaped code stop at IR generation. The interpretation or transformation step takes over from there.

## Tools worth knowing

- **`tree-sitter`** — incremental parsing; powers GitHub code-aware features and many editors. For analysing code in arbitrary languages, it's the right tool.
- **`antlr4`** — for grammar-driven parsing.
- **LLVM** — if you're producing native code.
- **`ast-grep`, `comby`** — pattern-based code transformation.
- **`Rascal`, `JetBrains MPS`** — language-workbench tools for serious DSL development.

## A pragmatic recommendation

For most engineers:

1. **Learn recursive descent + Pratt parsing.** Sufficient for most real DSL needs.
2. **AST visitors** as the standard way to analyse / transform.
3. **Don't write a full compiler** unless your job is "language designer."
4. **For analysing other languages' code**, use `tree-sitter` or the language's official AST library.
5. **For building DSLs**, evaluate "is this really a DSL or just a configuration language?" Often the latter is enough.

## Further reading

- [DataStructures] — ASTs, symbol tables
- [TypeSystemsComparison] — semantic analysis context
- [AbstractAlgebra] — for the formal-language theory underneath
