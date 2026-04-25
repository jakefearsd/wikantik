---
canonical_id: 01KQ4VGC5PKAFHNV8CBTXRW0HF
title: Building and Deploying Locally
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: One-screen canonical procedure for building the WAR and redeploying to the local Tomcat — the routine cycle, not the first-time setup. Covers shutdown, build, redeploy, restart, and the readiness check.
tags:
  - build
  - deploy
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You have local code changes and want to see them running
    - You are smoke-testing a new feature against the live wiki
    - You need to force a structural-index rebuild against the current page corpus
  inputs:
    - None — assumes the first-time setup in CLAUDE.md has been completed
  steps:
    - Stop Tomcat — `tomcat/tomcat-11/bin/shutdown.sh` (idempotent; ignore "no Tomcat running" output)
    - Build the WAR — `mvn clean install -T 1C -DskipITs` (parallel build, unit tests, no integration tests)
    - Wipe the exploded ROOT — `rm -rf tomcat/tomcat-11/webapps/ROOT` (Tomcat repopulates on next startup)
    - Copy the fresh WAR — `cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war`
    - Start Tomcat — `tomcat/tomcat-11/bin/startup.sh`
    - Wait for ready — `until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health/structural-index | grep -q '^200$'; do sleep 2; done`
  pitfalls:
    - Skipping the shutdown — Tomcat will detect a stale ROOT but the new servlet may not pick up wiring changes from a hot reload
    - Forgetting to wipe the exploded ROOT before copying the WAR — Tomcat un-explodes from the old directory before it sees the new WAR
    - Running with -T 1C and -Pintegration-tests in the same command — IT modules collide on fixed ports, see DebuggingFailingIntegrationTests
    - Trusting the "Tomcat started" log line as readiness — the structural index can take several seconds to rebuild after startup
    - Editing wikantik-custom.properties in the gitignored tomcat dir and forgetting to update the template under wikantik-war/src/main/config/tomcat/ — the next deploy-local.sh run reverts your edit
  related_tools:
    - /api/health/structural-index
  references:
    - DebuggingFailingIntegrationTests
    - PlanningAMigrationChange
---

# Building and Deploying Locally

The canonical redeploy cycle. CLAUDE.md has the longer first-time setup
flow; this page is the routine version that lives in muscle memory.

## When to use this runbook

Every time you want to see your local code change running. Every time.

## Context

Tomcat's webapps directory is at `tomcat/tomcat-11/webapps/`. Tomcat
auto-deploys WARs dropped into that directory, but it doesn't always
pick up wiring changes from a hot reload (filter chains, listener
order). The shutdown-wipe-copy-startup cycle is the safe baseline.

`bin/deploy-local.sh` is the heavier alternative — it also re-applies
config templates and runs DB migrations. Use it after pulling a branch
that changed config templates or schema; otherwise the routine cycle
above is faster.

## Walkthrough

The frontmatter `steps` are the canonical sequence. The readiness
check at the end (step 6) is the difference between "Tomcat is up"
and "the structural index has finished bootstrap" — both matter for
agent-grade endpoints.

## Pitfalls

The frontmatter `pitfalls` capture the recurring traps. The
"editing wikantik-custom.properties in the gitignored tomcat dir"
mistake is especially expensive because the loss is silent — the next
deploy reverts the change without warning.
