---
audience:
- humans
summary: Fixed WikiEngine lazy initialization and failsafe reporting for MCP integration
  tests
type: article
kg_include: true
related:
- McpIntegration
- WikantikDevelopment
title: MCP Integration Test Fix
cluster: wikantik-development
tags:
- development
- testing
- mcp
- integration-tests
date: '2026-03-12'
canonical_id: 01KQ0P44SBC89GJTS2N47SMG8N
status: active
---
# MCP Integration Test Fix

> **Status (2026-10-13):** Both fixes are still in place. `McpServerInitializer` still creates the WikiEngine eagerly before it registers any tool, `testFailureIgnore` appears in no `pom.xml`, and `wikantik-it-tests/pom.xml` binds the Failsafe `integration-test` and `verify` goals, so an integration-test failure fails the build. The MCP integration tests now live in `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/` (`McpProtocolIT`, `McpInstructionsDriftIT`, `McpWritePageCycleIT` and others, built on `WithMcpTestSetup`). That module's `wikantik-mcp.properties` sets `mcp.access.allowUnrestricted = true` and both `mcp.ratelimit.*` limits to `0`, so those tests exercise the tools but not the access filter or the rate limiter. See [MCP Audit Tools](McpAuditTools) for everything that checks the MCP surface.

The [MCP integration](McpIntegration) tests were silently failing due to two root causes: WikiEngine lazy initialization during test setup, and Maven Failsafe's `testFailureIgnore` setting suppressing failures.

## Root Causes

1. **Lazy initialization** — The WikiEngine was not fully initialized when MCP tools tried to access managers during integration tests, causing null pointer exceptions
2. **Silent failures** — The `testFailureIgnore` configuration in the Failsafe plugin meant test failures were logged but did not break the build

## Fix

- Ensured WikiEngine is fully initialized before MCP tool registration
- Removed `testFailureIgnore` so integration test failures properly fail the build
- Added explicit failsafe reporting to surface test results

[{Relationships}]
