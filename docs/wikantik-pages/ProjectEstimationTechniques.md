---
cluster: software-engineering-practices
canonical_id: 01KQ0P44TWA0SYDHD67R20G9ZK
title: "Advanced Project Estimation & Forecasting"
type: article
tags: [estimation, agile, mathematics, forecasting]
date: 2025-05-15
summary: Moving beyond "gut feel" to probabilistic forecasting using PERT distributions and Monte Carlo simulations for high-fidelity project delivery.
auto-generated: false
---

# Engineering Estimation: From Guesswork to Forecasting

In complex software systems, estimation is the process of surfacing and resolving underlying assumptions. While traditional methods rely on single-point "expert" opinions, high-density practitioner environments utilize probabilistic models to manage uncertainty.

---

## I. Relative Sizing: Planning Poker
Planning Poker mitigates **Anchoring Bias** by requiring simultaneous reveal of estimates using the Fibonacci sequence (1, 2, 3, 5, 8, 13, 21).

*   **The Goal:** Not the number, but the **variance reduction** achieved through discussion of outliers.
*   **The Rule:** If the team can't converge after 3 rounds, the story is too ambiguous and must be decomposed.

---

## II. PERT: Three-Point Estimation
The **Program Evaluation and Review Technique (PERT)** uses a weighted average to account for the "long tail" of software risks.

### A. The Math of PERT
For every task, gather three values:
1.  **$O$(Optimistic):** Best case (everything goes right).
2.  **$M$(Most Likely):** The consensus estimate.
3.  **$P$(Pessimistic):** Worst case (everything goes wrong).

**Expected Value ($E$):**

$$
E = \frac{O + 4M + P}{6}
$$

**Standard Deviation ($\sigma$):**

$$
\sigma = \frac{P - O}{6}
$$

**Why it works:** Unlike a simple average, PERT weights the "Most Likely" case and recognizes that the risk ($P$) is often much further from$M$than the opportunity ($O$).---

## III. Monte Carlo Simulations for Forecasting
Monte Carlo simulations replace deterministic "deadlines" with a **probability distribution of completion dates**.

### A. The Simulation Model
Instead of saying "The project will take 10 weeks," we run 10,000 simulations where each run samples from:
*   **Backlog Size:**$N \pm \Delta N$(accounting for scope creep).
*   **Velocity:** A distribution of historical team velocity (e.g.,$15 \pm 5$ points/sprint).

### B. Example Python-like Logic
```python
def run_simulation(backlog_range, velocity_dist, runs=10000):
    results = []
    for _ in range(runs):
        total_scope = random.sample(backlog_range)
        current_velocity = random.sample(velocity_dist)
        weeks_to_finish = total_scope / current_velocity
        results.append(weeks_to_finish)
    
    # Analyze the 85th and 95th percentiles
    p85 = percentile(results, 85)
    p95 = percentile(results, 95)
    return p85, p95
```

### C. Interpreting the Result
*   **P50:** 50% chance. (A coin flip; never commit to this).
*   **P85:** 85% chance. (Standard "safe" commitment for internal stakeholders).
*   **P95:** 95% chance. (The "high confidence" date for external release/marketing).

---

## IV. The Estimation Template (JSON)

Use this schema to capture the raw inputs for a forecasting model.

```json
{
  "milestone": "Identity_Provider_Migration",
  "estimation_method": "PERT_Weighted",
  "items": [
    {
      "task": "OAuth2_Schema_Design",
      "optimistic": 3,
      "most_likely": 5,
      "pessimistic": 13,
      "pert_e": 6.0,
      "pert_std": 1.66
    },
    {
      "task": "Legacy_Data_Cleanup",
      "optimistic": 5,
      "most_likely": 13,
      "pessimistic": 40,
      "pert_e": 16.1,
      "pert_std": 5.83
    }
  ],
  "confidence_interval": "P85",
  "projected_velocity_range": [20, 35]
}
```

---

## V. Governance: The Estimation Ethics
1.  **Never Average:** If one dev says 3 and another says 13, do not record 8. Discuss the gap. One of them knows something the other doesn't.
2.  **Estimate Complexity, Not Time:** Time is a derivative of complexity and team capacity.
3.  **Update Frequently:** Rerun Monte Carlo simulations after every sprint using actual velocity data to update the P85 date.
