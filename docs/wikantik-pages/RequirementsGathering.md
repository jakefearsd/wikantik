---
cluster: software-engineering-practices
canonical_id: 01KQ0P44VHNK9Y4GJ9CDJAY8H0
title: "Requirements: JTBD & Specification by Example"
type: article
tags: [requirements, jtbd, agile, specification, design]
date: 2025-05-15
summary: A practitioner's guide to requirements gathering using the 'Jobs to be Done' framework and 'Specification by Example' to create unambiguous, testable specifications.
auto-generated: false
---

# Requirements: Engineering the Desired Outcome

Requirements gathering is not about asking users what they want; it is about discovering the **progress they are trying to make**. This guide focuses on two authoritative frameworks: **Jobs to be Done (JTBD)** for discovery and **Specification by Example (SbE)** for formalization.

---

## I. Jobs to be Done (JTBD)

Users don't "buy" products; they "hire" them to do a job. The goal of requirements elicitation is to identify the **Job Story**.

### A. The Job Story Format
Unlike User Stories, Job Stories focus on the **Situation** and **Motivation** rather than just the Persona.
> **"When [Situation], I want to [Motivation], so I can [Expected Outcome]."**

*   *User Story:* "As a manager, I want a report so I can see sales data."
*   *Job Story:* "When **the quarter is ending**, I want to **compare actuals vs. targets**, so I can **decide which regions need corrective action**."

### B. The Four Forces of Progress
To uncover deep requirements, analyze the forces acting on the user:
1.  **Push:** The pain of the current solution (e.g., "The old system is slow").
2.  **Pull:** The attraction of the new solution (e.g., "The new system has real-time alerts").
3.  **Anxiety:** Fear of the new (e.g., "Will I lose my data?").
4.  **Inertia:** Attachment to the old (e.g., "I'm used to my Excel macros").

---

## II. Specification by Example (SbE)

Specification by Example transforms vague requirements into a set of concrete, illustrative examples that serve as both documentation and automated tests.

### A. The SbE Workflow
1.  **Derive Examples:** During a "Three Amigos" workshop (Dev, QA, Product), brainstorm scenarios for the job.
2.  **Refine Examples:** Convert scenarios into a structured format (e.g., Gherkin).
3.  **Automate:** Use the examples as the basis for automated acceptance tests.

### B. Example: Discount Logic
**Vague Requirement:** "Users get a discount for large orders."
**Specification by Example:**

| Order Total | Customer Type | Discount Applied | Expected Total |
| :--- | :--- | :--- | :--- |
| $50.00 | Standard | 0% | $50.00 |
| $150.00 | Standard | 10% | $135.00 |
| $50.00 | VIP | 15% | $42.50 |
| $150.00 | VIP | 25% | $112.50 |

---

## III. The Requirements Worksheet (YAML)

Use this template to document the findings from an elicitation session.

```yaml
job_id: "JOB-402"
job_summary: "Reconcile daily transactions against bank statements"
situation: "End-of-day accounting closure"
motivation: "Identify missing or duplicated entries without manual line-matching"
desired_outcomes:
  - "Zero discrepancy between internal ledger and statement"
  - "Completion of reconciliation in < 15 minutes"

specification_by_example:
  scenarios:
    - name: "Exact Match"
      given: "Ledger has $100 entry; Statement has $100 entry"
      when: "Reconciliation runs"
      then: "Both entries are marked 'Matched'"
    - name: "Discrepancy - Missing Statement Entry"
      given: "Ledger has $50 entry; Statement has no matching entry"
      when: "Reconciliation runs"
      then: "Ledger entry is flagged 'Unmatched - Missing from Statement'"

constraints:
  security: "Bank credentials must never be stored in plaintext"
  data_retention: "Audit logs must persist for 7 years per policy FIN-01"
```

---

## IV. Critical Failure Modes

1.  **Focusing on "Solutions" too early:** When a user says "I need a button that does X," they are prescribing a solution. The requirement is the **need** the button satisfies.
2.  **Ignoring the "Job" for the "Feature":** Building features that users hire once and never use again because they didn't solve a recurring pain point.
3.  **Ambiguous Adjectives:** Using words like "fast," "secure," or "intuitive." If it isn't measurable, it isn't a requirement. Use **quantifiable thresholds** (e.g., "95% of tasks completed without help-text usage").
4.  **Implicit Assumptions:** Failing to document the "Given" context (e.g., assuming the user is always online).
