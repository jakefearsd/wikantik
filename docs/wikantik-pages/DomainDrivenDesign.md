---
canonical_id: 01KQ12YDTQ0NHYCA7TRW1SSM4Z
title: Domain Driven Design
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- ddd
- domain-driven-design
- bounded-context
- ubiquitous-language
- aggregate
summary: DDD's load-bearing ideas — bounded contexts, aggregates, ubiquitous
  language — with the parts that survive in 2026 and the parts that turned
  out to be ceremony.
related:
- MicroservicesArchitecture
- EventDrivenArchitecture
- HexagonalArchitecture
- DesignPatternsOverview
- CqrsPattern
hubs:
- SoftwareArchitecture Hub
---
# Domain-Driven Design

Domain-Driven Design (DDD) is twenty years old. The Evans book (2003) introduced a vocabulary — bounded context, aggregate, ubiquitous language — that became the standard way to talk about modelling complex business software. Some of it aged well. Some of it became ritual.

This page is what's still load-bearing in 2026 and what was always more about consultancy revenue than engineering value.

## What aged well

### Ubiquitous language

Engineers and domain experts use the same words for the same things, and those words appear in the code. The customer-onboarding domain expert says "applicant"; the database has an `applicants` table; the class is `Applicant`; the discussion in standup uses "applicant."

This sounds trivial; it's not. Most teams have a translation layer where the business says "claim" and the engineers track "tickets," and bugs hide in that translation forever. Killing the translation layer is real engineering value that compounds.

How to actually do it:
- Capture the vocabulary in a glossary owned by domain experts plus engineers.
- Code review enforces it. PRs that introduce new entities should match the glossary; if they don't, the glossary needs updating *or* the code is wrong.
- When the business changes a term, the code follows. Use IDE rename, ship the deprecation, move on.

### Bounded contexts

A bounded context is a region of the system where one set of vocabulary applies consistently. Outside the context, the same word might mean something different.

Example: in the **billing** context, an "account" is a payment relationship — credit card, billing address, invoice schedule. In the **identity** context, an "account" is the login credential — username, password, MFA token. Same word, different things, different schemas, different services.

Bounded contexts are the load-bearing idea behind microservice boundaries (see [MicroservicesArchitecture]). Drawing service boundaries along bounded contexts produces services that are internally cohesive and externally decoupled. Drawing them anywhere else produces distributed monoliths.

How to actually do it:
- Map the contexts before deciding service boundaries. Use Event Storming or Context Mapping (see below).
- Each context owns its data. Other contexts integrate via published APIs or events, never direct DB access.
- Translation between contexts is explicit (an "anti-corruption layer") and lives in code, not informal conversation.

### Aggregates and aggregate roots

An aggregate is a cluster of objects with one root that's the only entry point. Invariants on the cluster are enforced by methods on the root. External code interacts only with the root.

Example: an `Order` aggregate root contains `OrderLine` items. Adding an item goes through `order.addLine(...)`, which can enforce "no more than 50 lines" or "total can't exceed credit limit." External code never directly mutates `OrderLine.quantity` because it can't get to the line without going through the order.

This pattern works. Use it when invariants span multiple objects. Don't use it when each object stands alone — wrapping a `User` in a `UserAggregateRoot` for ceremony is exactly the kind of DDD overhead that gives the practice its bad name.

The companion idea — **aggregates are also transaction boundaries** — is the practical bit. One business operation modifies one aggregate. Cross-aggregate consistency is eventual, via events. This is the basis for distributed-transaction-free architectures.

## What was always overdone

### Heavy "tactical patterns" stack

The Evans book lists a stack of patterns: entity, value object, domain service, application service, repository, factory, specification. Implemented religiously, this is a lot of layers around what's often a CRUD application.

The ones that earn their weight:
- **Value objects.** Immutable, equality by value (`Money`, `Address`, `EmailAddress`). Eliminates a category of "did I just mutate the user's address by accident" bugs. Cheap to introduce. Always worth it.
- **Repositories** — when you have a real abstraction over storage. When the repository is a thin wrapper over an ORM call, it's overhead.
- **Domain services** — when an operation legitimately doesn't belong on a single entity. Otherwise, methods on the entity are simpler.

The ones that are usually ceremony:
- **Factories.** Most languages have constructors. Use them.
- **Specifications.** Composable predicates over entities. Useful in narrow cases (complex business rules that change frequently); bureaucratic in most others.
- **Application services.** Often a layer that exists to satisfy "we need an application service layer" rather than to do anything.

Adopt selectively. The full DDD ceremonial stack on a CRUD app is the worst-case adoption pattern.

### Anaemic vs rich domain models

DDD orthodoxy says putting all logic in services with simple data classes ("anaemic model") is wrong, and that logic should live on entities ("rich model").

In practice, **rich models work when the business logic is about the entity itself** (an `Order` knows how to add lines, calculate totals, transition states). They become awkward when the logic spans entities or has complex external dependencies.

The right answer is "logic lives where it makes sense"; orthodoxy here produces 800-line `Order` classes that have absorbed every responsibility tangentially related to orders.

## Modelling techniques worth using

### Event Storming

A workshop format. Domain experts and engineers stick coloured Post-its on a wall: orange for events ("order placed"), blue for commands ("place order"), yellow for actors ("customer"), pink for problems ("two orders submitted, one duplicated").

Why it works: it surfaces hidden complexity — events nobody knew were happening, actors who do things that aren't documented, problems that are common but unsolved. The output is a shared map of how the business actually operates.

Two days of Event Storming early in a project is the highest-ROI DDD practice. Don't skip it.

### Context mapping

Once you have bounded contexts, you map their relationships:

- **Partnership** — two contexts evolve together; teams coordinate.
- **Customer/Supplier** — one context depends on another's output.
- **Conformist** — one context just accepts whatever the other gives.
- **Anti-Corruption Layer** — translation layer between contexts that don't speak each other's vocabulary.
- **Open Host Service / Published Language** — a context exposes a stable, well-documented interface for many consumers.

The names matter less than asking "how do these two contexts interact, and how do we keep them from corrupting each other's models." Putting it on a diagram is enough.

## How DDD interacts with modern architecture

Microservices: bounded contexts → service boundaries. See [MicroservicesArchitecture].

Event-driven systems: cross-aggregate consistency via events. See [EventDrivenArchitecture].

CQRS: separating writes (command-side, aggregate-enforced invariants) from reads (query-side, denormalised). See [CqrsPattern].

Hexagonal architecture: keeping the domain model independent of frameworks and infrastructure. See [HexagonalArchitecture].

DDD doesn't require any of these, but the latter three are most clearly motivated when you've adopted DDD. If you're not modelling a complex domain, you probably don't need them either.

## When DDD is the wrong fit

- **Simple CRUD.** A user-management page doesn't need bounded contexts and aggregates. Use a framework's scaffolding.
- **Data-heavy / analytics.** DDD models behaviour; analytics is mostly aggregations over data. The data warehouse pattern (dimensional modelling) fits better.
- **Small teams (< 10 engineers).** The full ceremony costs more than it saves. Use the loosely DDD-influenced ideas (ubiquitous language, value objects, aggregate roots where they make sense) without the heavy stack.

## A pragmatic minimum

For a team adopting DDD without going overboard:

1. **Run Event Storming** to map contexts before designing.
2. **Adopt ubiquitous language** in code, glossary, conversation.
3. **Identify aggregates and treat them as transaction boundaries.**
4. **Use value objects** for primitives that have invariants (`Money`, `EmailAddress`).
5. **Keep domain logic on entities** when natural, in services when not.

Skip everything else until a specific situation justifies it. This is roughly the modern consensus on DDD: the strategic ideas (bounded context, ubiquitous language) are nearly universally useful; the tactical pattern stack is selectively useful; a lot of the literature is over-prescribed.

## Further reading

- [MicroservicesArchitecture] — bounded contexts as service boundaries
- [EventDrivenArchitecture] — events between aggregates
- [HexagonalArchitecture] — keeping the domain decoupled from infrastructure
- [CqrsPattern] — read/write separation
- [DesignPatternsOverview] — the broader pattern landscape DDD lives in
