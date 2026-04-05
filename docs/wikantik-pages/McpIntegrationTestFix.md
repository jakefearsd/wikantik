---
summary: Fixed WikiEngine lazy initialization and failsafe reporting for MCP integration tests
tags:
- development
- testing
- mcp
- integration-tests
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-12'
related:
- McpIntegration
- WikantikDevelopment
---
# MCP Integration Test Fix

The MCP integration tests were silently failing due to two root causes: WikiEngine lazy initialization during test setup, and Maven Failsafe's `testFailureIgnore` setting suppressing failures.

## Root Causes

1. **Lazy initialization** — The WikiEngine was not fully initialized when MCP tools tried to access managers during integration tests, causing null pointer exceptions
2. **Silent failures** — The `testFailureIgnore` configuration in the Failsafe plugin meant test failures were logged but did not break the build

## Fix

- Ensured WikiEngine is fully initialized before MCP tool registration
- Removed `testFailureIgnore` so integration test failures properly fail the build
- Added explicit failsafe reporting to surface test results

[{Relationships}]
