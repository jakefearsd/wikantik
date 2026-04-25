---
canonical_id: 01KQ485C06BMPZ28QRZK8NM8K1
title: Debugging Failing Integration Tests
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: First-aid for a red `mvn -Pintegration-tests` run — port conflicts (8080/8205), Cargo failures, pgvector container leaks, the no-parallelism rule, and where to look for actual application errors.
tags:
  - testing
  - integration
  - runbook
  - agent-context
runbook:
  when_to_use:
    - mvn -Pintegration-tests fails and you can't immediately tell why
    - The same test passes locally in isolation but fails in the full IT run
    - You see "Port number 8205 is in use" or "address already in use"
  inputs:
    - The failing IT submodule (one of the wikantik-it-test-* directories)
    - The error message from the surefire report
  steps:
    - Confirm you ran without -T — `mvn clean install -Pintegration-tests -fae` (no `-T 1C`); IT modules share fixed ports and parallel runs collide
    - Stop any running Tomcat on 8080 — `tomcat/tomcat-11/bin/shutdown.sh` (or kill the PID); Cargo can't start over a live instance
    - Clear stale pgvector containers from prior runs — see `reference_docker_cleanup` for the canonical commands; port 55432 is the IT pgvector port
    - Re-run only the failing IT module — `mvn install -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -am`
    - If still red, check `target/surefire-reports/*.txt` and `target/cargo/configurations/tomcat*/logs/catalina*.log` for the actual error
    - When suspect is the wikantik-main parallel-flake list, re-run that test class in isolation — it will pass
  pitfalls:
    - Running mvn -T 1C with -Pintegration-tests — guaranteed port-conflict failures, every time
    - Ignoring stale Docker containers — they hold the pgvector port and silently fail every IT run after the first
    - Reading only the maven output — the actual exception is usually in the surefire txt or the Cargo catalina log
    - Re-running the full IT suite when only one module is failing — slow and obscures the signal
  related_tools:
    - /admin/structural-conflicts
  references:
    - BuildingAndDeployingLocally
---

# Debugging Failing Integration Tests

The IT suite uses Cargo to start embedded Tomcat instances against a
PostgreSQL + pgvector container. Most IT failures are environmental, not
code defects — port conflicts, container leaks, or running with the
wrong Maven flags.

## When to use this runbook

When you see red and don't yet know whether it's your code or the test
harness.

## Context

The IT modules under `wikantik-it-tests/` each run their own Tomcat via
Cargo. They use fixed ports (8080 for HTTP, 8205 for RMI) so parallel
execution always conflicts. The pgvector container also uses a fixed
port (55432), and Docker doesn't always release it cleanly between
runs.

The wikantik-main module has a known set of parallel-flake unit tests
(per `MEMORY.md`) — they pass in isolation. They show up in unit-test
runs, not IT runs, but agents sometimes confuse the two.

## Walkthrough

The frontmatter `steps` are the canonical first-aid sequence. Steps
1–3 catch the environmental issues; step 4 narrows scope; steps 5–6
locate the actual error.

## Pitfalls

The frontmatter `pitfalls` are the recurring traps. The `-T 1C` flag
is by far the most common cause — agents copy unit-test commands into
IT contexts without editing.
