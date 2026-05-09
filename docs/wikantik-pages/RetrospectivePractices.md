---
cluster: software-engineering-practices
canonical_id: 01KQ0P44VR0WJHC48G1XG0F5J5
title: "Retrospective Practices & Facilitation"
type: article
tags: [agile, scrum, retrospective, team, feedback]
date: 2025-05-15
summary: A practitioner's guide to running high-impact retrospectives, featuring concrete scripts for the Sailboat and Starfish techniques and formal action-tracking protocols.
auto-generated: false
---

# Retrospectives: The Engine of Continuous Improvement

A retrospective is not a status meeting; it is a **controlled experiment in organizational epistemology**. For engineering teams, the goal is to calculate the deviation between the "Expected Flow" and the "Actual Flow" and implement a corrective vector.

---

## I. Facilitation Techniques & Scripts

### A. The Sailboat Retrospective (Goal & Risk Focus)
Best for: Mapping progress toward a major milestone and identifying external risks.

**The Script:**
1.  **The Island (Goal):** "Imagine we are at the end of the next release. What does success look like?"
2.  **The Wind (Accelerants):** "What internal/external factors are pushing our boat forward? (e.g., new CI/CD tools, clear requirements)."
3.  **The Anchors (Drag):** "What is holding us back or slowing us down? (e.g., technical debt, slow review cycles)."
4.  **The Rocks (Risks):** "What upcoming challenges could sink our boat? (e.g., key personnel PTO, API deprecations)."

---

### B. The Starfish Retrospective (Process Tuning)
Best for: Granular adjustment of team habits and "Stop/Start" decisions.

**The Script:**
1.  **Keep Doing:** "What are the high-value activities we must protect? (e.g., Pair programming on critical modules)."
2.  **Less Of:** "What are we doing that is low-value but not yet eliminable? (e.g., excessive status emails)."
3.  **More Of:** "What should we amplify for better flow? (e.g., more frequent, smaller PRs)."
4.  **Stop Doing:** "What is pure waste or friction? (e.g., the 3 PM status meeting)."
5.  **Start Doing:** "What new experiments should we run? (e.g., implementing an automated dependency checker)."

---

## II. The Prime Directive (Psychological Safety)
The process must be anchored in the **Retrospective Prime Directive**:
> "Regardless of what we discover, we understand and truly believe that everyone did the best job they could, given what they knew at the time, their skills and abilities, the resources available, and the situation at hand."

**Practitioner Tip:** If the team is silent, use "Safety Check" voting (1-5 scale). If the average is $< 3$, pivot the retro to focus exclusively on *why* the team doesn't feel safe to share feedback.

---

## III. Action Item Tracking (SMART-ER)

Retrospective insights are worthless without accountability. Every action item must be:
*   **Specific:** One concrete change.
*   **Measurable:** A delta in a metric.
*   **Reviewable:** Re-checked in the next retro.

### Retrospective Action Tracker (YAML)
```yaml
retro_date: "2025-05-12"
technique: "Starfish"
top_root_cause: "High context-switching due to unscheduled 'urgent' tasks"
action_items:
  - id: "AI-2025-001"
    description: "Implement a 'Shield' role for one developer per day to handle all ad-hoc requests"
    owner: "@jake_lead"
    success_metric: "20% increase in deep-work hours logged"
    review_date: "2025-05-26"
  - id: "AI-2025-002"
    description: "Deprecate the 'General' Slack channel for production alerts; move to #ops-only"
    owner: "@sre_team"
    success_metric: "Reduction in alert noise for feature devs"
    review_date: "2025-05-26"
```

---

## IV. Critical Failure Modes

1.  **The "Moan Fest":** Discussing problems without proposing solutions.
2.  **Lack of Follow-through:** Generating 10 action items and completing 0. (Limit to 1-2 high-impact items).
3.  **Focusing on People, Not Systems:** Blaming "Developer X" instead of fixing the "Process Y" that allowed the error.
4.  **Facilitator Bias:** The lead or manager dominating the conversation. The facilitator must remain a neutral "Process Architect."
