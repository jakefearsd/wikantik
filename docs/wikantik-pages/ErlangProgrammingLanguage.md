---
title: Erlang Programming Language
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A functional, concurrent programming language designed by Ericsson for building massively scalable, fault-tolerant, soft real-time systems.
tags:
- erlang
- actor-model
- fault-tolerance
- beam-vm
- distributed-systems
- functional-programming
---

# Erlang Programming Language

Erlang is a general-purpose, concurrent, functional programming language and runtime system. It was designed by Joe Armstrong, Robert Virding, and Mike Williams at Ericsson in the mid-1980s specifically for the demands of large-scale telephony systems.

The core philosophy of Erlang is expressed in the phrase "Let it crash," which emphasizes building systems that can recover gracefully from errors rather than trying to prevent them entirely through complex defensive coding.

## Core Architectural Pillars

### 1. The Actor Model
In Erlang, every unit of computation is a **process**. These are not OS-level processes, but extremely lightweight "green threads" managed by the Erlang VM (BEAM). 
- Processes are completely isolated; they share no memory.
- Communication happens exclusively through asynchronous **message passing**.
- Each process has its own "mailbox" where incoming messages are queued.

### 2. Fault Tolerance and Supervision
Erlang systems are structured as **supervision trees**.
- **Supervisors** are processes whose sole job is to monitor other processes (workers or other supervisors).
- If a worker process fails, the supervisor is notified and can take action, such as restarting the worker in a known good state.
- This creates a self-healing system where localized failures do not cascade into global outages.

### 3. Functional and Immutable
Erlang is a functional language where variables are **immutable**. Once a value is bound to a name, it cannot be changed. This eliminates entire classes of bugs related to shared mutable state and makes reasoning about concurrent code significantly easier.

### 4. The BEAM Virtual Machine
The **BEAM** (Bogdan's Erlang Abstract Machine) is the virtual machine that executes Erlang bytecode. It is optimized for:
- Low-latency context switching between millions of processes.
- Soft real-time performance guarantees.
- **Hot Code Reloading**: The ability to update the code of a running system without stopping it, a critical requirement for high-availability systems.

## Distributed Erlang
Distribution is a first-class citizen in Erlang. Message passing works identically whether the target process is on the local node or a remote node across a network. This makes it straightforward to scale applications horizontally and build distributed databases like **Riak** or messaging brokers like **RabbitMQ**.

## Legacy and Influence
Erlang's success in building reliable systems (such as the WhatsApp backend, which famously handled billions of messages with a small engineering team) has influenced many modern languages and frameworks. 
- **Elixir**: A modern language built on the BEAM that provides a more approachable syntax and advanced tooling while retaining Erlang's power.
- **Akka**: A toolkit for the JVM that brings the actor model to Java and Scala.

## See Also
- [Actor Model Programming](ActorModelProgramming) — The theoretical foundation of Erlang's concurrency.
- [Fault Tolerant Systems](FaultTolerantSystems) — General patterns for reliability.
- [Distributed Systems Hub](DistributedSystemsHub) — Overview of distributed engineering.
