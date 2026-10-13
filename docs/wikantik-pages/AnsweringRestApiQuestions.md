---
type: runbook
cluster: agent-cookbook
tags:
- rest
- api
- runbook
- agent-context
title: Answering REST API Questions
canonical_id: 01KQ463XA2E15AP4DERN23Q3F8
summary: How to find the /api/* or /admin/* endpoint for a task by URL pattern, HTTP
  method and permission model, without guessing or scanning every Resource class.
audience:
- agents
- humans
runbook:
  when_to_use:
  - A user asks "is there an API to do X" and you don't already know the answer
  - You need to confirm an endpoint's HTTP method or permission model before recommending
    it
  - You suspect an endpoint exists but can't find it via search alone
  inputs:
  - The intent in plain English ("delete a page", "list backlinks", "verify a user")
  - Whether you need a write or read endpoint
  steps:
  - Grep `wikantik-war/src/main/webapp/WEB-INF/web.xml` for the URL pattern — the
    servlet-mapping blocks name every Resource and its prefix (`/api/*` and `/admin/*`;
    the ontology, feed, SCIM and tool endpoints are mapped elsewhere)
  - Open the matching `*Resource.java` in `wikantik-rest/src/main/java/com/wikantik/rest/`
  - The `doGet`/`doPost`/`doPut`/`doPatch`/`doDelete` overrides reveal the method
    shape and the permission check (look for `checkPagePermission`, the enforcing
    call, `hasPagePermission`, a silent capability query, and `filterViewable`, which
    silently drops pages the caller cannot view)
  - Cross-reference with /admin/* if you couldn't find it under /api/* — admin endpoints
    exist for operations that need AllPermission or a scoped admin-area grant
  - 'Confirm the response shape by reading the doGet body — results are serialized
    with `sendJson` (Gson) and errors with `sendError`, which writes {error: true,
    status, message}'
  pitfalls:
  - Don't guess the endpoint by analogy ("I've used /api/pages so /api/users must
    exist") — confirm via web.xml
  - Permission checks are per-action — `view`, `edit`, `delete`, `rename` are different;
    an endpoint that requires `edit` will 403 a `view`-only session
  - /admin/* endpoints are guarded by AdminAuthFilter, which wants AllPermission (the
    Admin role) or a scoped grant for that admin area — distinct from the ACL-aware
    /api/* endpoints
  - Bearer API keys (the /admin/apikeys kind) do not authenticate /api/* or /admin/*
    — those take a login session or HTTP Basic credentials; keys are for the MCP endpoints
    and /tools/*
  - A browser-style request with an Origin header must be same-origin or listed in
    wikantik.cors.allowedOrigins to POST, PUT, PATCH or DELETE under /api/* or /admin/*
    — curl-style clients that send no Origin are not affected
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

When a user asks "is there an API to do X" and you cannot name the
endpoint, or when you need an endpoint's HTTP method or permission model
before recommending it.

## Context

`wikantik-war/src/main/webapp/WEB-INF/web.xml` is the contract: every
servlet (every `*Resource.java`) has both a `<servlet>` declaration and
a `<servlet-mapping>`. The mapping shows the URL pattern; the
declaration shows the implementation class. Together they answer
"is there an endpoint for X" with full certainty. As of 2.4.56 the file maps:

- **`/api/*`** — 36 servlets (38 URL patterns; `SelfApiKeysResource` and `ExportResource` carry two each). Reader and editor operations such as `/api/pages/*`, `/api/search`, `/api/backlinks/*` and `/api/changes`, plus the agent-facing `/api/bundle`, `/api/briefing` and `/api/pages/for-agent/*`.
- **`/admin/*`** — 29 servlets (37 URL patterns) for the operator surfaces; `docs/admin/AdminPanel.md` in the repository maps each admin screen to its route.
- **Mapped elsewhere:** the public ontology endpoints (`/sparql`, `/id/*`, `/export/*`), `/feed.xml`, `/sitemap.xml`, `/attach/*`, `/metrics`, `/scim/v2/*`, the OpenAPI tool server at `/tools/*` and the two MCP endpoints.

Sub-paths are handled inside a single servlet, because a servlet cannot match a variable in the middle of a path. For example `POST /api/pages/{name}/rename` is dispatched by `PageResource.doPost`, and the page-scoped Knowledge Graph curation lives under `/api/page-knowledge/*`, not under `/api/pages/{name}/...`.

The Resource classes themselves (`wikantik-rest/src/main/java/com/wikantik/rest/*Resource.java`)
are the source of truth for HTTP method shape and permission model. They are
plain servlets that extend `RestServletBase`, so there are no JAX-RS style
`@GET` or `@QueryParam` annotations to look for:

- **HTTP methods:** which of `doGet`, `doPost`, `doPut`, `doPatch` and `doDelete` the class overrides (`PageResource` implements all five).
- **Permission model:** `checkPagePermission( request, response, pageName, action )` enforces a page permission (`view`, `edit`, `delete`, `rename`, ...), sends a 403 and writes an audit row on denial. `hasPagePermission` is a silent yes/no used to build UI affordances. `filterViewable` silently drops pages the caller cannot view from list and search results.
- **Payloads:** query parameters are read with `request.getParameter`; JSON bodies with `parseJsonBody`, which answers a malformed body with a 400.
- **Responses:** `sendJson` serializes with Gson; failures go through `sendError`, which writes `{"error": true, "status": <code>, "message": "..."}`. Resources use `RestServletBase.sendError` rather than `response.sendError`, or the container's raw HTML error page leaks through.

Several layers sit in front of the servlet:

- **Who the caller is:** the wiki session (login cookie) or HTTP Basic credentials, which `BasicAuthFilter` turns into a real login on `/api/*` and `/admin/*`. With neither, the caller is anonymous and ACLs apply as for any anonymous reader. The bearer API keys minted at `/admin/apikeys` do not apply here; they belong to the MCP endpoints and `/tools/*`.
- **`/admin/*`:** `AdminAuthFilter` requires `AllPermission` (the Admin role) or a scoped grant for that admin area, and answers a denial with 403 and `{"error":true,"status":403,"message":"Forbidden"}`. A GET whose `Accept` header includes `text/html` is treated as a browser navigation and served the SPA shell, so send `Accept: application/json` when scripting the admin API.
- **Writes from a browser:** `CsrfProtectionFilter` checks the `Origin` header on POST, PUT, PATCH and DELETE under `/api/*` and `/admin/*` (same-origin, or listed in `wikantik.cors.allowedOrigins`); it does not use a token for these JSON endpoints, and requests with no `Origin` header pass.
- **Rate limits:** `/api/*` is limited per client IP (default 25 requests per second); `/api/bundle`, `/api/search` and `/sparql` sit in a stricter tier (3 per second per client, 10 per second overall). Loopback and `/api/health` are exempt.

## Walkthrough

The frontmatter `steps` are the canonical procedure. The order matters:
web.xml first (it tells you whether the endpoint exists at all), then
the Resource (it tells you the method and permission), then the body
(it tells you the response shape). Only then write the `curl` or client
call, and test it as the weakest principal that should succeed and as an
anonymous caller that should be refused.

## Pitfalls

The frontmatter `pitfalls` cover the recurring traps. The
permission-by-analogy mistake is the most common — agents see
`view` work and assume `delete` will too on the same path. Related traps:
renaming is a `POST` to `/api/pages/{name}/rename` with a `newName` body, not
a `PUT` of a new name, and operator functions (users, groups, policy grants,
connectors, drift, audit) live under `/admin/*`, not `/api/*`.
