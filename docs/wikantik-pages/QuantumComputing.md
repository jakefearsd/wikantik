---
canonical_id: 01KQ0P44V2T7HE5X0A9ATF9H87
title: Quantum Computing
type: article
cluster: computer-science-foundations
status: active
date: '2026-04-26'
summary: Quantum computing — qubits, superposition, entanglement, the major algorithms,
  the realistic state of hardware, and what software engineers should and shouldn't
  expect from quantum technology in the foreseeable future.
tags:
- quantum-computing
- computer-science
- qubits
- algorithms
related:
- MemoryArchitectures
hubs:
- Computer Science Foundations Hub
---
# Quantum Computing

Quantum computing exploits quantum mechanical phenomena (superposition, entanglement) to perform computations classical computers can't do efficiently — for specific problem types.

Hype is high; reality is more nuanced. This page covers what quantum computing actually is, what it can do, and what's realistic to expect.

## Classical vs quantum bits

### Classical bit

0 or 1.

### Qubit

A superposition of 0 and 1. Mathematically: α|0⟩ + β|1⟩ where |α|² + |β|² = 1.

When measured: 0 with probability |α|², 1 with probability |β|².

A qubit is described by 2 complex numbers; n qubits by 2^n complex numbers. Quantum's exponential power.

## Key concepts

### Superposition

Qubit is in multiple states simultaneously until measured.

### Entanglement

Qubits become correlated such that measuring one determines the other.

Powerful for some algorithms; can't be created classically.

### Interference

Quantum amplitudes can add or cancel. Algorithms manipulate amplitudes to cancel wrong answers and reinforce right ones.

### Measurement

Collapses superposition. Returns one classical outcome with probability determined by amplitudes.

Measurement is not "free observation" — it changes the state.

### No-cloning theorem

Can't copy an arbitrary unknown quantum state. Has implications for cryptography and error correction.

## Quantum gates

Quantum analog of classical logic gates. Reversible (must be — quantum mechanics is reversible).

### Single-qubit gates

- Hadamard (H): creates superposition
- Pauli-X: bit flip (NOT)
- Pauli-Z: phase flip
- Rotation gates

### Multi-qubit gates

- CNOT: controlled NOT (classical controls quantum NOT)
- Toffoli: 3-qubit controlled-controlled-NOT
- Phase gates

A small set of universal gates can express any quantum computation (analog of classical NAND).

## Quantum algorithms

### Shor's algorithm

Factor large integers in polynomial time. Threatens RSA cryptography.

This is the algorithm that motivates quantum computing for cryptanalysis.

### Grover's algorithm

Search unstructured database in O(√n) instead of O(n). Quadratic speedup.

Affects symmetric cryptography (effectively halves key length).

### Quantum simulation

Simulate quantum systems. Native task; classical computers struggle.

Likely the first practical application of quantum computers.

### HHL algorithm

Solve linear systems exponentially faster (with caveats).

The caveats matter: input/output preparation can dominate.

### Quantum approximate optimization (QAOA)

Heuristic for combinatorial optimization. Mixed evidence on quantum speedup.

### Variational quantum eigensolvers (VQE)

Find ground state of Hamiltonian. Used in chemistry simulation.

### Quantum machine learning

Various proposals; mostly research. No clear quantum advantage in current hardware.

## Algorithm classes by speedup

### Exponential speedup (Shor's)

Specific to algebraic problems. Doesn't apply to arbitrary tasks.

### Quadratic speedup (Grover's)

For unstructured search. Modest in practice.

### Polynomial speedup

Some problems show modest quantum advantages.

### No speedup

Most problems. Quantum doesn't speed up everything.

## Hardware

Quantum computers are physical systems with specific implementations:

### Superconducting qubits

IBM, Google, Rigetti. Cooled to millikelvin.

Currently the leading approach.

### Trapped ions

Quantinuum, IonQ. High-fidelity gates; slower.

### Photonic

PsiQuantum, Xanadu. Room temperature; harder to entangle.

### Neutral atoms

QuEra, Atom Computing. Promising; growing.

### Topological

Microsoft. Theoretically very stable; not yet demonstrated.

## State of hardware (early 2026)

- IBM: 1000+ qubits demonstrated
- Google, IBM: hundreds-of-qubits processors
- High error rates limit practical computation
- Coherence times measured in microseconds
- Classical-quantum hybrid common

We are not at fault-tolerant quantum computing yet. We are at "noisy intermediate-scale quantum" (NISQ).

## Quantum supremacy

Demonstrating a task quantum computer does that classical can't simulate efficiently.

- Google (2019): 53 qubits, 200 seconds vs 10K years classical (estimate disputed)
- Various since with refined claims

These tasks are usually not useful — chosen specifically to be classically hard.

The bar moves: classical algorithms improved (refuting some claims). Useful supremacy requires both quantum advantage AND useful work.

## Quantum error correction

Qubits are noisy. To do reliable computation: error correction.

Logical qubit ≈ many physical qubits (typically 100-1000).

Required for fault-tolerant computation.

Currently demonstrated in research; not yet in operating systems.

## Cryptography implications

Shor's algorithm breaks RSA and elliptic curve cryptography.

Symmetric cryptography (AES) is "only" weakened by Grover's. AES-256 → effectively AES-128 (still secure).

### Post-quantum cryptography

Cryptography secure against quantum computers. NIST standardizing:
- Kyber (key encapsulation)
- Dilithium (signatures)
- Falcon, SPHINCS+

Migration is happening; will take years.

### Threat timeline

Most experts: 10-30 years before cryptographically-relevant quantum computers.

But: "harvest now, decrypt later" is real concern. Adversaries collect encrypted data; decrypt when quantum computers exist.

## What software engineers should know

### Will quantum computers break my application?

Probably not for years. But long-lived secrets should consider post-quantum migration.

### Should I learn quantum programming?

Optional. Tools exist (Qiskit, Cirq, Q#). Useful for specific roles (cryptography, quantum chemistry, research).

### Will quantum replace classical?

No. Quantum is for specific problem types. Classical computing remains primary.

### Can I run quantum code?

Yes, on cloud quantum platforms (IBM, Amazon Braket, Azure Quantum). Limited but accessible.

## Common misconceptions

### "Quantum tries all answers in parallel"

Misleading. Quantum manipulates amplitudes; only specific algorithms produce useful results.

You can't just "search all solutions in parallel" generally.

### "Quantum computers are exponentially faster than classical"

Only for specific problems. Most computations don't benefit.

### "Quantum will revolutionize everything soon"

Not soon. Likely never for many applications.

### "Quantum computing requires understanding quantum mechanics deeply"

Some understanding helps. Practical tools (Qiskit, Cirq) abstract much of it.

## Quantum-classical hybrid

Most realistic near-term:
- Classical computers do most work
- Quantum subroutines for specific tasks
- Iterate between

VQE and QAOA work this way.

## What might actually be useful

In near term:
- Quantum chemistry (drug discovery, materials)
- Optimization (mixed evidence)
- Scientific simulation
- Cryptanalysis (eventually)

What probably won't be useful:
- General-purpose computing
- Most ML
- Most database queries
- Web servers

## Practical advice

For most software engineers:
1. Keep aware of post-quantum cryptography (long-term concern)
2. Don't pivot career to quantum unless specifically interested
3. Don't believe vendor hype
4. Watch for fault-tolerant quantum computing milestones

For interested engineers:
1. Learn quantum basics (Nielsen-Chuang textbook)
2. Try Qiskit, run on real hardware
3. Pick a domain (cryptography, chemistry, optimization)

## Where this is going

- Hardware will keep improving
- Error correction will eventually work
- Useful applications will emerge slowly
- Hype cycles will continue

Quantum computing is real but not magic. Understanding what it can and can't do is more useful than overestimating either.

## Further Reading

- [MemoryArchitectures](MemoryArchitectures) — Classical computing
- [Computer Science Foundations Hub](Computer+Science+Foundations+Hub) — Cluster index
