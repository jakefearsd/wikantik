---
canonical_id: 01KQ0P44X51Q8RCW9GRHB00QYK
title: Symbolic Logic
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: Survey of symbolic logic — propositional, predicate, modal, temporal, higher-order
  — and the role of formal symbolic reasoning in computer science and mathematics.
tags:
- symbolic-logic
- mathematics
- formal-logic
- reasoning
related:
- PropositionalLogic
- PredicateLogic
- ModalLogic
- TemporalLogic
- SetTheoryLogic
hubs:
- Mathematics Hub
---
# Symbolic Logic

Symbolic logic is reasoning with formal symbols rather than natural language. Statements are written in precise symbolic notation; rules of inference manipulate the symbols. The result is reasoning that's checkable, mechanizable, and unambiguous.

This page surveys the major branches.

## Why symbolic

Natural language is ambiguous:
- "Every man loves some woman" — same woman for all men, or each man some woman?
- "Bill married Jane and they had children" — order ambiguous?

Symbolic logic forces precision:
- ∀x (Man(x) → ∃y (Woman(y) ∧ Loves(x, y))) — each man some woman
- ∃y (Woman(y) ∧ ∀x (Man(x) → Loves(x, y))) — same woman for all

Different formulas; different meanings.

## Levels of logic

### Propositional logic

Simplest. Statements are atomic; combined with connectives. See [PropositionalLogic](PropositionalLogic).

Decidable but limited expressiveness.

### Predicate logic (first-order)

Adds quantifiers and predicates. Most of mathematics is expressible. See [PredicateLogic](PredicateLogic).

Semi-decidable; rich expressiveness.

### Higher-order logic

Quantifies over predicates and functions, not just individuals.

More expressive than first-order; more complex.

Used in some theorem provers (HOL Light, Isabelle/HOL).

### Modal logic

Adds operators for necessity and possibility. See [ModalLogic](ModalLogic).

Variants: deontic, doxastic, epistemic, temporal.

### Temporal logic

Modal logic where modalities are temporal. See [TemporalLogic](TemporalLogic).

Specifically used for verification.

### Type theory

Alternative to set theory + logic. Mathematics built from types.

Used in Coq, Agda, Lean.

## Inference

Rules for deriving conclusions from premises.

### Natural deduction

Introduction and elimination rules for each connective.

For ∧:
- Intro: from P, Q derive P ∧ Q
- Elim: from P ∧ Q derive P (or Q)

For →:
- Intro: from assumption P proving Q, derive P → Q
- Elim (modus ponens): from P, P → Q derive Q

Closer to mathematical reasoning style.

### Sequent calculus

Manipulates sequents (sequences of formulas). Cleaner formalism for proof theory.

### Resolution

Refutation-based: to prove φ, show that ¬φ is unsatisfiable.

Computationally tractable; basis for automated theorem provers (Vampire, E).

### Tableau methods

Tree-based proof methods. Used in some theorem provers and decision procedures.

## Soundness and completeness

### Soundness

Every theorem is true. The proof system doesn't prove false things.

### Completeness

Every truth is a theorem. The proof system can prove all true statements.

For propositional logic: sound and complete.
For first-order logic: sound and complete (Gödel).
For some higher-order logics: incomplete.

## Gödel's incompleteness

Famous result: any sufficiently strong formal system either
- Is inconsistent (proves contradictions), or
- Is incomplete (some truths can't be proven within the system)

Specifically applies to systems strong enough to express arithmetic.

Implications:
- No "complete theory of mathematics" exists
- Some questions in math are undecidable from any given set of axioms
- Truth is not the same as provability in formal systems

## Decidability

A logic is decidable if there's an algorithm that determines truth or theoremhood.

- Propositional logic: decidable (truth tables)
- First-order logic: undecidable (Church)
- Specific decidable fragments: Presburger arithmetic, monadic predicate logic
- Modal logics: varies

For undecidable logics, semi-decidability often holds: provable formulas can be found; non-provable can't be confirmed.

## Computer science applications

### Theorem proving

Automated and interactive. Tools include:
- **Coq**: dependent type theory
- **Isabelle/HOL**: higher-order logic
- **Lean**: dependent type theory
- **Agda**: dependent type theory
- **Z3**: SMT solver (combines theories)
- **Vampire, E, SPASS**: first-order resolution provers

### Formal verification

Specifying programs as logical formulas; proving correctness.

Used in:
- Cryptographic protocols
- Hardware (CPU verification)
- Critical software (kernels, compilers)

### Programming languages

- **Prolog**: predicate logic as a programming language
- **Datalog**: subset for databases
- **Type systems**: based on logic (Curry-Howard correspondence)

### Knowledge representation

Logic-based ontologies, knowledge graphs, expert systems.

OWL, RDF, RuleML — logical frameworks.

### Database query languages

Relational algebra is logic. SQL is practically logic-based.

## Curry-Howard correspondence

Profound connection: types in programming languages correspond to propositions in logic.

- Type A → B corresponds to proposition A → B
- A program of type A → B is a proof that A implies B
- Termination corresponds to proof correctness

This connection underlies modern type-theoretic theorem provers.

## Modal logic variants

### Deontic logic

Obligation, permission, prohibition. Used in legal/ethical reasoning.

### Doxastic / epistemic logic

Belief and knowledge. Used in AI and game theory.

### Dynamic logic

Reasoning about programs as actions. Hoare logic is related.

### Linear logic

Resources and consumption. Used in some programming language theory.

## Specific theorems and results

### Compactness

A first-order theory is satisfiable iff every finite subset is satisfiable.

### Löwenheim-Skolem

A satisfiable first-order theory has a countable model.

These results constrain what first-order logic can express.

### Cut elimination

Proofs can be normalized to cut-free form. Enables decidability and complexity results.

### Soundness of first-order logic

Provable formulas are valid. Verified by Gödel.

## Common failure patterns

### Confusing levels of logic

Propositional, first-order, higher-order, modal — different levels with different properties.

### Treating provability as truth

In Gödel-incomplete systems, truth ≠ provability.

### Naive expressiveness assumptions

Some statements look first-order but require higher-order or modal.

### Ignoring decidability

Some logics are undecidable; algorithms may not terminate.

### Mistaking notation for understanding

Symbolic notation is precise; understanding what the symbols mean is the harder work.

## Further Reading

- [PropositionalLogic](PropositionalLogic) — Foundations
- [PredicateLogic](PredicateLogic) — First-order logic
- [ModalLogic](ModalLogic) — Necessity and possibility
- [TemporalLogic](TemporalLogic) — Time-based modal logic
- [SetTheoryLogic](SetTheoryLogic) — Foundational set theory
- [Mathematics Hub](Mathematics+Hub) — Cluster index
