---
canonical_id: 01KQ0P44TQZK0A9NCE9GSMSDCM
title: Predicate Logic
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: First-order logic — predicates, quantifiers, models, and the formal language
  underlying mathematics, automated reasoning, and computer science theory.
tags:
- predicate-logic
- first-order-logic
- mathematics
- formal-logic
related:
- PropositionalLogic
- ModalLogic
- SetTheoryLogic
- SymbolicLogic
hubs:
- Mathematics Hub
---
# Predicate Logic

Predicate logic (first-order logic, FOL) extends propositional logic with predicates and quantifiers. It can express statements like "for every x, there exists a y such that P(x,y)" — most of mathematics is naturally expressed in first-order logic.

For computer science: theorem proving, formal verification, database query languages, knowledge representation all build on predicate logic.

## What it adds to propositional logic

[Propositional logic](PropositionalLogic) handles statements like "rain implies wet ground." Each statement is atomic — true or false as a whole.

Predicate logic adds:

### Predicates

Statements with variables: P(x), Q(x,y), R(x,y,z).

- IsHuman(socrates): "Socrates is human"
- LessThan(x, y): "x < y"
- Likes(alice, bob): "Alice likes Bob"

### Quantifiers

- ∀x (forall): "for every x"
- ∃x (exists): "there exists an x"

Expressing more complex statements:

- ∀x (IsHuman(x) → IsMortal(x)): "All humans are mortal"
- ∃x (IsRed(x) ∧ IsApple(x)): "There exists a red apple"

### Functions

f(x), g(x,y) returning values.

- mother(x): "x's mother"
- max(a, b): "maximum of a and b"

### Equality

a = b: "a equals b"

## Syntax

A formula in predicate logic uses:
- Variables (x, y, z)
- Constants (a, b, c, specific values)
- Functions (f, g)
- Predicates (P, Q)
- Logical connectives (¬, ∧, ∨, →, ↔)
- Quantifiers (∀, ∃)

Example:
∀x ∃y (IsParent(x, y) → Loves(x, y))
"For every x, there exists a y, such that if x is the parent of y, then x loves y."

## Models and interpretation

A model gives meaning to symbols:
- Domain: the set of objects (e.g., people, numbers)
- Interpretation of constants: which objects they refer to
- Interpretation of predicates: which tuples satisfy them
- Interpretation of functions: how they map inputs to outputs

A formula is true in a model if it evaluates to true under the interpretation.

A formula is valid if it's true in every model. Tautologies in predicate logic.

A formula is satisfiable if true in at least one model.

## Logical equivalences

### Negation of quantifiers

¬∀x P(x) ↔ ∃x ¬P(x)
¬∃x P(x) ↔ ∀x ¬P(x)

"Not all are P" iff "Some are not P."
"None is P" iff "All are not P."

### Distribution

∀x (P(x) ∧ Q(x)) ↔ ∀x P(x) ∧ ∀x Q(x)
∃x (P(x) ∨ Q(x)) ↔ ∃x P(x) ∨ ∃x Q(x)

But not:
∀x (P(x) ∨ Q(x)) ≢ ∀x P(x) ∨ ∀x Q(x) (general)

### Quantifier scope

∀x ∀y P(x,y) ↔ ∀y ∀x P(x,y)
∃x ∃y P(x,y) ↔ ∃y ∃x P(x,y)

But ∀x ∃y P(x,y) ≢ ∃y ∀x P(x,y) (order matters with mixed quantifiers).

## Inference

Rules for deriving conclusions:

### Universal instantiation

From ∀x P(x), conclude P(a) for any specific a.

### Existential generalization

From P(a), conclude ∃x P(x).

### Universal generalization

From P(x) for arbitrary x (no specific assumption about x), conclude ∀x P(x).

### Existential instantiation

From ∃x P(x), introduce a new constant c and conclude P(c). (c is a "witness.")

Plus all the propositional rules.

## Formal systems

Various proof systems for first-order logic:
- **Natural deduction**: introduction and elimination rules for each operator
- **Sequent calculus**: rules for sequences of formulas
- **Resolution**: refutation-based; well-suited to automated proving

Modern theorem provers (Prolog, Z3, Coq) use variants of these.

## What predicate logic can express

Almost all of mathematics. Set theory (ZFC) is formulated in predicate logic. Number theory, algebra, analysis — all expressible.

Database queries (relational algebra, SQL) are essentially predicate-logic queries.

## Limitations

### First-order vs. higher-order

In first-order logic, you can quantify over individuals but not over predicates or functions.

You cannot express "for every property P, ..." — that's higher-order logic.

For most mathematical purposes, first-order is sufficient (with set theory underneath).

### Decidability

The set of valid first-order formulas is not decidable (Church-Turing theorem). No algorithm decides validity for arbitrary formulas.

But it is semi-decidable: if a formula is valid, an algorithm can find a proof; if not valid, may run forever.

### Compactness and Löwenheim-Skolem

Surprising properties:
- Compactness: a set of formulas is satisfiable iff every finite subset is satisfiable
- Löwenheim-Skolem: a satisfiable set of formulas has a countable model

These have philosophical implications about expressive power of first-order logic.

## Applications

### Mathematics

The standard formalization. Theorems and proofs in set theory, algebra, etc.

### Automated theorem proving

Tools like Z3, CVC4, Vampire prove or disprove first-order formulas.

Used in:
- Software verification (proving programs satisfy specifications)
- Hardware verification
- Mathematical research (proving theorems automatically)

### Database query languages

Relational algebra, SQL, Datalog — all logic-based query languages.

A SQL query is essentially a predicate-logic formula describing the desired result.

### Knowledge representation

OWL, RDF, knowledge graphs use logical frameworks.

Reasoners derive new facts from a knowledge base using inference rules.

### Programming languages

Prolog is essentially predicate logic as a programming language.

### Formal verification

Express specifications as predicate-logic formulas; verify implementations satisfy them.

## Common failure patterns

- **Confusing universals and existentials.** "∀x ∃y" and "∃y ∀x" are very different.
- **Implicit quantification.** When natural language is ambiguous about which quantifier; formal expression forces explicitness.
- **Forgetting the domain.** "For all x" — over what set?
- **Treating first-order as universally expressive.** Some concepts need higher-order.

## Further Reading

- [PropositionalLogic](PropositionalLogic) — Foundational
- [ModalLogic](ModalLogic) — Adding modality
- [SetTheoryLogic](SetTheoryLogic) — Where first-order grounds
- [SymbolicLogic](SymbolicLogic) — Broader framework
- [Mathematics Hub](Mathematics+Hub) — Cluster index
