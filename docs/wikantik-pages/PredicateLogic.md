---
summary: First-order logic — predicates, quantifiers, models, and the formal language
  underlying mathematics, automated reasoning, and computer science theory.
date: '2026-04-26'
cluster: mathematics
related:
- PropositionalLogic
- ModalLogic
- SetTheoryLogic
- SymbolicLogic
canonical_id: 01KQ0P44TQZK0A9NCE9GSMSDCM
type: article
title: Predicate Logic
tags:
- predicate-logic
- first-order-logic
- mathematics
- formal-logic
status: active
hubs:
- MathematicsHub
- PredicateLogic Hub
---

# Predicate Logic: The Language of Structure and Quantification

Predicate Logic (First-Order Logic, FOL) is the definitive formalization of mathematical and scientific reasoning. While propositional logic deals with opaque, atomic statements, predicate logic "opens the box," allowing us to reason about individual objects, their properties, and the complex relationships between them.

## 1. Spatial and Geometric Intuition

Predicate logic is fundamentally about the **mapping of properties onto a domain of space**.

### 1.1 Tarski’s World: Space as Model
The most effective spatial intuition for FOL is **Tarski’s World**, a "blocks world" where formulas describe a physical layout.
- **Predicates as Spatial Filters:** A predicate like $Cube(x)$ acts as a spatial filter, highlighting all regions of the domain occupied by cubes.
- **Binary Relations as Vectors:** $LeftOf(x, y)$ defines a directional vector between objects.
- **Quantification as Scanning:** 
    - **Universal ($\forall x$):** A "global scan" of the entire domain. The statement is true only if the property holds at every single coordinate.
    - **Existential ($\exists x$):** A "search operation" that terminates as soon as a single "witness" (an object satisfying the property) is found.

### 1.2 The Geometry of Elementary Geometry
Alfred Tarski proved that Euclidean geometry can be entirely reduced to FOL using only two primitives:
1. **Betweenness ($\beta(x, y, z)$):** $y$ lies on the segment $xz$.
2. **Equidistance ($\delta(x, y, z, w)$):** The distance $xy$ equals the distance $zw$.
This demonstrates that our geometric intuition of "space" is perfectly isomorphic to the logical structure of FOL.

## 2. Quantitative Foundations: Completeness and Complexity

The mathematical power of FOL comes with significant computational constraints.

| Property | Description | Quantitative Result |
| :--- | :--- | :--- |
| **Completeness** | Every valid formula is provable. | **Gödel’s Completeness Theorem (1929)** |
| **Satisfiability** | Can we find a model for a formula? | **Undecidable** (Church-Turing, 1936) |
| **Decision Status** | Can an algorithm always return T/F? | **Semi-Decidable** (Recursively Enumerable) |
| **Monadic Fragment**| Predicates with only 1 variable. | **Decidable** (NEXPTIME-complete) |

### 2.1 The Semi-Decidability Limit
While Gödel proved that a proof *always exists* for every valid FOL statement, Church and Turing proved there is no general algorithm to find it or to determine if a statement is *invalid*. If you run a theorem prover on an invalid FOL formula, it may run forever (the **Halting Problem**).

## 3. Real-World Applications

### 3.1 Database Theory: Relational Calculus
The foundation of all relational databases (SQL) is First-Order Logic.
- **Tuple Relational Calculus (TRC):** A declarative language where queries are expressed as FOL formulas. 
- **SQL WHERE Clause:** Directly implements the **Selection** operator of FOL. 
    - `SELECT * FROM Users WHERE Age > 18` is formally $\{ u \mid User(u) \land Age(u) > 18 \}$.

### 3.2 AI and Knowledge Representation
- **Prolog:** A logic programming language based on a subset of FOL called **Horn Clauses**. It uses **Resolution** (a form of automated deduction) to answer queries.
- **Semantic Web (OWL):** Uses "Description Logics" (decidable subsets of FOL) to allow machines to reason across distributed knowledge graphs.

### 3.3 Formal Verification
Engineers use FOL to write **Formal Specifications** for software. Tools like **Z3** or **Lean** use FOL to prove that a program's output will always satisfy its specification, a process essential for safety-critical systems like autonomous vehicles or flight controllers.

## 4. Syntax and Mechanics

A First-Order language consists of:
- **Variables:** $x, y, z$ (placeholders).
- **Constants:** $a, b, c$ (named individuals).
- **Predicates:** $P(x), R(x, y)$ (properties and relations).
- **Functions:** $f(x)$ (mappings that return objects).

### 4.1 De Morgan's Laws for Quantifiers
The spatial intuition of "all" vs. "some" is captured by the duality of negation:

$$ \neg \forall x P(x) \iff \exists x \neg P(x) $$
$$ \neg \exists x P(x) \iff \forall x \neg P(x) $$

*Visual Intuition:* "Not everything is a cube" is equivalent to "There is at least one thing that is not a cube."

## 5. Limits and Higher Orders

First-order logic is restricted to quantifying over **individuals**. 
- **FOL:** $\forall x (Human(x) \to Mortal(x))$ (Allowed).
- **Second-Order:** $\forall P (P(Socrates) \to P(Plato))$ (Forbidden).
Quantifying over properties themselves (Second-Order Logic) is vastly more expressive but loses the **Completeness** property—there is no finite proof system that can capture all Second-Order truths.

## Further Reading
- [PropositionalLogic](PropositionalLogic) — The atomic component of FOL.
- [SymbolicLogic](SymbolicLogic) — The broader formal framework.
- [SetTheoryLogic](SetTheoryLogic) — Where FOL is applied to build math.
- [MathematicsHub](MathematicsHub) — Central index for mathematical theory.
