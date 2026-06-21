---
tags:
- agile
- kanban
- wip
- flow
- metrics
cluster: software-engineering-practices
type: article
date: 2025-05-15T00:00:00Z
auto-generated: false
canonical_id: 01KQ0P44RFMB8PECPTBW3RZGKB
summary: A deep dive into Kanban as a flow-control system, focusing on WIP limits,
  Little's Law, and the diagnostic power of Cumulative Flow Diagrams.
title: Kanban Methodology & Flow Optimization
---

# Kanban: Engineering the Flow of Value

Kanban is more than a board; it is a **pull-based system** for managing the flow of value through a process. For engineering leaders, the goal is not to maximize activity, but to minimize **Cycle Time** by managing the primary constraint: Work-in-Progress (WIP).

---

## I. The Theoretical Foundation

### A. Little's Law
The fundamental law of flow states that in a stable system, the average number of items ($L$) is equal to the average arrival rate ($\lambda$) multiplied by the average time an item spends in the system ($W$).

$$
L = \lambda W \implies \text{WIP} = \text{Throughput} \times \text{Cycle Time}
$$

**The Practitioner's Insight:** To reduce Cycle Time ($W$) without changing your team's throughput capacity ($\lambda$), you **must** reduce the WIP ($L$). Adding more work to the board mathematically guarantees longer wait times.
---

## II. WIP Limits: The System Governor

WIP limits are non-negotiable constraints applied to columns on a Kanban board.

| Stage | WIP Limit | Logic |
| :--- | :--- | :--- |
| **Ready** | 5 | Prevents the "Backlog Explosion." |
| **In Dev** |$2 \times \text{Developers}$| Allows for pairing but prevents context switching. |
| **Review** | 2 | Forces the team to "Stop Starting and Start Finishing." |
| **Testing** | 3 | Prevents a bottleneck at the QA gate. |

### The "Swarm" Protocol
When a WIP limit is hit, the team must **stop pulling new work**. Instead, all available capacity "swarms" the bottleneck to move items to "Done." 

---

## III. Cumulative Flow Diagrams (CFD)

The CFD is the most powerful diagnostic tool in Kanban. It tracks the cumulative number of items in each state over time.

### How to Read a CFD
1.  **Vertical Distance:** The vertical gap between the "Arrivals" line and the "Departures" line is your current **WIP**.
2.  **Horizontal Distance:** The horizontal gap between the lines represents the **Lead Time**.
3.  **Slope:** The slope of the "Done" line is your **Throughput**.

### Diagnostic Patterns
*   **Diverging Lines:** If the top line (Arrivals) rises faster than the bottom line (Done), your WIP is growing, and your Lead Time is expanding. You have a **bottleneck**.
*   **Parallel Lines:** Indicates a stable system with predictable delivery.
*   **Steps/Flatlines:** Indicates a "Stagnant Flow" where work has stopped, likely due to a systemic blocker or external dependency.

---

## IV. Calculating Optimal WIP Limits

Don't guess. Use the **Bottleneck Capacity** method.

1.  Identify the slowest stage in your process (the bottleneck).
2.  Calculate its capacity ($C$) in items per week.
3.  Set the WIP limit for the bottleneck stage to$C$.
4.  Set upstream stages to$C + 1$(to ensure the bottleneck is never starved).

**Formula for Multi-team Flow:**

$$
\text{WIP}_{\text{max}} = \frac{\text{Target Cycle Time}}{\text{Historical Mean Processing Time}}
$$
---

## V. Flow Metrics Practitioner Template (YAML)

Use this format for your weekly flow audit.

```yaml
audit_date: "2025-05-12"
team: "Core_Platform"
metrics:
  avg_wip: 14
  throughput_items_per_week: 4.2
  avg_cycle_time_days: 23
  flow_efficiency: "35%" # (Value-add time / Total time)
bottlenecks:
  - stage: "Code_Review"
    reason: "Low reviewer availability due to off-site"
    action: "Implement mandatory 24h review SLA"
wip_adjustments:
  - stage: "Testing"
    old_limit: 5
    new_limit: 3
    rationale: "Lead time is diverging in CFD; reducing input to match QA capacity."
```

---

## VI. Critical Failure Modes

1.  **Invisible WIP:** Work that isn't on the board (emails, "quick favors") destroys the predictability of the system.
2.  **Violating Limits:** Treating WIP limits as "suggestions." If you don't stop pulling work when the limit is hit, you aren't doing Kanban.
3.  **Ignoring the "Done" Column:** If work is finished but not shipped (e.g., waiting for a release train), the "Done" column becomes a hidden inventory of waste.
