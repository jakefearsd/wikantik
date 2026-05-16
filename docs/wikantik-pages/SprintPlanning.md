---
cluster: software-engineering-practices
canonical_id: 01KQ0P44WV09A6KGT92QM81GCX
title: "Sprint Planning & Capacity Modeling"
type: article
tags: [scrum, agile, planning, velocity, capacity]
date: 2025-05-15
summary: A practitioner's guide to Sprint Planning, moving beyond simple averages to rigorous capacity modeling and volatility analysis.
auto-generated: false
---

# Sprint Planning: The Engineering of Commitment

Sprint Planning is the process of aligning a team's **Capacity** (available hours) with its **Velocity** (historical output) to create a high-confidence commitment. Practitioners must distinguish between "Yesterday's Weather" and active capacity constraints.

---

## I. Capacity Modeling

Capacity is the total amount of time the team can spend on sprint work, accounting for "Focus Factor."

### A. The Capacity Formula
$$\text{Available Capacity} = (\text{Total Dev Hours}) \times \text{Focus Factor}$$1.  **Total Dev Hours:** (Number of Devs$\times$Days in Sprint$\times$Hours per Day).
2.  **Focus Factor ($F$):** A value between$0.6$and$0.8$representing the time spent on actual coding/testing after subtracting meetings, email, and context switching.
    *   **Low F (0.4-0.5):** Heavy on-call rotation or fragmented meetings.
    *   **High F (0.8-0.9):** Deep-work environment, minimal overhead.

### B. Individual Capacity Worksheet (Example)
*   **Dev A:** 10 days$\times$6 effective hrs = 60 hrs.
*   **Dev B:** 8 days (2 days PTO)$\times$6 effective hrs = 48 hrs.
*   **Total Team Capacity:** 108 hours.

---

## II. Velocity Volatility

Velocity is a measure of throughput, but its **volatility** measures the reliability of the team's planning.

### A. The Say/Do Ratio$$\text{Say/Do Ratio} = \frac{\text{Points Completed}}{\text{Points Committed}}$$*   **Target:**$0.9 - 1.1$.
*   **Under 0.8:** Indicates over-commitment or "Discovery Debt" (stories were too vague).
*   **Over 1.2:** Indicates sandbagging or under-estimation.

### B. Coefficient of Variation ($CV$)
To measure predictability, calculate the volatility of velocity over the last 5 sprints:$$CV = \frac{\sigma(V)}{\bar{V}}$$*   **Stable ($CV < 15\%$):** Use "Yesterday's Weather" (last sprint's velocity) for planning.
*   **Volatile ($CV > 20\%$):** Use a 3-sprint moving average and apply a 20% "uncertainty buffer."

---

## III. The Planning Workflow

1.  **Verify Definition of Ready (DoR):** Do the top stories have clear Acceptance Criteria?
2.  **Calculate Capacity:** Subtract PTO, holidays, and scheduled maintenance.
3.  **Select Stories:** Pull from the backlog based on priority until the sum of points$\approx$ Adjusted Velocity.
4.  **Task Out:** Break stories into sub-tasks (usually 2-6 hours each). If the sum of task hours > Available Capacity, the story must be removed.

---

## IV. Sprint Planning Template (YAML)

```yaml
sprint_id: "SP-2025-12"
goal: "Migrate Auth Service to v2 and reduce P95 latency"
capacity:
  total_members: 5
  planned_pto_days: 2
  focus_factor: 0.7
  available_hours: 196 # (5 devs * 8h * 10d * 0.7) - (2 pto * 8h * 0.7)
velocity:
  last_sprint: 42
  moving_avg_3_sprint: 38
  volatility_cv: 0.12
  planning_target: 38
commitment:
  total_points: 36
  stories:
    - id: "AUTH-01"
      points: 8
      risk: "High (Integration with legacy)"
    - id: "AUTH-02"
      points: 5
      risk: "Low"
  unplanned_buffer_hours: 20
```

---

## V. Critical Failure Modes

1.  **Planning to 100% Capacity:** Never plan to 100%. Leave 10-20% buffer for the "unknown unknowns" that surface mid-sprint.
2.  **Point Inflation:** Increasing story points to "show growth" in velocity. This destroys the predictive power of the metric.
3.  **Ignoring the "Definition of Done":** Claiming points for stories that are "mostly finished." Velocity is binary: 0 or 100.
4.  **The "Hero" Fallacy:** Assuming one senior dev can absorb the work of three juniors. Capacity is not fungible across specialized domains.
