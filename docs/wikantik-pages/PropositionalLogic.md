---
canonical_id: 01KQ0P44TYD1JZNQE1MXEVJ34H
title: Propositional Logic
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: The basics of propositional logic — connectives, truth tables, tautologies,
  satisfiability — and the role it plays as foundation for predicate logic, automated
  reasoning, and digital circuits.
tags:
- propositional-logic
- mathematics
- formal-logic
- boolean
related:
- PredicateLogic
- SymbolicLogic
- ModalLogic
- SetTheoryLogic
hubs:
- Mathematics Hub
---
# Propositional Logic

Propositional logic is the foundation of formal reasoning. Statements (propositions) combine via logical connectives; truth values follow rigorous rules. From this simple base, much of mathematics, computer science, and digital circuits is built.

This page covers the practical understanding.

## Propositions

A proposition is a statement that's true or false:

- "It is raining"
- "2 + 2 = 4"
- "Socrates is mortal"

Symbolized by letters: P, Q, R, ...

## Connectives

Build complex statements from simple ones:

- **NOT (¬P)**: P is false
- **AND (P ∧ Q)**: both P and Q are true
- **OR (P ∨ Q)**: at least one of P, Q is true
- **IMPLIES (P → Q)**: if P then Q
- **IFF (P ↔ Q)**: P if and only if Q (P and Q have the same truth value)
- **XOR (P ⊕ Q)**: exactly one of P, Q is true

## Truth tables

Show truth values of compound statements for all combinations of inputs.

For P ∧ Q:

| P | Q | P ∧ Q |
|---|---|-------|
| T | T | T |
| T | F | F |
| F | T | F |
| F | F | F |

For P → Q:

| P | Q | P → Q |
|---|---|-------|
| T | T | T |
| T | F | F |
| F | T | T |
| F | F | T |

(P → Q is "vacuously true" when P is false.)

## Tautologies, contradictions, contingencies

- **Tautology**: always true (e.g., P ∨ ¬P)
- **Contradiction**: always false (e.g., P ∧ ¬P)
- **Contingency**: sometimes true, sometimes false (e.g., P)

## Logical equivalence

Two formulas are equivalent if they have the same truth values for all assignments.

Important equivalences:

### De Morgan's laws

- ¬(P ∧ Q) ↔ (¬P ∨ ¬Q)
- ¬(P ∨ Q) ↔ (¬P ∧ ¬Q)

### Distribution

- P ∧ (Q ∨ R) ↔ (P ∧ Q) ∨ (P ∧ R)
- P ∨ (Q ∧ R) ↔ (P ∨ Q) ∧ (P ∨ R)

### Implication

- (P → Q) ↔ (¬P ∨ Q)
- (P → Q) ↔ (¬Q → ¬P) (contrapositive)

### Double negation

- ¬¬P ↔ P

## Inference rules

Patterns of valid reasoning:

### Modus ponens

P, P → Q ⊢ Q

If P and "P implies Q" are true, then Q is true.

### Modus tollens

P → Q, ¬Q ⊢ ¬P

If "P implies Q" and Q is false, then P is false.

### Hypothetical syllogism

P → Q, Q → R ⊢ P → R

Implication is transitive.

### Disjunctive syllogism

P ∨ Q, ¬P ⊢ Q

If "P or Q" and not P, then Q.

## Normal forms

Canonical ways to write formulas.

### Disjunctive normal form (DNF)

Disjunction of conjunctions:
(A ∧ ¬B) ∨ (¬A ∧ B) ∨ (¬A ∧ ¬B)

Easy to evaluate; can be huge.

### Conjunctive normal form (CNF)

Conjunction of disjunctions:
(A ∨ B) ∧ (¬A ∨ ¬B)

Standard form for SAT solvers.

Every formula has a CNF and a DNF (possibly with exponential blow-up).

## Satisfiability (SAT)

Is there an assignment of truth values that makes a formula true?

The SAT problem is NP-complete (Cook-Levin theorem). One of the most-studied problems in computer science.

Modern SAT solvers can handle millions of variables in many real problems despite worst-case exponentiality.

Applications:
- Circuit verification
- Hardware design
- Software testing
- Constraint satisfaction
- Cryptography

## Boolean algebra

Algebraic structure with operations like AND, OR, NOT.

Equivalent to propositional logic but algebraic notation.

Boolean variables: 0 (false) and 1 (true).
- a · b = a AND b
- a + b = a OR b
- ā = NOT a

Used in digital circuit design and Boolean optimization.

## Digital circuits

Logical gates implement Boolean functions in hardware:
- NOT gate
- AND gate
- OR gate
- NAND, NOR (universal gates)
- XOR

Combinations of gates make complex circuits — adders, multiplexers, CPUs.

Boolean simplification reduces gate count, saving silicon area.

## Decidability

Propositional logic is decidable. Truth-table evaluation always terminates.

But: it can be expensive. With n variables, the truth table has 2^n rows. For 100 variables, that's astronomical.

SAT-solving heuristics work better than brute force in practice.

## Applications in CS

### Verification

Specifying program properties as propositional formulas; verifying.

### Type systems

Type checking can be encoded as logic problems.

### Database queries

Boolean queries over indexed data.

### Circuit minimization

Karnaugh maps, Quine-McCluskey algorithm reduce Boolean expressions.

### Symbolic execution

Programs as logical formulas; SAT solvers explore execution paths.

### Constraint solving

Many constraint problems reduce to SAT.

## Limitations

### Expressiveness

Propositional logic can't express:
- "All humans are mortal"
- "There exists a number greater than 5"

These require quantifiers — predicate logic. See [PredicateLogic](PredicateLogic).

### Modality

Can't express necessity, possibility, time, knowledge. See [ModalLogic](ModalLogic).

### Finite scope

Each proposition is atomic. To reason about "P about person X," you'd need separate propositions for each X — combinatorial explosion.

## Common failure patterns

- **Confusing implication direction.** P → Q is not the same as Q → P.
- **Forgetting "vacuously true" implications.** P → Q is true when P is false, regardless of Q.
- **Mixing English and formal logic.** Natural language is often ambiguous; formal logic forces precision.
- **Truth-table explosion.** Tractable for small n; intractable for large.
- **Confusing satisfiability with validity.** Different concepts.

## Further Reading

- [PredicateLogic](PredicateLogic) — Adding quantifiers
- [SymbolicLogic](SymbolicLogic) — Broader logic context
- [ModalLogic](ModalLogic) — Logic of necessity/possibility
- [SetTheoryLogic](SetTheoryLogic) — Foundational set theory
- [Mathematics Hub](Mathematics+Hub) — Cluster index
