---
summary: Lightweight test stubs replacing TestEngine for decoupled, fast unit tests
tags:
- development
- testing
- stubs
- test-infrastructure
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-23'
related:
- ConstructorInjection
- WikantikDevelopment
---
# Test Stub Conversion

The test stub conversion replaced heavy TestEngine-based unit tests with lightweight stub implementations. Three key stubs were introduced: StubPageManager, StubSystemPageRegistry, and StubReferenceManager.

## Motivation

Many unit tests only needed one or two manager methods but were forced to spin up a full TestEngine with file-based providers, search indexes, and security configuration. This made tests slow and brittle.

## Stubs

- **StubPageManager** — In-memory page storage with put/get semantics
- **StubSystemPageRegistry** — Returns false for all system page checks
- **StubReferenceManager** — No-op reference tracking

## Impact

Test classes using stubs run significantly faster and have no filesystem or configuration dependencies.

[{Relationships}]
