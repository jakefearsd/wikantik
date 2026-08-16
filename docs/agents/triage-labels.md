# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  | Exists in the tracker? |
| -------------------------- | -------------------- | ---------------------------------------- | ---------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  | **No — not created**   |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information | **No — not created**   |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  | **No — not created**   |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            | **No — not created**   |
| `wontfix`                  | `wontfix`            | Will not be actioned                     | Yes                    |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

> **Status (verified 2026-08-16 via `gh label list --repo jakefearsd/wikantik`):**
> only `wontfix` is defined on the repository. The other four are the *intended*
> vocabulary carried over from the upstream skill defaults, but they have never
> been created, so applying one fails rather than filing the issue under it. The
> repo's real labels today are the GitHub defaults (`bug`, `documentation`,
> `duplicate`, `enhancement`, `good first issue`, `help wanted`, `invalid`,
> `question`, `wontfix`) plus `dependencies` and `java`.
>
> Resolve this one of two ways — do not leave it half-true:
> ```bash
> # (a) adopt the vocabulary: create the four missing labels
> gh label create needs-triage    --repo jakefearsd/wikantik --color FBCA04 --description "Maintainer needs to evaluate this issue"
> gh label create needs-info      --repo jakefearsd/wikantik --color D4C5F9 --description "Waiting on reporter for more information"
> gh label create ready-for-agent --repo jakefearsd/wikantik --color 0E8A16 --description "Fully specified, ready for an AFK agent"
> gh label create ready-for-human --repo jakefearsd/wikantik --color 1D76DB --description "Requires human implementation"
> ```
> or **(b)** rewrite the right-hand column to map each role onto a label that
> already exists, and delete this note.
