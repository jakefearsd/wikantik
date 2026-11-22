# Work Management

Work management is a small project tracker built into the wiki, at `/work`. It holds products and projects, a
board of epics, stories and tasks, the changes (branches) that do the work, and the deployments that ship it.
People and registered coding agents use it side by side: agents pull work from queues, and nothing they do is
hidden from the people who sponsor them.

This guide walks through first use in order. Each step ends with what you should see, so you can follow it as a
checklist the first time. Administrators: the operations side (configuration, metrics, troubleshooting) is in
[WikantikOperations.md](../admin/WikantikOperations.md), sections 1.14–1.16.

## 1. Turn it on (administrator)

Work management is off until an administrator sets `wikantik.work.enabled=true` in `wikantik-custom.properties`
and restarts the wiki.

**You should see:** a **Work** entry in the navigation, and `/work` opens the Work home. Only signed-in people see
it.

## 2. Create a product (administrator)

A *product* is something you build and ship: a repository with a default branch. Projects and their items target
a product, and deployments ship a product's versions.

1. Open **Products** from the Work home (or `/work/products`) and choose **New product**.
2. Give it a short key (lower case, for example `wiki`), a name, and optionally its repository URL, default
   branch and the wiki cluster that documents it.
3. Leave **Agents may do at most** and **Deployments are approved by** at their defaults for now (see *Autonomy*
   below).

**You should see:** the product in the list. When you edit it later, a **History** list under the form shows who
changed which setting, from what to what.

## 3. Create a project and add people

A *project* is a team's board. Only its members (and wiki administrators) know it exists; everyone else gets
"Not found".

1. On the Work home choose **New project**: a key (upper case, for example `PLAT`; it prefixes every item key,
   `PLAT-1`), a name and a description, then **Create project**.
2. Open the project's **Settings** tab and add members by display name, each as `viewer` (reads), `member` (reads,
   edits, comments, moves cards) or `lead` (also changes the project's policy and settings).

**You should see:** the project on the Work home and an empty board. A person you added sees it too the next time
they open `/work`; a person you did not add gets "Not found". Wiki administrators see every project without being
members, so the project you just created may say "This project has no members" until you add people.

## 4. Items and the board

Items come in three levels: an *epic* holds *stories*, and a story holds *tasks*. Every item has a status, shown
as a board column: Draft, Refining, Ready, In progress, Review, Done and Cancelled. A task or story you create starts
as **Ready**. An epic has no Ready status, so it starts as a **draft**, and so does anything an agent creates: a
person moves an agent's draft forward. The board hides Draft and Cancelled until you tick **Show draft and
cancelled**.

1. Choose **New item**, pick the level (task, story or epic), a title, and optionally a description, parent,
   product, assignee, kind, priority and due date, then **Create item**. A task or story you put in an agents'
   queue is available to its agents straight away.
2. Drag a card between columns (or use its **Move to…** menu) to change its status, or up and down a column to
   change its order. To see drafts, such as a new epic, tick **Show draft and cancelled**.
3. Open a card to see its page: the description, comments (mention someone with `@` and their name), links to
   other items (`blocked by`), its history, and its policy fields.

**Type and labels.** Every item has a type: *feature* (new capability, the default), *bug* (something broken; its
card shows a Bug marker) or *chore* (upkeep that changes nothing a user sees). An epic is always a feature. An item
can also carry up to 10 labels: type one in the form or on the item page to create it, and press Enter to add it.
Colours are automatic. A label drops out of the suggestions once no open or done item carries it. The Board and
Dates views can each be filtered by one type and one label.

**Sprints and estimates.** A story or task can be estimated in points (whole or half, 0 to 999.5): use Edit beside
Estimate on the item page. Members plan stories and tasks into a sprint from the **Sprints** tab, with the "Plan
into…" menu or by dragging; the tab appears once the project has a sprint, or for a lead who may make the first. A
project lead creates, starts and closes sprints. Only one sprint is active at a time. Starting records what was
committed; closing asks where unfinished work goes (the Backlog or a planned sprint) and records what was completed.
A closed sprint takes no more work. Click Report on the active sprint, or a closed sprint's name, for the burndown, what
was added and removed after the start, and the committed and completed totals; the Sprints tab shows velocity for the
last sprints. The Board filters by sprint and opens on the active one. Agents may estimate but never plan a sprint.

**You should see:** the card where you dropped it, still there after you reload the page. Every change is in the
item's history and the project's activity.

## 5. A change: from branch to merge

A *change* is one branch of work on an item, against the item's product. The wiki records what it is told; it
never talks to your code host, so keep using branch protection there.

1. On the item page, in the **Changes** panel, choose **Open a change**: the branch (put the item key in it, for
   example `work/PLAT-1-login-fix`), and optionally the newest commit and a review link.
2. When the work is ready, choose **Ask for review**. A reviewer (a person, or an agent that did not write it)
   chooses **Approve** or **Send back**. An approval is for the commit the change is at: a new commit clears it.
3. Merge on your code host, then choose **Record merge** and give the commit the merge produced. A squash or merge
   commit is fine: the merge counts as reviewed as long as nothing was pushed after the approval.
4. Work committed straight to the default branch: **Record a merged change** opens and merges in one step.

**You should see:** the change marked *merged after review* (or *merged without review*, or *merged directly*).
An item cannot be closed while one of its changes is still open or in review.

## 6. A deployment: from request to verified

A *deployment* is one product version going to an environment (production unless you name another), carrying the
items it ships.

1. Open **Deployments** from the Work home, choose the product at the top, then **Request a deployment**: the
   version, the environment, and the item keys it ships (separated by commas or spaces), then **Request
   deployment**.
2. Open the deployment. Someone allowed to approve it (by default a wiki administrator, or a lead of every project
   it carries) chooses **Approve**; then **Mark as running**, and finally **Mark as verified** (paste the health and
   smoke-test output as evidence) or **Mark as failed**. Before it runs it can be withdrawn by whoever asked, or
   rejected by an approver.

**You should see:** "Shipped in" on each carried item's page once the deployment is verified, and approvals waiting
for you listed on the Work home.

## 7. Agents (a project lead or administrator)

An agent is a registered identity, for example a coding agent on a build machine, that takes work from a queue.

1. An administrator creates a queue on **Agents & queues** (`/work/agents`).
2. A project lead or administrator chooses **Register agent**: a name such as `codex@docker2`, and the queues it
   serves. Whoever registers it is its *sponsor*: the agent can never do or see more than you can.
3. Copy the token (it starts with `wka_`) into the agent's environment straight away: it is shown once.
4. Add the agent to the project under **Settings**, as `viewer` or `member`.

**You should see:** the agent listed with you as sponsor. Items in its queue that are Ready show "Available to
agents" on the board; when it claims one, the card shows the agent and until when it holds it. Every claim,
hand-off and release is listed under **Assignments and claims**.

## 8. Notifications

The **Inbox** link on the Work home (`/work/inbox`) opens your inbox: a notice when someone mentions you, assigns you an item, hands one to you, or changes an item you watch. Only the new assignee hears of an assignment; watchers hear of hand-offs; a mention beats a watch; you never hear of your own action. Edits to plain fields, sprints, changes and deployments notify nobody yet. Notices appear within a minute. Nothing that happened before notifications were switched on is notified.

- **Watch or mute an item** from its page. You start watching items you create or comment on, and items assigned or handed to you or where you are mentioned; a mute you chose stays through creating or commenting, but an assignment, hand-off or mention turns watching back on.
- **Mark read** one notice at a time or all at once. You only ever see notices for projects you can still see: if you are removed from a project its notices disappear.
- **Email** (if your administrator turned it on): choose *each*, *daily* or *off* in the inbox preferences. Mail carries the title and the action; the full comment text only if the administrator allowed it.
- **Webhooks** (project leads, if enabled): add an https address under the project's **Settings** to receive a signed JSON or Slack message per event. The secret is shown once; the address is never shown again.
- Agents are never notified. An agent's name can no longer be one that belongs to a person.

## 9. Finding work and saved filters

**Find work** on the Work home (`/work/find`) searches the items of every project you can see. The filter bar narrows by project, status, level, type, labels, priority, assignee, reporter, queue, product, sprint, watching, text, due date and last update, and sorts the result. Choose **Load more** to see further rows.

- **The address is the view.** The filter lives in the page's link, so you can bookmark it or send the link to a colleague. They see the same filter, run for them.
- **`me`** in the assignee or reporter field means whoever opens the link. *Assigned to me*, *Reported by me* and *Watching* on the Work home open ready-made views.
- **Save a filter** under a name of your own. A saved filter is yours alone until you share it.
- **Share a filter with a project** when it names exactly one project in which you are a member or lead (a viewer cannot share): its members then see it in their list. A shared filter shows each person their own results, so it never reveals items they cannot see. You, a lead of that project or an administrator can delete it; only you can rename it.
- You only ever find items in projects you can see.

## Autonomy in one paragraph

Each item has an *autonomy level* that decides what an agent may do to it without a person: `supervised` (agents
work and review; a person approves, merges and closes), `delegated` (the default: agents may also merge what they
approved and close the item) or `autonomous` (agents may also approve work as ready). The level comes from the
item, else its story or epic, else the project's default, and the product's ceiling caps it. People are never
limited by it. Two rules hold at every level: an agent never reviews or merges a change it wrote, and an item
cannot close while a change is open.

## Done without you

When an agent closes an item at the `autonomous` level, the item waits under **Done without you** on the Work home
and the board, for the project's leads, until a member chooses **Acknowledge**. Nothing an agent finishes on its
own goes unseen.
