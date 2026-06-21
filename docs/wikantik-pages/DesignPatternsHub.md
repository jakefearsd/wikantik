---
type: hub
status: active
date: '2026-04-26'
cluster: design-patterns
title: Design Patterns
hubs:
- SoftwareEngineeringPracticesHub
- JavaHub
tags:
- design-patterns
- software-design
- hub
- patterns
- architecture
summary: Index of software design patterns — when each applies, shared naming vocabulary,
  and cases where the pattern value is communication rather than novelty.
related:
- SoftwareEngineeringPracticesHub
- JavaHub
canonical_id: 01KZHC6PVW4SBQM9R0F3T7K8Z9
---
# DesignPatterns Hub

The design-patterns vocabulary is a shared language for naming common solutions to common problems. The cluster covers patterns that actually appear in modern codebases — not the full Gang of Four catalog, but the subset that earns its place when the right problem appears.

The honest assessment of design patterns: most of the value is in the *vocabulary* (giving teams a shared name for a structure they would have invented anyway), not in the patterns being inherently novel.

## Creational

- [BuilderPatternAndFluentApis](BuilderPatternAndFluentApis) — Constructor sprawl and the fluent-API solution
- [FactoryPattern](FactoryPattern) — Factories, abstract factories, when each is right
- [Dependency Injection Patterns](DependencyInjectionPatterns) — Decoupling components through inversion of control

## Behavioral

- [StateMachinePattern](StateMachinePattern) — Finite state machines in code, the cases where they help
- [SpecificationPattern](SpecificationPattern) — Composable predicates, query objects

## Structural

- [RepositoryPattern](RepositoryPattern) — Aggregate access, the case for and against repositories

## Adjacent clusters

- [Software Engineering Practices Hub](SoftwareEngineeringPracticesHub) — Practices that complement patterns
- [Java Hub](JavaHub) — Java-specific applications
