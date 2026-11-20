# Work management polish round (2026-11-20)

## Intent

All four phases of work management are live (2.6.2), but the owner has barely used it. This round fixes what can
be fixed with confidence and makes the feature easy to pick up, ahead of the owner's own hands-on testing. It is
polish: no new concepts, no new surfaces beyond a user guide.

## Goals

1. **Squash and merge commits count as reviewed** when nothing was pushed after the approval (owner chose option b).
2. **Board order survives a reload** when cards share a rank.
3. **Product setting changes leave a record** (autonomy ceiling and deploy gate matter for agent authority).
4. **A user guide**, `docs/user/WorkManagement.md`, that walks first use end to end and doubles as the owner's
   test script.
5. **A newcomer's pass over `/work`** in the local deployment: fix clear defects; list judgment calls for the owner.

## Non-goals and anti-goals

- No change to the rulings the reviews settled: sequential ids, approved deployments not expiring, a rollback not
  reopening items. Not reopened here.
- No code-host sync, on-call trigger or sprints (later rounds).
- Anti-goal: the squash fix must not let an agent merge work that was changed after approval.

## 1. Squash merges

Today `WorkChangesService.merge` decides `reviewed = locked.approvedForHead() && sha.equals(locked.headSha())`.
A squash or merge commit has a new id, so a person's merge records `unreviewed`, and an **agent's merge is refused**
(`NOT_APPROVED`) — agents cannot squash-merge at all.

**Approaches considered**

- (a) Keep strict. Honest, but every normal GitHub flow reads "merged without review", and agents are blocked.
- **(b) Chosen:** reviewed when the approval stands on the change's current head (nothing pushed since approval),
  whatever the merged commit id. The merged id is still recorded as `merged_sha`. Same trust as today: heads are
  already self-reported.
- (c) The merge report names the approved commit it squashed. Same trust as (b), one more field for every agent.

**Design:** `reviewed = locked.approvedForHead()`. An agent's merge is allowed under the same condition (approval
stands on the current head). A head pushed after approval still voids the approval, exactly as today, so the
anti-goal holds. Update the `merge_change` MCP tool description, REST/operations docs and any UI wording that says
the reported commit must equal the approved one. Tests: a person's and an agent's merge with a different merged sha
after approval → `reviewed`; after a post-approval push → agent refused, person `unreviewed` (pin both sides).

## 2. Equal-rank board order

The client computes a rank between neighbours (`rankForInsert`); when neighbours tie (or the gap has shrunk below
what a double can split), the new rank equals a neighbour and the order is lost on reload.

**Design (my call):** the server renumbers. When a move's rank ties a neighbour in the target column (or the gap is
too small to split), the store renumbers that column at `RANK_STEP` spacing, right after the move and under row locks
on the column (rank only: no version bump, no event), preserving the order the client asked for, and the reply says the column was renumbered so the board reloads it. No data
migration: columns heal on their next move. The builder chooses how the client conveys the intended position
(neighbour keys vs. the rank) as long as the PATCH contract stays backward compatible for agents.

## 3. Product change record

`work_event.project_id` is NOT NULL, and products sit above projects, so product edits fit no existing log.

**Design (my call):** a migration adds `work_product_event` (product id, at, actor, actor kind, field, from, to),
written in the same transaction as `updateProduct`, one row per changed field (name, repo, default branch, wiki
cluster, autonomy ceiling, deploy gate, archive). The product admin form shows a short "History" list. Admin-only,
like the form. DDL only; no backfill.

## 4. User guide

`docs/user/WorkManagement.md`, in the voice of the other `docs/user/*.md` guides. Order of first use: turn it on
(admin flag), create a product, create a project and add members, items and the board, epics and dates, a change
(open → review → approve → merge), a deployment (request → approve → run → verify), registering an agent and its
token, autonomy in one paragraph, and the "Done without you" lane. Each step says what you should see, so it reads
as a test script. Link it from the `/work` UI (help link) and from the operations guide.

## 5. Newcomer's pass

Walk `/work` in the local deployment as a first-time user following the guide: empty states that do not say what to
do next, dead ends, confusing wording, broken layout at phone width. Fix clear defects with tests; bring the rest to
the owner as a list. The guide and the pass inform each other: anything the guide has to apologise for is a defect.

## Left to the builder

Exact wording; how the board reply signals a renumber; the history list's layout; whether the help link sits in the
header or the Work home.
