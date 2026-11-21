# Work Management for Small Teams — Design

Date: 2026-11-21
Status: approved by owner 2026-11-21
Parent: `docs/superpowers/specs/2026-11-15-work-management-design.md`

## Intent

Work management was built for one owner and a fleet of agents. This round makes it
usable by a technical team of up to about 50 people, so that such a team would pick
it over JIRA. It closes the eight gaps a team that size feels, and nothing else.

A second intent, the owner's: a manager of such a team must be able to understand the
inputs to flow. Sprints and estimates are therefore first-class, and delivery metrics
follow the DORA definitions so the numbers can be compared with anyone else's.

## Goals

1. **Labels and item type**: tell a bug from a feature; tag areas.
2. **Sprints and estimates**: plan a time box, see commitment against completion.
3. **Notifications**: watching, an inbox, email, and outgoing webhooks (Slack included).
4. **Find and saved filters**: one query across every project a person can see.
5. **Code-host integration**: pull requests and pushes become change records without
   anyone reporting them, carrying the real commit times.
6. **Flow and DORA metrics**: read from the records the module already keeps.
7. **Attachments**: screenshots and logs on items and comments.
8. **Intake**: people outside a project can file work into it and follow their own.

## Non-goals

- Calling a code host's API, holding a code-host credential, or polling one. The
  integration only receives what the host sends.
- Enforcing anything on the code host, syncing issues with it, or taking deployments
  from it. Deployments stay with the deploy script and its gate.
- Moving an item's status from code-host activity. A merged pull request does not
  close an item; closing has rules (acknowledgement, done-means) a webhook must not skip.
- Import from JIRA, GitHub Issues or CSV. Deferred by the owner.
- Custom fields, configurable workflows, permission and issue-type schemes, a query
  language, an automation rule engine, portfolio planning, time tracking, multi-tenancy.
  Unchanged from the parent design.
- Bulk edit, cross-project `blocked_by`, release/version planning, a mobile app.
- Notifying agents. Agents pull work; nothing here pushes to one.

## Anti-goals

Unchanged from the parent, and each one constrains this round:

- **Not JIRA.** Nothing here needs an administrator before a team can work. Labels
  exist once typed. A project has sprints once a lead creates one. Item types are a
  fixed list. The only instance-level switches are for things that leave the system:
  email and webhooks.
- **Not a leak.** Email, webhooks and attachments are three new ways for work to leave
  a project. Each is checked against the reader's standing at the moment of delivery,
  and email carries the least it can by default.
- **Not a way for agents to gain authority.** Agents do not plan sprints, manage
  webhooks or file intake.

## What this changes in the parent design

The parent lists sprints, estimates, velocity, burndown and email notifications as
non-goals. This design reverses those four. It also adds an inbound integration with
the code host, which the parent did not have; the parent's constraint that a change is
"identified by repository, branch and commit, so it works on any code host or none"
still holds, because the integration is one more reporter of changes and the manual
and agent paths are untouched. The parent's rule that sprints must be
addable "as a new table plus nullable columns" is what makes that cheap, and this
design keeps to it. Time tracking stays a non-goal.

## Constraints

Everything in the parent's Constraints section, plus:

- **No metrics store.** Every flow and DORA figure is computed at read time from
  `work_event`, `work_item`, `work_change`, `work_sprint` and `work_deployment*`. The
  Simple Agility plane rule allows this: the figures need the product's own tables and
  are shown to its users. A snapshot table, a counter table or a Prometheus series
  with a project or product label would break the rule and the module's own
  ("no project, product or item labels on metrics").
- `wikantik-work` still depends on neither `wikantik-api` nor `wikantik-main`. Mail,
  outbound HTTP and people's email addresses arrive through ports, implemented in
  `com.wikantik.work.runtime`.
- Outbound HTTP goes through the connectors' `EgressGuard`.
- Each part ships dark behind `wikantik.work.enabled` like the rest, and can be
  released on its own.

## Approaches

**A. Grow the one module, one facade per feature (chosen).** Each feature is a
`WorkService` facade with its own store, as claims, changes and deployments are. One
visibility resolver (`WorkStanding`) keeps covering everything. Cost: `wikantik-work`
keeps growing.

**B. A second module for the outward-facing parts.** Notifications and webhooks move
to `wikantik-work-notify`, which reads the event log through `WorkService`. Cleaner
boundary for the code that talks to the outside. Cost: a second place that must
apply standing correctly, for no present benefit. The dispatcher in A is already
written against ports, so this split can be made later without rework.

**C. Reuse wiki services** (page attachments for files, wiki watches for notifying).
Least new code. Rejected: it makes work depend on page-store behaviour, which the
parent names as an anti-goal, and page attachments are reachable by people who are
not project members.

The owner has said a functional partition is acceptable. A is partitioned by feature
inside the module, and the plan is partitioned into eight independently releasable
parts. B stays open.

## The design, by part

### 1. Labels and item type

- `work_item.type`: `feature`, `bug` or `chore`, default `feature`. A fixed list.
  It is separate from `kind` (software, docs, admin), which says what sort of work it
  is; a docs bug is `kind: docs, type: bug`. Epics are always `feature`.
- `work_item.labels TEXT[]`, default empty, GIN-indexed. A label is lower-case,
  `^[a-z0-9][a-z0-9._-]{0,39}$`, at most 10 per item. There is no label table: the
  labels of a project are the ones its items carry, so there is nothing to administer.
  Typing a new label creates it; the last item to drop a label retires it.
- A change to either writes one `field` event, as other fields do.
- Label colour in the UI is derived from the name. Nobody picks one.
- Board and Dates gain type and label filters. Cards show a bug marker and labels.
- Agents may set type and labels on items they may edit, over MCP and REST.

### 2. Sprints and estimates

- `work_sprint`: belongs to one project (visibility is per project, so a sprint
  spanning projects would have no single audience). Name, goal, start and end dates,
  state `planned`, `active` or `closed`. At most one active sprint per project.
- `work_item.sprint_id` and `work_item.estimate`, both nullable, on stories and
  tasks. An estimate is a unitless number of points, 0 to 999.5 in halves. An epic
  has no estimate of its own; it shows the sum of its children.
- **A project without sprints behaves exactly as today.** Nothing is configured. The
  sprint controls appear when a lead creates the first sprint.
- A lead creates, edits, starts and closes sprints. A member puts items in and takes
  them out, and estimates. An agent may estimate, and reads sprint data; it may not
  move an item between sprints or manage a sprint (planning is a person's decision).
- Starting a sprint records the commitment on the sprint row (item count and points
  at that moment). Closing it records what was completed, and moves each unfinished
  item to the sprint the lead names or back to the backlog, in one transaction.
  These four numbers are a record of what was promised, not a metrics cache: scope
  can change after the start, and the commitment must not move with it.
- Burndown is derived from the event log (status, estimate and sprint events) for the
  sprint's dates. Velocity is the completed points of the last six closed sprints.
- The board gains a sprint selector: all, the active sprint, the backlog, or a named
  sprint. It defaults to the active sprint when there is one. A new Sprints view
  under the project plans the backlog into sprints and shows a sprint's report.
- Claimable work is ordered with the active sprint's items first, then as today.

### 3. Notifications

- **Watching.** `work_watch(item, login, state)` with state `watching` or `muted`.
  A person starts watching an item when they report it, are assigned it, comment on
  it or are mentioned on it. Muting sticks: nothing re-subscribes a muted person
  except being assigned or mentioned again.
- **The event log is the outbox.** Nothing is added to the write path. A dispatcher
  (a background thread beside the claim sweeper) reads `work_event` past a stored
  cursor, works out who should hear about each event, and writes `work_notification`
  rows. A crash or restart loses nothing and repeats nothing: the cursor and the rows
  commit together.
- **Who hears what.** The assignee on assignment; anyone mentioned; watchers on status
  changes, comments, hand-offs, review requested, merged, shipped and rolled back; a
  project's leads when an agent closes work that needs acknowledging; the reporter
  when a claim on their item expires. Never the person who did it. Never an agent.
- **Standing is checked at dispatch and again at read.** A person who has lost
  access gets no row, and an old row for an item they can no longer see is not shown.
- **Inbox.** `/work/inbox` with an unread count in the work navigation. Opening an
  item marks its notifications read.
- **Email.** Off until `wikantik.work.notify.email.enabled` is true and mail is
  configured. Each person chooses `each`, `daily` or `off` (default `each`). `each`
  means one message per dispatch cycle (five minutes) listing everything new, grouped
  by item, so a busy item is one email and not twenty. `daily` is one message at a
  set hour.
- **What an email contains.** By default the item key and title, who did what, and a
  link. Comment and body text are included only when
  `wikantik.work.notify.email.detail = full`. Mail leaves through a relay the
  instance does not control, so the default sends the least that is still useful.
- **Outgoing webhooks.** A project lead adds a URL, a format (`generic` JSON signed
  with HMAC-SHA256, or `slack`), and optionally the event types to send. Delivery is
  in event order and at least once, from a per-webhook cursor. A webhook that fails
  for 24 hours is switched off and the leads are notified. Adding, changing and
  removing one is a project `settings` event and an audit record. The URL and secret
  are never returned after creation. Off until `wikantik.work.notify.webhooks.enabled`.

### 4. Find and saved filters

- One query, `find`, over every project the caller can see, with standing applied in
  the query itself as the assignment log does. Filters: projects, statuses, levels,
  type, labels (all of), priorities, assignee, queue, reporter, product, sprint
  (a sprint, `active` or `none`), watching, text, due before, updated since.
- `/work/find`: a filter bar and a table. The filter lives in the URL, so a link is
  already a shareable view.
- `work_saved_filter`: a named filter belonging to its owner. One scoped to a project
  can be shared with that project's members. A shared filter shares the question,
  never the answer: results are always computed for whoever opens it.
- Work home gains three built-in views: assigned to me, reported by me, watching.

### 5. Code-host integration

Decided with the owner on 2026-11-21: GitHub plus a generic format; pull requests and
direct pushes; people matched by email; rule breaks recorded and flagged.

- **One inbound endpoint per product**, `POST /api/work/hooks/{product}/{format}`,
  where the format is `github` or `generic`. A product already names its repository
  and default branch. Whoever may edit the product turns the hook on and is shown a
  secret once. Every delivery is verified with HMAC-SHA256 over the body
  (`X-Hub-Signature-256` for GitHub, `X-Wikantik-Signature` for generic). An unknown
  product, a product with no hook and a bad signature are the same 404. A good
  delivery is always answered 204, whatever it matched, so the endpoint says nothing
  about which items exist. Off until `wikantik.work.codehost.enabled` is true.
- **Receive only.** No request is ever made to the code host and no token for it is
  stored. Everything recorded comes from the delivery's own payload. (This is also
  what the Simple Agility plane rule requires: a product does not poll a third party.)
- **The generic format** is a small documented JSON body carrying the same facts
  (repository, branch, commits with sha, message, time and author, the review's URL,
  title and author, the reviewer, the merge commit). GitLab, Gitea, a CI job or a git
  hook can post it. The GitHub adapter translates into it, so there is one code path.
- **Which item.** An item key (`PLAT-15`) in a branch name or a pull request title ties
  that branch to the item. On the default branch, a key in a commit message ties that
  commit to the item. The item must exist and target this product; a key that does not
  is ignored without a trace. A branch or title naming two items makes a change on each.
- **What becomes what.**

| The host says | The change |
|---|---|
| a push to a branch naming an item | opened if there is none; head and first commit time updated |
| a pull request opened or reopened | opened, or the existing one adopted; its URL recorded |
| a pull request marked ready, or a review requested | in review |
| a review approving it | reviewed, for the commit approved |
| a new commit on it | head moves; a standing approval is cleared, as today |
| merged | merged, with the merge commit; `reviewed` when an approval by someone other than the author stands on the head, else `unreviewed` |
| closed without merging | abandoned |
| a commit pushed to the default branch naming an item, not part of a known pull request | a `direct` change, merged, with that commit's time |

- **A merge is recorded once.** A pull-request merge also arrives as a push to the
  default branch. The push is recognised as that pull request's (by its merge commit,
  its head commit or the request number in the message) and adds nothing. Every
  operation is safe to repeat, so a redelivered or out-of-order delivery changes
  nothing it has already recorded.
- **It reconciles with what agents and people report.** A change already opened on the
  same item and branch is adopted, not duplicated. The integration fills in what is
  missing (commit time, head, review URL, the merge) and never overwrites who opened,
  reviewed or merged it when that was reported directly.
- **Who did it.** The author's commit email is matched, ignoring case, to a wiki
  user's email; exactly one match is that person. Push deliveries also carry the
  author's code-host username beside the email, so the first match teaches the system
  that username, and later pull-request and review deliveries (which carry only the
  username) resolve to the same person. Nobody links an account by hand. With no
  match the change is still recorded, attributed to the code-host name and shown as
  an outside author; it is never tied to a wiki account by guesswork.
- **Events are the system's.** The item's change events are written with a new actor
  kind, `system`, named for the source ("via GitHub"). The matched person is recorded
  on the change as its opener, reviewer or merger; they are not shown as having
  acted inside the wiki.
- **Outside policy: recorded and flagged.** The merge has already happened, so it is
  always recorded as it was. It is flagged when it is an unreviewed merge, or a direct
  commit where the item's policy does not allow direct commits, **whoever made it**
  (the owner's decision, 2026-11-21). This is stricter than the module's rule for
  merges reported from inside, where a person is never refused: a webhook report is
  not a person's deliberate act in the wiki, so a lead looks at it. A flagged change
  sits in the project leads' waiting lane ("merged outside policy") until one
  acknowledges it, and with notifications built the leads are notified. A team that
  works on its default branch turns on "allow direct commits" for the epic or item
  and is not flagged for it.
- **`work_change.first_commit_at`** is added here: the earliest commit time seen for
  the change. It can also be given by anyone reporting a change by hand or by MCP.
- The product page shows when the hook last received a delivery, so whoever set it
  up can see that it works. There is no delivery log table; deliveries are logged as
  structured lines and counted by kind and outcome, with no product label.

### 6. Flow and DORA metrics

Flow, per project, optionally for one sprint, label, type or assignee:

- throughput (items closed per week), cycle time (first `in_progress` to `done`,
  median and 85th percentile), lead time (created to `done`), work in progress by
  status with the age of each item, sprint velocity, commitment against completion,
  and burndown.

DORA, per project and filterable by product, over production deployments that
carried at least one of the project's items (so every member sees the same number,
and a non-member sees none). Wiki admins also get an instance-wide view per product.

| Key | Definition here |
|---|---|
| Deployment frequency | verified production deployments per week |
| Lead time for changes | first commit to the verified production deployment that shipped it |
| Change failure rate | deployments that failed, were rolled back or needed a remediating deployment, over those that ran |
| Failed deployment recovery time | from the bad deployment going live (or failing) to the rollback or the remediating deployment being verified |
| Deployment rework rate (the 2024 fifth key) | remediating deployments over all deployments |

Two inputs make these exact rather than approximate:

- `work_change.first_commit_at`, from part 5. Lead time uses it and falls back to the
  change's `opened_at` for a change nobody's hook reported; the report says how many
  changes used the fallback.
- `work_deployment.remediates_id`, nullable: "this deployment fixes that one". It is
  how a fix-forward counts as a failure of the earlier deployment. Without it only
  rollbacks would count, and a team that fixes forward would look perfect.

Because part 5 records how each change was merged, the report also shows the share of
merges that were reviewed, unreviewed and direct, and how many were flagged.

A deployment "shipped" a change by the rule the module already uses for done-means:
production, verified, at or after the change merged, same product.

`/work/:project/flow` shows these as charts, drawn as inline SVG (the frontend has
no chart library and this does not justify one).

### 7. Attachments

- `work_attachment` (metadata) and `work_attachment_blob` (the bytes, `BYTEA`), so
  no item query ever touches file content and backups cover it with no new volume.
  Limits: 10 MB per file, 50 MB per item, both configurable.
- An attachment belongs to an item, and optionally to one of its comments. It is as
  visible as its item: the download route resolves the item first, so an unknown
  id, another item's id and an unseen item are the same 404.
- Served with `nosniff`. PNG, JPEG, GIF and WebP, identified by their leading bytes
  and not by the uploader's claim, may be shown inline; everything else, SVG and HTML
  included, is a download.
- Pasting or dropping an image into the body or a comment uploads it and inserts
  `![name](work-attachment:<id>)`. The renderer rewrites that scheme to the download
  route. That is a rewrite, not a lookup, so the rule that work text never does page
  or attachment lookups holds.
- Removing an attachment keeps its row and its event and deletes the bytes.
- Agents upload and read over REST with their own token. The MCP item view lists
  attachment names and sizes.

### 8. Intake

- A fourth role, `reporter`, below `viewer`, given to a user or a group. Never to an
  agent.
- A reporter can file a story or task into the project (title, text, type,
  attachments) and can see, comment on and watch **only the items they reported**.
  They see no board, no other items, no parent, no links, changes or deployments.
- An intake item starts `draft` and unassigned, whoever the reporter is, so a member
  triages it. Members see a marker on it, and a notice that its reporter is outside
  the project and can read the discussion.
- `/work/submit` files one; work home lists "reported by me".
- This is the one part that changes who can see something, so it goes last and
  `WorkStanding` stays the only place that decides it.

## Decisions made here that are the owner's to overturn

| # | Decision | If wrong |
|---|---|---|
| D1 | Type is a fixed list of three; labels are free text with no table | A team wanting a label taxonomy adds a table later; the array migrates cleanly |
| D2 | Sprints belong to one project; one active at a time | Cross-project sprints would need a new audience rule |
| D3 | Estimates are points, not hours; no time tracking | Hours would be a second column |
| D4 | Agents estimate but do not plan sprints | One gate to loosen |
| D5 | The board defaults to the active sprint; claimable work prefers it | One default each |
| D6 | Email carries title and action only unless `detail = full` | A config change |
| D7 | Email is batched per five-minute cycle, not sent per event | A setting |
| D8 | Webhooks are per project, managed by leads, default off at the instance | — |
| D9 | DORA is computed per project over the deployments that carried its items | An admin-only per-product view is included either way |
| D10 | `remediates_id` is given by whoever requests the deployment | A deploy script can pass it |
| D14 | The hook belongs to a product and is receive-only: no code-host API calls, no stored token | Missed pushes mean a missing commit time, shown as a fallback count |
| D15 | Code-host activity never moves an item's status | "Merged moves it to review" is one rule to add later |
| D16 | Usernames are learned from the first email match; nobody links an account | A manual link is a small addition if matches prove too rare |
| D17 | Decided by the owner: every unreviewed or disallowed-direct merge reported by the code host is flagged, people's included. Merges reported from inside the wiki keep today's rule | Narrowing it to unattributed merges is one condition |
| D18 | Events from the hook are a new actor kind, `system` | — |
| D11 | Attachment bytes live in PostgreSQL | A filesystem store is a port away |
| D12 | A reporter reads the whole discussion on their own item; no internal comments | An internal-comment flag is one column and one filter |
| D13 | Order: labels, sprints, notifications, find, code host, metrics, attachments, intake | Parts 3 and 7 can move freely; 6 needs 2 and is better after 5; 4 is better after 1 and 2 |

## Left to the builder

Class names inside a facade, SQL shapes, the layout of the new views within the
existing work UI conventions, and chart styling.
