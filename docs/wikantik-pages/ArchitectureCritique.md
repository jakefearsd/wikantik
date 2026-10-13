---
status: active
date: '2026-05-15'
title: Architecture Critique and Design Patterns
related:
- WikantikPlatformHub
- WikantikArchitecture
canonical_id: 01J7KQTCCQ3H9K0M9E95ZCK3KN
cluster: software-architecture
type: article
tags:
- architecture
- design-patterns
- refactoring
- systems
summary: Architecture evaluation frameworks (ATAM, CBAM, SAAM) and day-to-day critique
  techniques applied to Wikantik's hybrid DI bridge and system decoupling.
---
# Architecture Critique and Design Patterns

**Atomic Answer:** Architecture critique methodologies are structured frameworks used by software engineers to systematically evaluate, analyze, and optimize system design. By assessing trade-offs, uncovering structural risks, and managing technical debt early in the development lifecycle, these methodologies ensure software systems remain scalable, resilient, and aligned with long-term business objectives.

Architecture critique methodologies provide structured frameworks for evaluating, analyzing, and improving software architectures. These methodologies help technical leaders and stakeholders uncover risks, evaluate trade-offs, and manage technical debt in complex platforms like Wikantik.

## 1. Core Evaluation Frameworks

**Atomic Answer:** Core architecture evaluation frameworks provide formalized, scenario-based methods for assessing software design quality. Methodologies like SAAM, ATAM, and CBAM enable teams to systematically analyze modifiability, balance competing quality attributes, and quantify the economic return on investment of architectural decisions, ultimately preventing costly late-stage structural failures.

Several formal methodologies have been developed over the years, primarily by organizations like the Software Engineering Institute (SEI).

### SAAM (Software Architecture Analysis Method)

Developed in the mid-1990s, SAAM was the first widely recognized architecture evaluation method.
*   **Focus:** Originally designed to assess the **modifiability** of a software architecture.
*   **Approach:** It uses a scenario-based approach where stakeholders define both "direct" (currently supported) and "indirect" (future modifications) scenarios.
*   **Outcome:** SAAM provides early insight into system capabilities and acts as a communication vehicle among stakeholders to ensure the architecture handles anticipated change.

### ATAM (Architecture Trade-off Analysis Method)

An evolution of SAAM, ATAM is the industry standard for comprehensive architecture evaluation.
*   **Focus:** Evaluating fitness regarding **multiple competing quality attributes** (e.g., performance vs. security, availability vs. modifiability).
*   **Approach:** It uses a "utility tree" to prioritize quality attributes and scenarios. Evaluators seek to identify "sensitivity points" and "trade-off points."
*   **Best For:** Complex, high-risk systems where early mistake detection saves exponential costs.

### CBAM (Cost-Benefit Analysis Method)

CBAM builds directly upon ATAM by introducing economic factors.
*   **Focus:** Introducing **economic and quantitative rigor** (ROI) into architectural decision-making.
*   **Approach:** It models the costs, benefits, and schedule implications of various design alternatives.
*   **Best For:** Business stakeholders needing quantitative metrics to prioritize which architectural improvements to fund based on constrained budgets.

## 2. Modern and Iterative Frameworks

**Atomic Answer:** Modern iterative architecture frameworks offer pragmatic, agile-friendly approaches to continuous system evaluation. Techniques like aim42 focus on existing legacy applications by iteratively analyzing technical debt, evaluating root causes, and applying tactical improvements, allowing organizations to progressively modernize their software architecture without halting regular business feature delivery.

While the SEI methods are highly formalized, modern agile environments often require more iterative approaches.

### aim42 (Architecture Improvement Method)

Unlike ATAM, which is often used during the design phase, aim42 is focused on **existing systems**.
*   **Focus:** A pragmatic, open-source approach for improving legacy systems and managing technical debt.
*   **Approach:** Operates in three iterative phases: **Analyze** (collecting issues), **Evaluate** (prioritizing root causes), and **Improve** (applying patterns to fix problems).
*   **Outcome:** A systematic roadmap for software evolution that balances short-term business delivery with long-term internal quality.

## 3. Practical Day-to-Day Techniques

**Atomic Answer:** Practical day-to-day architecture evaluation techniques integrate structural critique directly into routine engineering workflows. By leveraging Architecture Decision Records (ADRs) to document intent, conducting scenario-based peer code reviews, and utilizing automated static analysis metrics, development teams can continuously monitor code health and actively prevent unintended architectural drift over time.

While formal methodologies are powerful, day-to-day practices are essential for continuous critique:
*   **Architecture Decision Records (ADRs):** Documenting the *why* behind architectural choices.
*   **Peer Reviews & Scenario-Based Evaluation:** Regular architectural code reviews against project goals.
*   **Static Code Analysis & Metrics:** Automating the tracking of cyclomatic complexity, coupling, and cohesion to detect architectural drift.

## 4. How Wikantik Applies These Techniques

*Status (2026-10-13):* the sections above are a general survey. These are the checks the Wikantik repository actually enforces today.

*   **ADRs:** decision records live in `docs/adr/` (ADR-0000 to ADR-0010), for example ADR-0008 on late-bound service registration and ADR-0010 on the single data-access primitive.
*   **Architecture tests:** ArchUnit tests fail the build on structural drift. `DecompositionArchTest` (`wikantik-main`) forbids new `WikiEngine#getManager` callers and new late-bound `mgr_*` fields on `WikiEngine`, and `JdbcAccessArchTest` (`wikantik-war`) forbids JDBC connection and transaction calls outside `com.wikantik.jdbc..`.
*   **Metric ratchets:** `mvn pmd:check -Pcomplexity-gate` fails on any new PMD complexity violation that is not already in `build-support/pmd-complexity-baseline.properties` (entries only ever come out), and per-module JaCoCo line-coverage floors under `-Pcoverage` only ever rise.
*   **The hybrid DI bridge:** Wikantik is moving from the `WikiEngine#getManager` service locator toward typed per-subsystem `Services` records, without a DI framework (ADR-0008 declines Guice). `getManager` and `setManager` stay on `WikiEngine` as thin delegators, and `CoreSubsystemBridge.fromLegacyEngine` synthesises a `Services` record from `getManager` lookups for test harnesses that did not run a full engine start-up.

[RefactorToPatterns](RefactorToPatterns) records which Gang-of-Four refactorings have been carried out.

For the full platform overview, see the [Wikantik Platform Hub](WikantikPlatformHub).
