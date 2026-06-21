---
type: article
status: active
date: '2026-05-15T00:00:00Z'
cluster: software-architecture
title: Architecture Critique and Design Patterns
hubs:
- ArchitectureHub
tags:
- architecture
- design-patterns
- refactoring
- systems
summary: Architecture evaluation frameworks (ATAM, CBAM, SAAM) and day-to-day critique
  techniques applied to Wikantik's hybrid DI bridge and system decoupling.
related:
- WikantikPlatformHub
- WikantikArchitecture
canonical_id: 01J7KQTCCQ3H9K0M9E95ZCK3KN
---
# Architecture Critique and Design Patterns

This article provides a critical analysis of architecture critique methodologies, and how they apply to modern platform architectures (such as the Wikantik platform).

## 1. Core Evaluation Frameworks

### ATAM (Architecture Trade-off Analysis Method)
Developed by the SEI at Carnegie Mellon University, ATAM is the industry-standard, scenario-based evaluation technique.
*   **Focus:** Discovering risks, sensitivity points, and trade-offs.
*   **Best For:** Complex, high-risk systems where early mistake detection saves exponential costs.

### CBAM (Cost-Benefit Analysis Method)
An economic extension of ATAM that quantifies trade-offs.
*   **Focus:** Return on Investment (ROI) and economic justification.
*   **Best For:** Business stakeholders needing quantitative metrics to prioritize architectural improvements.

### SAAM (Software Architecture Analysis Method)
The predecessor to ATAM and the first formalized scenario-based method.
*   **Focus:** Primarily modifiability and comparing competing architectures.

## 2. Targeted & Agile Frameworks

*   **ARID (Active Reviews for Intermediate Designs):** Focuses on evaluating intermediate, incomplete, or partial designs through active peer reviews.
*   **QAW (Quality Attribute Workshops):** A facilitation method used *before* the architecture is created to ensure alignment between business and technical stakeholders.
*   **aim42 (Architecture Improvement Method):** A pragmatic, iterative approach aimed at continuous improvement and technical debt management in existing systems.

## 3. Practical Day-to-Day Techniques

While formal methodologies are powerful, day-to-day practices are essential for continuous critique:
*   **Architecture Decision Records (ADRs):** Documenting the *why* behind architectural choices.
*   **Peer Reviews & Scenario-Based Evaluation:** Regular architectural code reviews against project goals.
*   **Static Code Analysis & Metrics:** Automating the tracking of cyclomatic complexity, coupling, and cohesion to detect architectural drift.

For the full platform overview, see the [Wikantik Platform Hub](WikantikPlatformHub).
