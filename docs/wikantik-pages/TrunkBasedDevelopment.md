---
canonical_id: 01KQ0P44Y2T10DNP9M0W1SFFQA
title: Trunk Based Development
type: article
tags:
- branch
- featur
- must
summary: We've seen the grand, complex, and ultimately brittle structures of Git Flow,
  the bureaucratic overhead of release branches, and the sheer nightmare of long-lived
  feature isolation.
auto-generated: true
---
# The Architecture of Velocity

For those of us who have spent enough time wrestling with version control systems to understand the sheer entropy of poorly managed codebases, the concept of "branching strategy" often feels less like an architectural choice and more like a philosophical battleground. We've seen the grand, complex, and ultimately brittle structures of Git Flow, the bureaucratic overhead of release branches, and the sheer nightmare of long-lived feature isolation.

This tutorial is not for the novice seeking a simple "how-to." It is intended for the seasoned practitioner, the architect, and the researcher who understands that the true bottleneck in modern software delivery is rarely the compute power, but the *integration friction* itself. We are dissecting **Trunk-Based Development (TBD)**, specifically when paired with the discipline of **short-lived feature branches**, not as a mere workflow recommendation, but as a fundamental paradigm shift in how development teams manage technical debt and cognitive load.

If you are researching new techniques, you must understand that TBD is not just a branching model; it is a *commitment to continuous integration* that forces architectural discipline.

***

## I. Theoretical Underpinnings: Why TBD Over the Alternatives?

To appreciate the elegance of TBD, one must first understand the systemic failures of its predecessors. The core thesis of TBD is simple, yet profoundly difficult to execute: **The mainline branch (`trunk` or `main`) must always be in a deployable, working state.**

### A. The Failure Modes of Long-Lived Branching

The primary antagonist to TBD is the "Big Bang Integration." When developers operate in isolation on branches that live for weeks or months, they accumulate divergence. This divergence leads to:

1.  **Integration Hell:** The moment the feature branch finally attempts to merge, it encounters a cascade of conflicts, dependency mismatches, and behavioral regressions that are exponentially harder to debug than if they had been addressed incrementally. The cost of integration rises non-linearly with branch age.
2.  **Context Switching Debt:** Developers become deeply familiar with the isolated state of their branch, leading to "local optima" thinking. They solve problems relative to the branch's starting point, forgetting how the rest of the system has evolved on `main`.
3.  **Stale Dependencies:** The longer a branch lives, the more likely it is to drift from the current reality of the trunk. Updating dependencies or adapting to upstream API changes becomes a monumental, non-feature-related task.

### B. The TBD Philosophy: Integration as the Primary Artifact

TBD flips the script. Instead of treating integration as a *final step* (the merge), it treats integration as the *primary, continuous activity*.

The guiding principle, as highlighted by industry leaders, is that the goal is not merely to *merge* code, but to ensure that the *system* works together.

> **Expert Insight:** When we discuss TBD, we are not merely talking about Git commands. We are discussing a cultural shift where the *cost of delay* is recognized as being higher than the *cost of immediate, small integration*.

The role of the short-lived branch is thus not to *isolate* work, but to *contain* the scope of the change to a minimal, testable unit, ensuring that the divergence from `main` is measured in hours, not weeks.

### C. Conceptualizing the "Trunk"

The term "trunk" is often used interchangeably with `main` or `master`. For an expert audience, it is crucial to understand that the "trunk" represents the **single source of truth for the current, deployable state of the system**.

*   **Ideal State:** The `main` branch should always pass the full suite of automated tests (unit, integration, contract, and smoke tests) and, ideally, be tagged as deployable to staging or even production, depending on the release cadence.
*   **The Safety Net Misconception:** Some developers view TBD as "just merging to main." This is a dangerous oversimplification. The safety net is not the branch itself; the safety net is the **robust, automated testing suite** that validates the merge *before* it lands.

***

## II. The Mechanics of Short-Lived Branching

If TBD is the philosophy, the short-lived branch is the tactical instrument. These branches must be treated with the same reverence as the `main` branch itself—they are ephemeral, highly focused, and must be resolved quickly.

### A. Defining "Short-Lived" Quantitatively

For the purposes of this advanced discussion, "short-lived" must be defined relative to the development cycle, not just the time elapsed.

1.  **Time Constraint:** Ideally, a feature branch should exist for less than one working day, certainly not exceeding 24-48 hours for anything beyond trivial bug fixes.
2.  **Scope Constraint:** The branch must encapsulate a single, atomic unit of work. If the work requires touching three unrelated modules (e.g., UI, API endpoint, and database migration), it should be broken into three separate, sequential PRs, even if they are conceptually related.
3.  **Integration Frequency:** The branch must be rebased or merged back into the latest `main` *at least* once per day, regardless of whether the feature is complete. This is the active maintenance required to prevent drift.

### B. The Workflow Mechanics: PRs as Integration Gates

The Pull Request (PR) or Merge Request (MR) is the formalized mechanism through which the short-lived branch interacts with the trunk. It is the *gate*, not the *destination*.

The process must be highly formalized:

1.  **Branch Creation:** `git checkout -b feature/JIRA-123-atomic-fix main`
2.  **Development & Committing:** Work is done, committing small, logical chunks.
3.  **Self-Validation (Local):** Running the full local test suite (`npm test` or equivalent).
4.  **Synchronization (Crucial Step):** Before opening the PR, the developer *must* pull the latest changes from `main` into their feature branch. This is usually done via `git rebase main` or `git merge main`.
    *   **Expert Note on Rebase vs. Merge:** For short-lived branches, **rebasing is generally preferred** for the developer's local cleanup, as it creates a linear history, making the final merge cleaner. However, the CI system must be configured to handle the merge commit correctly, as the PR process itself often necessitates a merge commit to preserve the history of the integration event.
5.  **PR Submission:** The PR is opened, triggering the CI pipeline.

### C. Pseudocode Example: The Idealized Integration Cycle

Consider a developer working on a new validation rule for a user profile endpoint.

```pseudocode
// 1. Start from the latest known good state
git checkout main
git pull origin main

// 2. Create the isolated, minimal branch
git checkout -b feature/profile-validation-v2

// 3. Implement the change (e.g., adding a regex check)
# ... coding ...
git add .
git commit -m "feat: Add regex validation for profile email format"

// 4. CRITICAL STEP: Sync with the trunk to catch upstream changes
git fetch origin
git rebase origin/main 

// 5. Test locally (Must pass all unit/integration tests)
run_test_suite() 

// 6. Push and open PR
git push origin feature/profile-validation-v2
// Open PR targeting 'main'
```

If the rebase fails due to conflicts, the developer *stops* and resolves them immediately, rather than waiting for the CI pipeline to fail hours later.

***

## III. The Pillars of Resilience: CI/CD and Testing Strategies

A short-lived branch strategy is utterly meaningless without an equally rigorous Continuous Integration/Continuous Delivery (CI/CD) pipeline. The pipeline *is* the enforcement mechanism for TBD.

### A. The CI Pipeline: Beyond Unit Tests

For an expert audience, we must move past the notion of "running tests." We must discuss the *types* and *depth* of testing required at every merge gate.

#### 1. Unit Testing (The Foundation)
These tests must be fast, isolated, and cover the business logic exhaustively. They are the first line of defense. If a unit test fails, the PR is immediately blocked.

#### 2. Integration Testing (The Contract Check)
These tests verify that different components (e.g., the service layer talking to the repository layer, or the API gateway talking to the business logic) interact correctly. They test the *seams* between modules.

#### 3. Contract Testing (The Inter-Service Agreement)
This is perhaps the most critical, yet often overlooked, element in microservices architectures utilizing TBD. When Service A depends on Service B, we cannot wait for both services to be deployed together.

*   **Mechanism:** Consumer-Driven Contract (CDC) testing (e.g., using Pact). Service A (the consumer) defines the expected contract (the API endpoint, payload structure, and response codes) it needs from Service B (the provider).
*   **TBD Application:** When Service A is merged to `main`, its PR must pass tests verifying that its *assumed* contract with Service B is valid, *even if* Service B's code hasn't been merged yet. The CI pipeline must run the contract tests against a mocked or stubbed version of the dependency, ensuring the local change doesn't violate the established agreement.

#### 4. End-to-End (E2E) Testing (The Smoke Test)
E2E tests are slow and brittle, so they should *not* be the primary gatekeeper for every single PR. Instead, they should be reserved for:
*   Nightly builds on the `main` branch.
*   Deployment to a dedicated Staging/Pre-Prod environment.
*   They serve as the final confirmation that the *entire system* behaves as expected, confirming the hypothesis that the small, frequent merges have not accumulated into a systemic failure.

### B. Feature Toggles (The Ultimate Safety Mechanism)

If the goal of TBD is to keep `main` deployable, but the feature being built is not ready for *all* users, the solution is the **Feature Toggle** (or Feature Flag).

A feature toggle is a conditional switch in the code that allows a feature to be deployed to production (and thus merged into `main`) while remaining dormant or invisible to end-users.

**The TBD/Feature Toggle Synergy:**

1.  **Development:** Developer A builds Feature X on `feature/X`. They wrap all new code paths within a flag check:
    ```pseudocode
    if (feature_flag_is_enabled("feature_x_v2")) {
        // New, experimental code path
        process_new_logic(data);
    } else {
        // Legacy path (the current stable code)
        process_old_logic(data);
    }
    ```
2.  **Integration:** The PR merges this code into `main`. Because the flag is off by default, the merge passes all tests, and the system remains stable. The code is now *in* the trunk, but inert.
3.  **Deployment:** The build is deployed to production. The feature is *not* visible.
4.  **Release:** Once QA signs off, or the product owner flips the flag in the feature management system (e.g., LaunchDarkly), the feature becomes active instantly, without requiring a new deployment or a complex rollback.

**Expert Warning:** Treating feature flags as a mere "switch" is a rookie mistake. They must be treated as **first-class citizens of the architecture**. They require dedicated management, testing (testing the *off* state, the *on* state, and the *transition* state), and a clear deprecation roadmap.

***

## IV. Advanced Workflow Management and Edge Cases

For researchers pushing the boundaries of CI/CD, the simple "create branch, merge, done" model breaks down under the weight of complexity, large teams, and legacy systems.

### A. Handling Large Codebases and Monorepos

In massive repositories (Monorepos), the sheer volume of code makes traditional branching strategies prohibitively slow. TBD shines here because it forces developers to think in terms of *minimal change sets*.

*   **Dependency Graph Awareness:** The developer must not only know *what* they are changing, but *what else* might be affected by that change. Tools that can analyze the dependency graph *before* the PR is opened are invaluable.
*   **Impact Analysis:** Advanced tooling should provide a pre-merge analysis: "This change touches Module A, which has 15 active consumers, and Module B, which has a known breaking change in its contract definition." This shifts the burden of architectural oversight from manual review to automated analysis.

### B. The Problem of Shared Mutable State

The most dangerous aspect of TBD is when multiple, unrelated features modify the same piece of shared, mutable state (e.g., a global cache, a shared database table schema, or a singleton service).

*   **The Race Condition:** If Feature A assumes State X and Feature B assumes State Y, and they merge concurrently, the resulting state might be neither X nor Y, leading to unpredictable runtime errors that are notoriously difficult to reproduce.
*   **Mitigation: Explicit State Contracts:** The solution is to move away from implicit shared state mutation. Instead, enforce explicit contracts:
    1.  **Database:** Use schema migration tools (Flyway, Liquibase) that are version-controlled and applied *before* the application code that relies on them is deployed. The migration itself must be backward-compatible for at least one release cycle.
    2.  **API:** Use strict versioning (`/v1/users`, `/v2/users`). A new feature should never break the contract of an existing, stable endpoint.

### C. Rollback Strategies in TBD

In Git Flow, rolling back often meant reverting an entire release branch. In TBD, the concept of "rollback" must be granular and fast.

1.  **Code Rollback (The Quick Fix):** If a merge introduces a bug, the immediate action is to revert the specific commit or the entire PR merge commit (`git revert <SHA>`). This is fast because the change set is small.
2.  **Feature Flag Rollback (The Preferred Method):** If the bug is related to a new feature, the *preferred* rollback is to flip the feature flag off. This is instantaneous, requires no code deployment, and proves the system's resilience.
3.  **Database Rollback (The Hardest Part):** Database rollbacks are the Achilles' heel. If a feature requires a schema change (e.g., adding a non-nullable column), and that change causes a failure, simply reverting the code is insufficient if the schema change was already applied. This necessitates **additive, non-breaking migrations** that can be safely reverted or bypassed by the application logic.

### D. Handling External Dependencies and Third-Party APIs

What happens when the "trunk" depends on a third-party API that changes its rate limits or deprecates an endpoint without warning?

*   **The Anti-Corruption Layer (ACL):** Experts must build an ACL. This is a dedicated service or module whose *sole purpose* is to mediate between the core business logic and the external system.
*   **Benefit:** If the external API changes, you only need to update the ACL. The rest of the application, which communicates with the ACL via a stable, internal contract, remains untouched. This isolates the blast radius of external volatility.

***

## V. The Human Element: Process, Culture, and Tooling

Ultimately, TBD is a socio-technical system. The best tooling cannot compensate for poor process discipline.

### A. The Role of Code Ownership and Review

In a TBD environment, code ownership must be fluid but accountable.

*   **Mandatory Review Depth:** Reviews cannot be superficial. Reviewers must act as integration architects, asking: "If this code merges, what is the *next* thing that will break because of this change?"
*   **Pair Programming/Mob Programming:** For complex, high-risk features, the best practice is to minimize the number of people who have the feature branch in isolation. Working together reduces the surface area for divergent thinking.

### B. Tooling Stack Requirements (A Checklist for Experts)

A modern TBD implementation requires more than just Git and GitHub. The toolchain must enforce the discipline:

| Component | Requirement | Purpose in TBD |
| :--- | :--- | :--- |
| **VCS** | Git (or equivalent) | Must support lightweight, atomic branching. |
| **CI System** | Jenkins, GitLab CI, GitHub Actions | Must execute the full, multi-stage test suite on *every* PR. |
| **Testing Framework** | JUnit, Jest, etc. | Must enforce fast, isolated unit and integration testing. |
| **Contract Testing** | Pact, Spring Cloud Contract | Must validate service boundaries *before* deployment. |
| **Feature Management** | LaunchDarkly, ConfigMaps | Must allow runtime toggling of features to decouple deployment from release. |
| **Schema Management** | Flyway, Liquibase | Must enforce versioned, backward-compatible database migrations. |

### C. Measuring Success: Metrics Beyond Velocity

If you are researching techniques, you must measure the *quality* of the integration, not just the speed of commits. Key metrics to track:

1.  **Lead Time for Changes (LTC):** Time from code commit to code running in production. TBD aims to minimize this to hours.
2.  **Change Failure Rate (CFR):** Percentage of deployments that cause immediate production failure. TBD aims to drive this toward zero.
3.  **Mean Time to Recovery (MTTR):** How quickly can you restore service after a failure? TBD, with its small change sets, inherently lowers MTTR because the scope of the failure is small.

***

## Conclusion: The Continuous State of Integration

Trunk-Based Development with short-lived branches is not a destination; it is a perpetual state of high-frequency, low-risk integration. It is the architectural discipline that forces developers to treat the mainline branch not as a repository of *completed* features, but as a living, breathing, constantly validated *system*.

For the expert researcher, the takeaway is that the complexity is not in the branching model itself, but in the **rigor of the supporting infrastructure**. You are not just merging code; you are executing a complex, multi-layered contract negotiation involving unit tests, contract validation, feature flag governance, and schema compatibility—all within the lifespan of a single working day.

Mastering this pattern means accepting that the greatest technical debt is not the unmerged feature, but the *assumption* that the system will magically work when all the pieces are finally assembled. The only way to guarantee that assembly is to test the seams constantly, and to keep the pieces small enough that when they finally connect, they don't require a full-scale archaeological dig to understand why they broke.

If you can maintain this discipline, you are not just participating in DevOps; you are architecting for resilience at the atomic level. Now, go build something that can survive the inevitable, and inevitable, failure.
