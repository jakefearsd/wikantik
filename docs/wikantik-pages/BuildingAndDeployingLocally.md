---
date: ''
runbook:
  when_to_use:
  - You have local code changes and want to see them running
  - You are smoke-testing a new feature against the live wiki
  - You need to force a structural-index rebuild against the current page corpus
  inputs:
  - None - assumes the first-time setup (bin/deploy-local.sh) has been completed
  steps:
  - Build the WAR - `mvn clean install -DskipTests -T 1C` (use -DskipTests, never
    -Dmaven.test.skip; run the tests separately with bin/run-tests.sh)
  - Redeploy - `bin/redeploy.sh` (stops Tomcat and waits for port 8080 to release,
    rotates catalina.out, removes the exploded webapps/ROOT, copies wikantik-war/target/Wikantik.war
    to webapps/ROOT.war, applies pending DB migrations, starts Tomcat)
  - Wait for ready - `until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health/structural-index
    | grep -q '^200$'; do sleep 2; done`
  pitfalls:
  - Hand-rolling the shutdown/copy/startup cycle instead of using bin/redeploy.sh
    - it skips the wait for port 8080 to release (a new Tomcat started into a still-bound
    port stays up but cannot serve), the catalina.out rotation and the migration run
  - Building with -Dmaven.test.skip - it skips wikantik-main's test-jar and breaks
    the full-reactor build; use -DskipTests
  - Running with -T 1C and -Pintegration-tests in the same command - IT modules collide
    on fixed ports, see DebuggingFailingIntegrationTests
  - Trusting the 'Tomcat started' log line as readiness - the structural index can
    take several seconds to rebuild after startup
  - Expecting a change to a git-tracked template under wikantik-war/src/main/config/tomcat/
    to reach an existing install - the deployed files in tomcat/tomcat-11 (wikantik-custom.properties,
    ROOT.xml, log4j2.xml, setenv.sh) are rendered once and never overwritten by deploy-local.sh
    or redeploy.sh. Edit the deployed file by hand (your edit survives redeploys),
    or delete it and re-run bin/deploy-local.sh to re-render from the template
  related_tools:
  - /api/health/structural-index
  references:
  - DebuggingFailingIntegrationTests
  - PlanningAMigrationChange
  - PostgreSQLLocalDeployment
title: Building and Deploying Locally
audience:
- agents
- humans
canonical_id: 01KQ4VGC5PKAFHNV8CBTXRW0HF
cluster: agent-cookbook
type: runbook
tags:
- build
- deploy
- runbook
- agent-context
summary: 'Build the WAR and redeploy to the local Tomcat with bin/redeploy.sh: the
  routine cycle (not first-time setup), then wait for the structural index to be ready.'
---

# Building and Deploying Locally

The canonical redeploy cycle. CLAUDE.md has the longer first-time setup
flow (also walked through in [PostgreSQL Local Deployment](PostgreSQLLocalDeployment));
this page is the routine version that lives in muscle memory.

## When to use this runbook

Every time you want to see your local code change running. Every time.

## Context

Tomcat's webapps directory is at `tomcat/tomcat-11/webapps/`. Tomcat
auto-deploys WARs dropped into that directory, but it doesn't always
pick up wiring changes from a hot reload (filter chains, listener
order). A full stop-swap-start cycle is the safe baseline, and
`bin/redeploy.sh` is that cycle in one command: it stops Tomcat, waits
for port 8080 to release, rotates `catalina.out`, replaces the exploded
`webapps/ROOT` with the fresh WAR, applies any pending database
migrations and starts Tomcat. It does not build; run the Maven step first.

`bin/deploy-local.sh` is the heavier alternative — it also validates
secrets, renders any config files that do not exist yet, upgrades the
JDBC driver and seeds the admin account. Use it for first-time setup and
after pulling a branch that changed Tomcat, the JDBC driver version or
the config templates; otherwise the routine cycle above is faster.

## Walkthrough

The frontmatter `steps` are the canonical sequence. The readiness
check at the end (step 3) is the difference between "Tomcat is up"
and "the structural index has finished bootstrap" — both matter for
agent-grade endpoints.

## Pitfalls

The frontmatter `pitfalls` capture the recurring traps. The costly one
is believing a template edit has taken effect: the files rendered into
`tomcat/tomcat-11` (`wikantik-custom.properties`, `ROOT.xml`,
`log4j2.xml`, `setenv.sh`) are written once and never overwritten, so
a change to the template under `wikantik-war/src/main/config/tomcat/`
silently does nothing for an existing install. Edit the deployed file
instead (it survives redeploys), or delete it and re-run
`bin/deploy-local.sh`.
