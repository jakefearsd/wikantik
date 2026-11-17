# Work Management — Design

Date: 2026-11-15
Status: approved by owner 2026-11-15

## Intent

The Simple Agility stack needs a place where work is assigned and tracked, readable
and writable by agents as easily as by people. The owner does not want JIRA or
another heavy project-management tool. The work is software development with story
refinement, documentation and admin work, and it moves in all four directions:
human to agent, agent to agent, agent to human, human to human.

The owner is ramping up agent concurrency across several vendors and locally hosted
models alongside their own work. Working directly on `main` will shrink to special
cases, so the design must treat branches and review as the normal path.

Work management is a first-class part of Wikantik with its own tables and module. It
reuses wiki services (identity, rendering, mentions, plumbing) but is not stored as
wiki pages and is not an add-on glued to them.

## Goals

- Epics, stories and tasks, organised into projects with different audiences, in an
  instance that serves one company.
- Work is invisible to anyone who is not a member of its project, on every surface.
- Status is highly visible: a board, a dates view, an activity feed, and full history
  for every item.
- A story can be refined in place: its text is versioned and diffable, with discussion
  alongside.
- Agents from any vendor can find, claim, update and hand off work over MCP or REST.
- Every item maps to the software product it changes, and its code changes are
  tracked from branch to merge to running in production.
- Who may approve, review, merge and deploy is configurable. A fully autonomous path
  exists and is explicit, but it is a special case and never the default.

## Non-goals

- Sprints, story points, velocity, time tracking, burndown charts — **not now, and
  not locked out**. The owner expects sprints may come. Nothing in the model may
  assume continuous flow only: a sprint must be addable later as a new table plus
  nullable columns on `work_item` (sprint, estimate), with no rework of items,
  events, the board query or the policy. A builder choice that would make that hard
  is wrong.
- Custom fields, configurable workflows, permission schemes, issue-type schemes.
- Multi-tenancy. One instance is one company.
- Launching or scheduling agents. Agents pull work; the system never starts one.
- Two-way sync with GitHub issues. An item can link to an issue; nothing more.
- Release planning (target versions, milestones) ahead of a deployment.
- Email or push notifications. (My call for now; the board and mentions page carry it.)
- Enforcing merges on the code host. See Constraints.

## Anti-goals

- Becoming JIRA. Every addition is weighed against "simple": if a feature needs an
  administrator to configure it before a team can work, it does not belong.
- A barnacle. Work items must not be wiki pages with extra frontmatter, and must not
  depend on page-store behaviour for state, history or isolation.
- A leak. A work item appearing in search, a bundle, the sitemap, the change feed or
  the Page Graph is a defect of the highest severity.
- A system that agents can use to grant themselves authority.

## Constraints

- All data access goes through `com.wikantik.jdbc.Jdbc`; schema changes are numbered
  idempotent migrations under `bin/db/migrations/`; database tests run against
  `PostgresTestDb`. Multi-statement writes get a rollback test.
- Wikantik never calls a chat model. Nothing here changes that.
- Observation telemetry goes to jakemon; data that is a functional input to
  Wikantik's own behaviour stays a product table. `work_event` is a functional input
  and stays here.
- New `wikantik.*` keys are declared in `wikantik.properties` first.
- New SPA routes need dual registration (`web.xml` and `SpaRoutingFilter`).
- This system decides what it accepts and records. It can refuse a merge report from
  an agent the policy does not allow; it cannot stop a `git push`. The hard guarantee
  for merges comes from branch protection on the code host, configured to match. The
  deployment gate is different: the deploy script can ask this system and refuse.
- **Time-limited assumption:** early on the owner drives every agent session by hand.
  Phases 1–2 may assume attended sessions (no unattended polling, generous claim
  expiry). The model must not bake that in: claims, expiry and the agent registry
  exist from phase 2 so unattended agents need no rework.

## Approaches

1. **Adopt an external tool** (Vikunja, Kanboard, Plane, Beads, Backlog.md). Fastest
   to a board. Rejected: a second identity system and a second service, no shared
   rendering or cross-links with the wiki, and the agent model (queues, claims,
   autonomy, change tracking) would still have to be built on top.
2. **Tasks as wiki pages** in a private cluster, state in frontmatter. Cheapest to
   start and history is free. Rejected by the owner: it is a barnacle. Every status
   change is a page save, concurrent agents collide like editors, activity has to be
   derived from version diffs, and isolation depends on every page-listing surface
   remembering to filter.
3. **First-class tables in a new module, wiki as a service provider.** Chosen. Work
   items are rows; isolation is structural because items are never in the page store;
   events are recorded at the moment of change; the wiki supplies identity, rendering,
   mentions and plumbing.

## The model

### Projects, products and items

- **Project** — a body of work with an audience and a board. Decides who can see an
  item. Has a short key (`PLAT`) used in item keys (`PLAT-15`).
- **Product** — a thing that ships. Decides where work lands: repository, wiki
  cluster, deployments. Products are instance-wide, not owned by a project.
- **Item** — an epic, story or task. Belongs to exactly one project. A story or task
  targets at most one product (none for admin work). An epic has no product of its
  own; its products are derived from its children.
- **Hierarchy** — task → story → epic, and nothing deeper. A story may stand without
  an epic; a task may stand without a story.
- **Kind** — `software | docs | admin`. Changes only what "done" points at.

### Membership and visibility

`work_member` grants a principal (a login or a wiki group) a role on a project:

| Role | May |
|---|---|
| `viewer` | Read everything in the project |
| `member` | Create, edit, comment, move, assign, hand off |
| `lead` | Manage members, project settings, and autonomy settings |

Wiki Admins see and manage every project. For anyone else, a project they are not a
member of does not exist: reads return 404, never 403, and lists omit it. Visibility
is applied in one place in the repository layer, and every read goes through it.

Setup and administration (my calls):

- Only a wiki Admin creates or archives projects and products, and names a project's
  first lead. Leads manage their own project from there.
- Any user who is a member of at least one project can list products (key and name),
  since items need a product to target.
- Group membership is resolved on each request, so a membership change takes effect
  at once.
- Nothing is hard-deleted. Items are cancelled, projects and products are archived.
  A comment's author may edit it or remove it; a removed comment leaves a marker, and
  both write an event. `work_event` is strictly append-only.
- Changes to fields other than title and body (status, priority, due date, assignee,
  parent, product) are recorded in `work_event` `from_value`/`to_value` only.

### Lifecycle

```
draft ─▶ refining ─▶ ready ─▶ in_progress ─▶ review ─▶ done
                                                        (cancelled from any state)
```

- Stories and tasks use the full list. Epics use `draft | in_progress | done |
  cancelled`; an epic's progress is derived from its children.
- Statuses are a fixed list in code with a `CHECK` constraint.
- Only `ready` and `review` work is claimable (the owner approved both; a claim of a `review` item leaves the status at `review`, a claim of a `ready` item moves it to `in_progress`). An item with an open `blocked_by` link is not.
- `review` is assignable work in its own right, to a person or a queue.
- A handoff is one event: new status, new assignee, and a required note saying what
  was done and what is needed next.
- Transitions are otherwise unrestricted for people: a member may move an item to any
  status, including backwards and out of `done` or `cancelled`; each move is an event.
  (My call.) The gates in the autonomy table restrict agents. A human member is never
  locked out of a gate, so phase 3 adds no restriction on what people could do in
  phase 1.
- The policy class exists from phase 1 and every state-changing operation asks it.
  In phase 1 it answers from role alone.

### History and activity

- `work_event` is append-only and written in the same transaction as the change it
  records. Every change produces one: created, edited, status, assigned, claimed,
  released, handed off, commented, linked, due date, membership, settings.
- `work_item_revision` holds every version of an item's title and body.
- The activity feed, the per-item timeline and handoff notes are reads of
  `work_event`.

### Assignment, agents and claims (phase 2)

> **Superseded in part by phase 3a** ([agent identity and authority](2026-11-16-work-agent-identity-design.md)):
> an agent is no longer bound to an API key. It has a sponsor, whose current standing caps what it may read and do,
> and its own credential. The identity bullets below describe phase 2 as it shipped in 2.5.23.

- An assignee is a login or a **queue** (`implementer`, `reviewer`, `writer`, …).
  People are assigned directly; agent work goes to a queue.
- `work_agent` registers each agent: name (`codex@docker2`), vendor, runtime, the API
  key it uses, and the queues it serves. Every event and claim records the agent.
- A claim is one atomic statement: take the item only if it is `ready` or `review` and unclaimed.
  The claim names the agent and expires. An expired claim returns the item to its
  queue with any open change still attached, so the next agent resumes the branch.
- Agent-to-agent handoff is a move to another queue. Agent-to-human handoff assigns a
  login and shows in that person's "waiting on you" lane.
- Agents authenticate with an API key carrying a new `work` scope. The MCP surface is
  its own endpoint, `/work-mcp`, so an agent does not need admin MCP rights.
- Identity (my calls): an agent is a `work_agent` row bound to exactly one API key.
  A request is an agent's if its key is bound to a `work_agent`; any other request,
  including one made with an unbound `work`-scope key, acts as the key's or session's
  human owner. `actor_kind` is derived this way and never supplied by the caller.
- One key is one agent. Sessions sharing a key are the same agent, so "a different
  agent" means a different `work_agent`. Each vendor or runtime gets its own key.
- An agent is admitted to projects individually (`work_member` with
  `principal_kind = agent`). It does not inherit the access of the person who owns
  its key. An agent's role is `viewer` or `member`, never `lead`.
- Claiming an item returns the item and its product's `wiki_cluster` name, so the
  agent can ask the knowledge MCP for a briefing itself. Until then `wiki_cluster`
  is a stored label and nothing in this module reads wiki cluster services.
- Decisions made during the build (owner-approved or reported to the owner):
  - Agents create items as `draft`; only a person moves a draft forward (or cancels it).
    An unclaimed item in a queue is never `in_progress`: work in a queue is `ready` or
    `review` until an agent claims it.
  - Every ending of a claim is recorded as its own event with its reason: release,
    hand-off, a move or reassignment by someone else, loss of access, retirement of the
    agent, archiving of the project, and expiry.
  - Agent keys are managed only through the agent registry (rotate or retire the agent);
    the generic key endpoints refuse them, and rotation may name a new owner.

### Changes (phase 3)

A change is a branch of work against one product, attached to one item.

- States: `open | in_review | merged | abandoned`.
- Identified by repository, branch and commit, so it works on any code host or none.
  A review URL is optional.
- An item may have several changes. Work done directly on the default branch is a
  change opened and merged in one step.
- The item key goes in the branch name (`work/PLAT-15-spa-route`).
- Agents report change state through MCP or REST. A reconciling sync from the code
  host may come later through the connector framework and is not a dependency.
- An item cannot be `done` while it has a change that is `open` or `in_review`.

### Autonomy (phase 3)

One setting with three levels, defined by who holds each gate:

| Level | Approves as ready | Reviews | Merges | Closes |
|---|---|---|---|---|
| `supervised` | Human | Agent or human | Human | Human |
| `delegated` (default) | Human | A different agent, or a human | The reviewer | Agent |
| `autonomous` | Agent | A different agent | The reviewer | Agent, then a human acknowledges |

- An agent never merges its own change, at any level. Not configurable. The rule
  binds agents only: a person may merge their own change.
- An agent may commit straight to a product's default branch only where a lead has
  allowed it (`allow_direct`), set on the epic and inherited like autonomy, or set on
  a single item.
- Both of those are **common cases and must be well supported**, not tolerated
  exceptions: a person merging their own work, and lead-allowed direct commits, are
  each one operation (open and merge in a single call, from the item view and from
  MCP/REST), are set once on an epic and not per item, and show on the board and
  timeline as ordinary merged changes with how they were merged.
- Set on the epic; it applies to everything under it. A story may be stricter freely;
  making it looser takes a project lead.
- A project has a default for items with no epic.
- A product has a ceiling that always wins.
- Only a human lead can set `autonomous`. An agent can never grant it.
- The effective level is resolved in one policy class that answers "may this actor
  take this action on this item", and every gate asks it.

### Deployments and the autonomous path (phase 4)

- A deployment is a product version going to production, carrying a set of items.
  States: `requested | approved | running | verified | failed | rolled_back`.
- `deploy_gate` on the product is `human` or `agent`. With `human`, an agent can only
  request; the request waits in the owner's lane. The gate holds inside an autonomous
  epic: autonomy covers the path to merged, the product decides who releases.
- `verified` requires attached evidence (health and smoke-test output).
- `done_means` on the epic is `merged` (default) or `in_production`. Ordinary items
  close at merged and later receive a "shipped in" marker from the deployment that
  carried them. Items under an `in_production` epic cannot close until a deployment
  containing them is verified.
- The deploy script checks that its deployment is approved and refuses otherwise.
- The explicit autonomous path: a lead creates a standing epic (for example
  `OPS-1 Production incidents`) with `autonomy: autonomous` and
  `done_means: in_production`. Agents may create, approve, claim and merge under it.
  Each item closed with no human in the path is flagged and sits in a "done without
  you" lane until a human acknowledges it. A jakemon alert reaching the on-call
  automation is the intended trigger.

## Schema

Each phase ships its own migration. Phase 1 tables are shaped so later phases only
add tables and columns and never rework existing ones.

```
-- phase 1
work_project        id, key, name, description, next_seq, default_autonomy,
                    created_at, archived_at
work_member         project_id, principal, principal_kind (user|group|agent), role
work_product        id, key, name, repo_url, default_branch, wiki_cluster,
                    autonomy_ceiling, deploy_gate, archived_at
work_item           id, project_id, seq, level (epic|story|task), parent_id,
                    product_id, kind, title, body, status, priority, rank,
                    due_on, assignee, reporter, autonomy, done_means, allow_direct,
                    version, created_at, updated_at, closed_at
work_item_revision  item_id, version, title, body, edited_by, edited_at
work_event          id, project_id, item_id (nullable), at, actor, actor_kind,
                    agent_id (nullable), type, from_value, to_value, note
work_comment        id, item_id, author, body, created_at, edited_at, removed_at
work_mention        id, item_id, comment_id (nullable), mentioned, created_at
work_link           id, item_id, kind (page|issue|url|blocked_by|commit), target

-- phase 2
work_queue          key, name, description
work_agent          id, name, vendor, runtime, api_key_id, created_at, retired_at
work_agent_queue    agent_id, queue_key
work_item           + claimed_by_agent, claimed_at, claim_expires_at

-- phase 3
work_change         id, item_id, product_id, branch, base_branch, head_sha,
                    review_url, state, merged_sha, opened_by, opened_at, closed_at

-- phase 4
work_deployment       id, product_id, environment, version, state, requested_by,
                      approved_by, started_at, verified_at, evidence
work_deployment_item  deployment_id, item_id
work_item             + ack_required, acknowledged_by, acknowledged_at
```

The policy columns (`default_autonomy`, `autonomy_ceiling`, `deploy_gate`,
`autonomy`, `done_means`, `allow_direct`) exist from phase 1 with their defaults, and are inert until
the phase that reads them (the autonomy level, its ceiling and `allow_direct` since phase 3b; `done_means` and
`deploy_gate` since phase 4). All are live now.

Item keys come from `work_project.next_seq`, incremented in the transaction that
creates the item. `work_item.version` is an optimistic lock: an update names the
version it read and fails if the row has moved on.

## Where it lives

- **`wikantik-work`** — new module shaped like `wikantik-insights`: the domain, the
  policy and the JDBC store. Depends on neither `wikantik-api` nor `wikantik-main`.
  Identity, group membership and rendering arrive through small ports.
- **`wikantik-rest`** — `/api/work/*` resources.
- **`wikantik-work-mcp`** (phase 2) — the `/work-mcp` endpoint, on `wikantik-mcp-core`.
- **`wikantik-frontend`** — the `/work` views.
- **Config** — `wikantik.work.enabled`, default `false`; the feature ships dark and is
  switched on per deployment. When off (my calls): migrations still apply, the module
  is in the WAR but its services are not started, `/api/work/*` and `/work-mcp`
  return 404, and the SPA routes stay registered while the navigation entry is
  hidden and the view says the feature is not enabled.

## What the wiki supplies

| Service | How it is used |
|---|---|
| Identity | Logins, display names (`PublicIdentity`), groups, SSO, API keys and scopes |
| Rendering | Item bodies and comments render through the Markdown pipeline: wikilinks, Mermaid, PlantUML, math |
| Mentions | The existing mention picker component only. Work mentions are stored in `work_mention`, the picker offers project members only, and they surface in the "waiting on you" lane, never on the wiki's mentions page |
| Diff | The existing diff viewer over `work_item_revision` |
| Plumbing | `wikantik-jdbc`, migrations, `PostgresTestDb`, MCP substrate, SPA shell and shared UI components |
| Observability | Instance-wide counts by status, level and kind, and claim and gate outcomes, on `/metrics`. No project, product or item labels: a project's existence is confidential |

Comments are a separate table from wiki comment threads, which are anchored to a
passage of a page.

Rendering rules that keep the two sides apart (my calls):

- Rendering an item body or comment is display only. It registers nothing with the
  reference manager, creates no Page Graph edges or backlinks, and its output is
  never stored in the shared render caches.
- A wikilink or `page` link shows the page name as the author typed it. It does no
  lookup of the target's title, summary or existence; the page's own access rules
  apply when the link is followed.
- `kind` is a label and a filter. Nothing enforces what a `docs` or `admin` item must
  link to when it closes.

## What people see

```
 Work ▸ PLAT Platform        [Board] [Dates] [Activity]    product ▾  assignee ▾
┌ Waiting on you (2) ─────────────────────────────────────────────────────────┐
│ PLAT-15 review requested by codex@docker2 · PLAT-9 deployment needs approval │
└──────────────────────────────────────────────────────────────────────────────┘
┌─ Refining (2) ───┐┌─ Ready (3) ──────┐┌─ In progress (2) ─┐┌─ Review (1) ─┐┌ Done ┐
│ PLAT-21 story    ││ PLAT-15 task     ││ PLAT-13 story     ││ PLAT-14      ││  …   │
│ wikantik         ││ wikantik · P1    ││ claude@ws · 2d    ││ jakemon      ││      │
│ 💬 3             ││ due Nov 22       ││ ⎇ 1 open change   ││ ⎇ in review  ││      │
└──────────────────┘└──────────────────┘└───────────────────┘└──────────────┘└──────┘
```

- **Board** — columns by status, cards grouped under their epic on request, drag to
  move. A move that policy refuses says why.
- **Dates** — items by due date, overdue flagged.
- **Activity** — the project's event feed, filterable by item, actor and type.
- **Item view** — rendered body, the fields, comments, links, changes, and the
  timeline; edit and diff for the story text.
- **Epic view** — its stories and tasks with progress, derived products, and its
  autonomy and done-means settings.

## Phases

Each phase is usable when it lands. This document covers the whole model; each phase
gets its own plan.

1. **Core.** Projects, members, products, items, revisions, events, comments, links;
   visibility; REST; the board, dates, activity, item and epic views. Human-to-human
   work is complete.
2. **Agents.** Queues, agent registry, claims with expiry, the `work` scope, the
   `/work-mcp` endpoint and tools. All four handoff directions work.
3. **Changes and autonomy.** Change records, the review and merge gates, the three
   levels and their inheritance. Built in two parts: 3a, agent identity
   (`2026-11-16-work-agent-identity-design.md`), and 3b, changes and the gates
   (`docs/superpowers/plans/2026-11-16-work-management-phase3b-changes-autonomy.md`, whose
   "Decisions" section records what was settled where this document is silent; it also
   moved acknowledgement and the "done without you" lane forward from phase 4).
4. **Deployments and incidents.** Deployment records, the product gate, the
   deploy-script check, the "done without you" lane, the jakemon trigger. Built from
   `docs/superpowers/plans/2026-11-16-work-management-phase4-deployments.md` (its "Decisions"
   section records what was settled where this document is silent). The jakemon trigger
   is documented, not built: the on-call automation that would receive an alert does not
   exist yet.

## Testing

- Repository tests against `PostgresTestDb`; rollback tests with
  `FaultInjectingDataSource` for every multi-statement write (item create, edit plus
  revision plus event, claim, handoff).
- A visibility suite proving a non-member gets 404 or an empty list from every read
  path, and an architecture test that no read bypasses the visibility-scoped store.
- A leak suite proving work items never appear in search, bundles, the sitemap, the
  change feed or the Page Graph.
- Concurrency tests for claims and optimistic locking.
- A policy table test covering every level × actor × action combination.
- REST and browser integration tests in `wikantik-it-test-rest`.

## Left to the builder

- Column types, indexes, and how `rank` orders cards.
- The exact REST resource shapes and MCP tool names.
- Search within work (a Postgres query scoped to visible projects is enough).
- Default claim expiry and its configuration key.
- Whether the frontend views share one route with tabs or three routes.
- A page-side panel showing the items that link to a page; desirable, not required
  in phase 1, and it must respect item visibility if built.
