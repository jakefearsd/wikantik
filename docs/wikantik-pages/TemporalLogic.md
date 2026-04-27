---
canonical_id: 01KQ0P44XFGA1ZZ7K4ZAVMJZ1A
title: Temporal Logic
type: article
cluster: mathematics
status: active
date: '2026-04-26'
summary: Logic for reasoning about time — LTL, CTL, the modal operators for "always",
  "eventually", "until" — and the central role in verification of concurrent and
  distributed systems.
tags:
- temporal-logic
- ltl
- ctl
- verification
- mathematics
related:
- ModalLogic
- PropositionalLogic
- PredicateLogic
hubs:
- Mathematics Hub
---
# Temporal Logic

Temporal logic extends classical logic with operators that reason about time. "Will the request eventually be served?" "Is this state always reached?" "Does the system always terminate?" — these are temporal-logic questions.

For concurrent and distributed systems, temporal logic is the standard framework. Model checkers verify temporal-logic specifications against system models.

## The basic operators

The major operators (Linear Temporal Logic, LTL):

- **G P** ("always P"): P is true at every future point
- **F P** ("eventually P"): P is true at some future point
- **X P** ("next P"): P is true at the next point
- **P U Q** ("P until Q"): P is true until Q becomes true

Equivalences:
- F P = true U P
- G P = ¬F¬P (always = not eventually not)

## LTL example

System specification:

- G(request → F response): "every request is eventually answered"
- G(¬(processA ∧ processB)): "processes A and B never run simultaneously"
- F G stable: "eventually, system reaches stable state forever"
- G F running: "infinitely often, system is running" (no permanent halt)

These are properties of execution traces.

## CTL: Computation Tree Logic

Considers the branching structure of possible futures. Adds path quantifiers:

- **A φ**: along all paths, φ holds
- **E φ**: there exists a path where φ holds

Combined with temporal operators:
- **AG P**: along all paths, always P
- **EF P**: there exists a path where eventually P
- **AF P**: along all paths, eventually P

Example: AG(request → AF response) — "in all possible futures, every request is eventually answered."

LTL talks about all paths; CTL allows reasoning per-path.

LTL and CTL have incomparable expressive power.

## Specification examples

### Safety

"Bad things never happen": G ¬bad

- G ¬deadlock: never deadlocks
- G ¬(rangeError): never out-of-bounds

### Liveness

"Good things eventually happen": F good

- F response: eventually responds
- G(request → F response): every request eventually answered

### Fairness

"Each process eventually executes": G F running_process

If a process is enabled infinitely often, it eventually runs.

### Mutual exclusion

G ¬(critical_A ∧ critical_B): processes A and B not simultaneously in critical section.

## Model checking

Given a system model M and temporal formula φ, model checking determines if M satisfies φ.

For finite-state systems, model checking is decidable. Modern checkers can handle very large state spaces.

Tools:
- **NuSMV**: CTL model checker
- **SPIN**: LTL model checker
- **TLA+**: TLA (Temporal Logic of Actions); model checker TLC
- **UPPAAL**: real-time temporal logic
- **PRISM**: probabilistic model checker

For concurrent and distributed systems, model checking finds bugs that testing misses.

## Real-time temporal logic

Adds quantitative time:

- F_{<5} P: P happens within 5 time units
- G(request → F_{<10} response): every request answered within 10 time units

Used in real-time systems.

## Probabilistic temporal logic

PCTL: combines temporal logic with probability:

- P_{>0.99} (G stable): probability > 99% that system is always stable

Used in probabilistic system verification.

## Specific applications

### Concurrent program verification

Specifying and verifying:
- Mutual exclusion
- Deadlock freedom
- Fairness
- Eventual termination

### Distributed system verification

- Consensus protocols (Paxos, Raft)
- Byzantine fault tolerance
- Consistency properties

### Hardware verification

- CPU pipeline correctness
- Cache coherence
- Communication protocols

### Specification of software systems

TLA+ used at Amazon, Microsoft, MongoDB, Cosmos DB for designing distributed systems.

## Model checking workflow

1. Build a model of the system (often as a state machine)
2. Specify properties as temporal-logic formulas
3. Run model checker
4. Get result: property holds, or counterexample trace
5. Fix issues; repeat

The counterexample is the practical value: when a property fails, the model checker shows exactly why.

## State explosion

Model checking faces state-space explosion: number of states grows exponentially with concurrency.

Techniques:
- Symbolic model checking (BDDs)
- Bounded model checking
- Abstraction
- Compositional verification

For very large systems, model checking is hard. But: for many practical concurrent designs, it works well.

## Specific verification successes

### Pentium FDIV bug

Caught in retrospective formal verification. Real-world cost from a missed bug led to investment in formal methods.

### TLA+ at Amazon

Specifications of S3, DynamoDB, EBS, etc. Found bugs in production systems.

### Byzantine consensus protocols

Many distributed protocols formally verified.

### Amazon Web Services

Heavy use of TLA+ for design verification.

## Common failure patterns

### Specifying the wrong property

Verification correct; property is wrong. Not all formal methods catch this.

### State-space too large

Naive modeling produces explosion; verification times out.

### Modeling errors

Model differs from real system. Verification of model may not apply to system.

### Ignoring liveness

Easy to focus on safety (nothing bad); harder to specify liveness (something good).

### Missing fairness assumptions

Fairness assumptions on schedulers, environment matter; easy to miss.

## When you'd use temporal logic

For most software engineers: rarely directly. But:

- Concurrent / distributed system designers: yes
- Hardware verification: yes
- Critical systems (medical, avionics, finance): yes
- Anyone using TLA+: yes

The mental model (G, F, etc.) is useful even without formal use.

## Further Reading

- [ModalLogic](ModalLogic) — Modal logic foundation
- [PropositionalLogic](PropositionalLogic) — Logic basics
- [PredicateLogic](PredicateLogic) — Quantified extension
- [Mathematics Hub](Mathematics+Hub) — Cluster index
