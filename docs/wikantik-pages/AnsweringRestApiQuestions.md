---
canonical_id: 01KQ463XA2E15AP4DERN23Q3F8
title: Answering REST API Questions
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: How to find which `/api/*` endpoint answers a given user question — by URL pattern, HTTP method, and permission model — without guessing or scanning every Resource class.
tags:
  - rest
  - api
  - runbook
  - agent-context
runbook:
  when_to_use:
    - A user asks "is there an API to do X" and you don't already know the answer
    - You need to confirm an endpoint's HTTP method or permission model before recommending it
    - You suspect an endpoint exists but can't find it via search alone
  inputs:
    - The intent in plain English ("delete a page", "list backlinks", "verify a user")
    - Whether you need a write or read endpoint
  steps:
    - Grep `wikantik-war/src/main/webapp/WEB-INF/web.xml` for the URL pattern — the servlet-mapping block names every Resource and its prefix
    - Open the matching `*Resource.java` in `wikantik-rest/src/main/java/com/wikantik/rest/`
    - The `doGet`/`doPost`/`doPut`/`doDelete` overrides reveal the method shape and the permission check (look for `checkPagePermission` or `hasPagePermission`)
    - Cross-reference with /admin/* if you couldn't find it under /api/* — admin endpoints exist for AllPermission-only operations
    - Confirm the response shape by reading the doGet body — every response goes through GSON.toJson(envelope)
  pitfalls:
    - Don't guess the endpoint by analogy ("I've used /api/pages so /api/users must exist") — confirm via web.xml
    - Permission checks are per-action — `view`, `edit`, `delete` are different; an endpoint that requires `edit` will 403 a `view`-only session
    - /admin/* endpoints require AllPermission (admin role) and use AdminAuthFilter — distinct from ACL-aware /api/* endpoints
    - The React SPA hits some endpoints under `/api/*` that don't exist on the bare REST surface — those are server-rendered fragments, not part of the API contract
  related_tools:
    - /knowledge-mcp/search_knowledge
    - /api/search
  references:
    - StructuralSpineDesign
    - FindingTheRightMcpTool
---

# Answering REST API Questions

The `/api/*` surface is wide and grows organically. Most agents over-rely
on search to find endpoints, when the truth is two greps away in
`web.xml`.

## When to use this runbook

When a user asks something that should be one curl call, but you can't
name the endpoint in advance.

## Context

`wikantik-war/src/main/webapp/WEB-INF/web.xml` is the contract: every
servlet (every `*Resource.java`) has both a `<servlet>` declaration and
a `<servlet-mapping>`. The mapping shows the URL pattern; the
declaration shows the implementation class. Together they answer
"is there an endpoint for X" with full certainty.

The Resource classes themselves (`wikantik-rest/.../*Resource.java`)
are the source-of-truth for HTTP method shape and permission model.
`RestServletBase` is the parent class — its helpers (`checkPagePermission`,
`requirePathParam`, `parseJsonBody`) are used by every Resource.

## Walkthrough

The frontmatter `steps` are the canonical procedure. The order matters:
web.xml first (it tells you whether the endpoint exists at all), then
the Resource (it tells you the method and permission), then the body
(it tells you the response shape).

## Pitfalls

The frontmatter `pitfalls` cover the recurring traps. The
permission-by-analogy mistake is the most common — agents see
`view` work and assume `delete` will too on the same path.
