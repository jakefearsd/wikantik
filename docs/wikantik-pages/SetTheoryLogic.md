---
canonical_id: 01KQ0P44WC15BBHEDPRSEP3JW2
title: Set Theory and Logic
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: The foundational role of set theory in mathematics — Zermelo-Fraenkel axioms,
  cardinality, the role of choice, paradoxes — and the connection to logic and computer
  science.
tags:
- set-theory
- mathematics
- foundations
- zfc
related:
- PropositionalLogic
- PredicateLogic
- InfinityMathematics
- TopologyMathematics
hubs:
- Mathematics Hub
---
# Set Theory and Logic

Set theory is the standard foundation of mathematics. Almost everything in math is defined ultimately in terms of sets. The connection to logic is intimate — set theory is formulated in first-order logic, and logical reasoning is grounded in set-theoretic concepts.

This page covers the foundations.

## Sets

A set is a collection of objects (its members or elements). Specified by:
- Listing: {1, 2, 3}
- Defining property: {x : x is even}

The empty set ∅ has no elements.

Two sets are equal iff they have exactly the same elements.

## Operations

- **Union**: A ∪ B (in A or B)
- **Intersection**: A ∩ B (in both)
- **Difference**: A \ B (in A but not B)
- **Complement**: A^c (everything not in A; relative to a universe)
- **Cartesian product**: A × B (ordered pairs)
- **Power set**: P(A) (all subsets of A)

## The naive paradox

In naive set theory: any property defines a set.

Russell's paradox: let R = {x : x ∉ x}. Is R ∈ R?
- If yes, then R doesn't satisfy x ∉ x, contradicting membership.
- If no, then R satisfies the property, so it should be in R.

Either way, contradiction.

Resolution: not every property defines a set. Need axioms restricting set formation.

## Zermelo-Fraenkel set theory (ZF)

Standard axiomatic foundation. Specifies which set-formation operations are allowed.

Key axioms:
- **Extensionality**: sets with same elements are equal
- **Pairing**: for any a, b, the set {a, b} exists
- **Union**: for any set of sets, their union exists
- **Power set**: for any set A, P(A) exists
- **Infinity**: an infinite set exists
- **Replacement**: image of a set under a function is a set
- **Foundation**: sets cannot contain themselves (avoids paradoxes)

These rule out Russell's paradox while allowing all of standard mathematics.

## ZFC: ZF + Axiom of Choice

The axiom of choice (AC):

For any collection of non-empty sets, there's a function picking one element from each.

Sounds obvious; has surprising consequences.

### Consequences of choice

- Every vector space has a basis
- Tychonoff's theorem (compactness)
- Well-ordering theorem (every set can be well-ordered)

### Paradoxes from choice

- **Banach-Tarski**: decompose a sphere into pieces; rearrange into two spheres of same size
- Non-measurable sets exist
- Some "paradoxical" decompositions

These are mathematical realities; they don't violate physics because they involve non-constructible sets.

### Independence

Like the continuum hypothesis, AC is independent of ZF. Both AC and ¬AC are consistent with ZF.

Most mathematics assumes AC; some constructive mathematics avoids it.

## Cardinality

The "size" of a set.

For finite sets: just count.

For infinite sets: cardinality measured by bijection (one-to-one correspondence).

- |ℕ| = ℵ₀ (countable infinity)
- |ℝ| = 2^ℵ₀ (uncountable; the continuum)

See [InfinityMathematics](InfinityMathematics) for more.

## Ordinal numbers

Generalizations of natural numbers to infinite well-orderings.

Finite ordinals: 0, 1, 2, 3, ...
First infinite ordinal: ω
Then: ω+1, ω+2, ..., ω·2, ..., ω², ..., ω^ω, ...

Different infinite orderings have different ordinals, even if same cardinality.

## Relations

A relation R on A × B is a subset of A × B (set of ordered pairs).

Properties of relations on a single set:
- **Reflexive**: aRa for all a
- **Symmetric**: aRb implies bRa
- **Transitive**: aRb and bRc imply aRc
- **Antisymmetric**: aRb and bRa imply a = b

Combinations:
- **Equivalence**: reflexive + symmetric + transitive
- **Partial order**: reflexive + antisymmetric + transitive
- **Total order**: partial order + comparable (any two elements related either way)

## Functions

A function f: A → B is a relation where each a ∈ A relates to exactly one b ∈ B.

Properties:
- **Injective** (one-to-one): different inputs give different outputs
- **Surjective** (onto): every output is hit
- **Bijective**: both injective and surjective

Bijections establish equal cardinality.

## Set theory as foundation

In modern math, everything is built from sets:

- Numbers (Peano construction): 0 = ∅, 1 = {0}, 2 = {0, 1}, ...
- Ordered pairs: (a, b) = {{a}, {a, b}}
- Functions: sets of ordered pairs satisfying function property
- Real numbers: Cauchy sequences or Dedekind cuts
- Spaces, structures, etc.

The "type" of mathematical object is determined by its set-theoretic encoding.

## Connection to logic

Set theory is formulated in first-order logic. ZFC axioms are first-order formulas.

But: first-order logic plus ZFC is sufficient for almost all mathematics.

This unifies foundations: math is FOL + sets.

## Computer science applications

### Type systems

Many type systems are set-theoretic. Types are sets; type membership is set membership.

### Databases

Relational databases are essentially set theory + first-order logic.

### Programming languages

Set abstractions in many languages (Set<T> in Java, Python sets).

### Formal methods

Verification languages (Z, B, Alloy) are based on set theory.

### Knowledge representation

Knowledge graphs and ontologies are often set-theoretic.

## Variants and alternatives

### Class theory

Some structures are "too big" to be sets (the class of all sets, all groups, etc.). Class theory handles these.

NBG and Morse-Kelley are class theories extending ZFC.

### Constructive set theory

Avoids axiom of choice and law of excluded middle. Basis for constructive mathematics.

### Type theory

Modern alternative foundation. Mathematics built from types rather than sets.

Used by proof assistants (Coq, Agda, Lean).

### Category theory

Even more abstract foundation. Math built from categories and functors.

## Common failure patterns

- **Treating sets like physical containers.** Sets are abstract; "containing" doesn't have physical meaning.
- **Confusing membership and subset.** a ∈ A vs. {a} ⊆ A.
- **Naive set formation.** Not every property defines a set; Russell's paradox.
- **Ignoring foundational issues.** Most working mathematicians don't worry about foundations; sometimes it matters.
- **Conflating different infinities.** Multiple sizes of infinity.

## Further Reading

- [PropositionalLogic](PropositionalLogic) — Logical foundation
- [PredicateLogic](PredicateLogic) — First-order foundation
- [InfinityMathematics](InfinityMathematics) — Cardinalities
- [TopologyMathematics](TopologyMathematics) — Built on set theory
- [Mathematics Hub](Mathematics+Hub) — Cluster index
