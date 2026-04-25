---
canonical_id: 01KQ12YDVYMJ6YFQP897KP47CC
title: Microservices Architecture
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- microservices
- architecture
- distributed-systems
- service-boundaries
- monolith
summary: When microservices are the right answer, when they're cargo-culted misery,
  and the practical decisions that decide which way it goes for your team.
related:
- DomainDrivenDesign
- EventDrivenArchitecture
- ContainerOrchestration
- ServiceLevelAgreements
- DistributedTracing
hubs:
- SoftwareArchitecture Hub
---
# Microservices Architecture

Microservices solve a real problem: as a single codebase grows past ~100 engineers, the cost of coordination on shared deployments, shared databases, and shared release cadence becomes the dominant tax on velocity. Splitting the system into independently deployable services lets teams ship without coordinating, at the cost of new operational complexity.

That's the deal. If you take on the cost without earning the benefit (because your team is 8 people, or your services share a database anyway), you've made things worse. The decision matrix below is roughly the threshold for when the trade flips.

## When microservices win

You probably need them when at least three are true:

- **You have multiple teams** (say, 4+ teams, 30+ engineers) hitting merge conflicts and release-coordination overhead in the monolith.
- **Different parts of the system have different scaling characteristics** — one part is CPU-bound, another is memory-bound, a third is I/O-bound. Independent scaling pays for itself.
- **Different parts have different compliance or runtime constraints** — one needs PCI isolation, another needs to run in a customer's VPC.
- **You've already made the monolith modular** (clear domain boundaries, separate databases per module, no shared mutable state) and the only remaining boundary cost is deployment coupling.

If you can answer all of these honestly, microservices probably help.

## When they lose

You almost certainly should not split when:

- **Team size < 20 engineers.** The operational overhead is more than your coordination cost.
- **Services share a database.** That's a distributed monolith, not microservices. You have all the costs and none of the benefits.
- **Your domain isn't yet stable.** Service boundaries are very expensive to refactor. You want the monolith's "rename a function" ergonomics until you know what the boundaries should be.
- **You don't have observability and CI/CD for microservices.** Without traces, structured logs, and per-service deploy pipelines, you'll spend most of your time debugging the network instead of building features.

The "default to microservices" trend cost a lot of teams a lot of years. Default to a modular monolith. Promote modules to services only when the coordination cost has clearly become real.

## Service boundaries: the hard part

Drawing the lines wrong is the failure mode. Two heuristics:

**Boundaries follow business capabilities, not data.** The classic mistake: "we have a User table, so we need a UserService that manages users." This produces a service that everything calls, which is a distributed monolith with a UserService bottleneck. Better: "we have onboarding, billing, and support; each is a service that owns the data it cares about (with controlled duplication)."

**Boundaries follow team ownership.** A service that no team owns will rot. A service that two teams co-own will become a coordination nightmare. One team, one service (or one team owns multiple cohesive services). If the team boundaries are wrong, fix those before fixing service boundaries.

Domain-driven design's *bounded contexts* (see [DomainDrivenDesign]) are the formalisation of "follow business capabilities." Worth reading the Evans book or skimming Vaughn Vernon's distillation.

## The operational cost, concretely

Things that are nearly free in a monolith and not free in microservices:

| Concern | Monolith | Microservices |
|---|---|---|
| **Tracing a request** | Stack trace | Distributed tracing system (OTel + backend) |
| **Atomic writes** | Database transaction | Saga / outbox / 2PC, all complex |
| **Refactoring a method signature** | IDE rename | Versioned API + deprecation cycle |
| **Local dev** | Run the app | Docker Compose with N services + service mesh |
| **Deployments** | One pipeline | N pipelines, deployment ordering, rollback coordination |
| **On-call** | One pager rotation, one runbook | N pager rotations, "which service is degraded?" detective work |

Each row is a real engineering investment. Total cost on a mature microservices org is on the order of 3–8 platform engineers per 100 product engineers. Plan for this; don't pretend it's free.

## The patterns that actually matter

**API gateway.** External traffic enters one place; concerns like auth, rate limiting, request shaping live there. Without it, every service reinvents these and you'll never get them all right.

**Service mesh.** mTLS, retries, circuit breakers, observability — done at the network layer. Istio and Linkerd are the canonical options. Adopt only when you have enough services that doing this in libraries hurts.

**Event-driven communication for cross-service coordination.** RPC for synchronous calls; events for "I changed something other services might care about." Mixing these or only using one creates pain. See [EventDrivenArchitecture].

**Database per service.** Each service owns its data. Cross-service queries go through APIs or replicated read models, never direct DB joins. This is the rule that distinguishes microservices from a distributed monolith.

**Saga or outbox for distributed transactions.** Two-phase commit doesn't work across services in practice. Saga (compensating transactions) or transactional outbox + event log are the workable patterns.

## Failure modes specific to microservices

**Cascading failures.** Service A calls B calls C; C is slow; A's threads pile up; A fails too. Defence: timeouts at every call boundary, circuit breakers, bulkheads. Library: Resilience4j, Polly, or service-mesh equivalent.

**Distributed monolith.** Services that must be deployed together because they share schemas, share state, or have synchronous chains of dependency. The smell: changes always span multiple services. Fix: stricter schema versioning, async event-based communication, more careful boundary placement.

**Hidden coupling via shared libraries.** Every service uses the same internal library, which is now versioned across N services and fans out an upgrade cycle. Defence: keep shared libraries small and stable; prefer code-gen from schemas over inheritance.

**N+1 service calls.** A list view calls service X for each item, multiplying RPCs. Defence: bulk endpoints, GraphQL-style query batching, or denormalised read models.

**Schema drift.** Service A returns `userId`; Service B started returning `user_id` last week. Defence: API contracts in versioned IDL (OpenAPI, gRPC, JSONSchema); contract testing in CI.

## Migration: monolith to microservices

If you've decided to migrate, the strangler-fig pattern is the proven path:

1. Put a routing layer in front of the monolith.
2. Pick one bounded context. Build it as a new service.
3. Route traffic for that context to the new service. The monolith stops handling it.
4. Repeat.

Common mistakes:

- Trying to split everything at once. Pick one boundary; finish it; repeat.
- Splitting before observability is in place. You won't know what broke.
- Leaving the database split for "later." Two services on one DB is a distributed monolith forever.

A 10-engineer team migrating a 5-year monolith should expect 12–24 months for a real, complete migration. Most published "we did it in 6 months" stories are partial migrations.

## What you'd build today, ground up

Most teams in 2026 should:

1. **Start with a modular monolith.** One repo, clear module boundaries, separate database schemas per module, dependency injection so modules don't reach into each other.
2. **Add observability early** — traces, structured logs, RED metrics per endpoint. You'll need this whether you split or not.
3. **Promote a module to a service when, and only when, deployment coupling has become a measurable bottleneck.** Track release frequency per module; promote the slowest.
4. **Treat the platform as a product.** Service template, CI/CD pipeline, observability defaults, security scans — invest in these as soon as you have 3+ services.

This produces fewer services, slower, but each one is justified. It also keeps the option of staying a monolith forever if your domain doesn't require splitting.

## Further reading

- [DomainDrivenDesign] — bounded contexts as the basis for boundaries
- [EventDrivenArchitecture] — async communication patterns
- [ContainerOrchestration] — Kubernetes is usually the runtime
- [ServiceLevelAgreements] — SLO discipline becomes more important with more services
- [DistributedTracing] — observability foundation
