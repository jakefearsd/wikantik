---
cluster: software-engineering-practices
canonical_id: 01KQ0P44W4V88HAYPBYJPVXF3Z
title: Scrum Framework
type: article
tags:
- sprint
- team
- must
summary: Technical breakdown of the Scrum framework, focusing on roles, ceremonies, and artifacts for iterative delivery.
auto-generated: false
---

# Scrum Framework: Roles, Ceremonies, and Artifacts

Scrum is an iterative framework for managing complex work. It relies on transparency, inspection, and adaptation to deliver value in fixed-length iterations called Sprints.

## I. Scrum Roles

Scrum defines three core roles with distinct accountabilities.

### A. Product Owner (PO)
The Product Owner is responsible for maximizing the value of the product resulting from the work of the Scrum Team.
*   **Backlog Management:** Developing and communicating the Product Goal and ordering Product Backlog items.
*   **Economic Arbitrator:** Prioritizing work based on business value, risk, and dependencies.
*   **Value Optimization:** Ensuring the team works on the highest-impact items first. Use quantitative models like **Weighted Shortest Job First (WSJF)** for complex prioritization:
    $$
    \text{Priority Score} = \frac{\text{Business Value} + \text{Time Criticality} + \text{Risk Reduction}}{\text{Effort}}
    $$
### B. Scrum Master (SM)
The Scrum Master is accountable for establishing Scrum as defined in the Scrum Guide.
*   **Coaching:** Helping the team and the organization understand Scrum theory and practice.
*   **Impediment Removal:** Identifying and eliminating friction points that prevent the team from achieving the Sprint Goal.
*   **Facilitation:** Ensuring that all Scrum events take place and are productive.

### C. Developers
Developers are the people in the Scrum Team that are committed to creating any aspect of a usable Increment each Sprint.
*   **Self-Management:** Developers decide how to turn Product Backlog items into Increments of value.
*   **Cross-Functionality:** The team collectively possesses all the skills (design, dev, test, ops) required to deliver a "Done" increment.

---

## II. Scrum Ceremonies (Events)

### A. Sprint Planning
Planning initiates the Sprint by laying out the work to be performed.
1.  **Sprint Goal:** Why is this Sprint valuable?
2.  **Sprint Backlog selection:** What can be Done this Sprint?
3.  **The Plan:** How will the chosen work get done?

### B. Daily Scrum
A 15-minute event for the Developers to inspect progress toward the Sprint Goal and adapt the Sprint Backlog as necessary. It is a planning session, not a status report.

### C. Sprint Review
The purpose of the Sprint Review is to inspect the outcome of the Sprint and determine future adaptations. The Scrum Team presents the results of their work to key stakeholders and progress toward the Product Goal is discussed.

### D. Sprint Retrospective
The purpose of the Sprint Retrospective is to plan ways to increase quality and effectiveness. The team inspects how the last Sprint went with regards to individuals, interactions, processes, tools, and their Definition of Done.

---

## III. Scrum Artifacts

### A. Product Backlog
The Product Backlog is an emergent, ordered list of what is needed to improve the product. It is the single source of work undertaken by the Scrum Team.

### B. Sprint Backlog
The Sprint Backlog is composed of the Sprint Goal (why), the set of Product Backlog items selected for the Sprint (what), as well as an actionable plan for delivering the Increment (how).

### C. Increment
An Increment is a concrete stepping stone toward the Product Goal. Each Increment is additive to all prior Increments and thoroughly verified, ensuring that all Increments work together.

#### Definition of Done (DoD)
The DoD is a formal description of the state of the Increment when it meets the quality measures required for the product.
*   **Typical Criteria:** Unit test coverage (e.g., >80%), integration tests passed, security scans completed, and documentation updated.
