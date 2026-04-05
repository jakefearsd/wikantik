---
summary: Testability refactor from service locator pattern to constructor dependency injection
tags:
- development
- testing
- refactoring
- dependency-injection
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-29'
related:
- TestStubConversion
- WikantikDevelopment
---
# Constructor Injection

The constructor injection initiative refactored high-value classes from the service locator pattern (calling `engine.getManager()` internally) to accepting dependencies via constructor parameters.

## Motivation

The service locator pattern made unit testing difficult — tests had to create a full WikiEngine even when only one manager was needed. Constructor injection allows tests to pass lightweight stubs directly.

## Classes Refactored

Seven high-value classes were converted, including core rendering and reference management components. Each class gained a constructor accepting its dependencies while maintaining backwards compatibility through a default constructor that falls back to service location.

[{Relationships}]
