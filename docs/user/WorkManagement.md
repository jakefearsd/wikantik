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

### Changes that arrive from your code host

If an administrator has connected the product's code host (see the administrator guide), you do not have to record
anything by hand. Name the item key in the branch, the pull request title or the commit message, and the change
appears on the item by itself, marked **via GitHub**: opened when the branch is pushed or the pull request is opened,
in review when an approval arrives, merged when the pull request merges. Its history shows the code host as **GitHub**.

- **Who opened it.** If the commit's email address is the one on a wiki account's profile, the change names that
  person (by display name). Anyone else is shown by their code-host name with *(outside the wiki)* after it, and is
  never linked to a profile.
- **One pull request is one change.** Closing a pull request marks its change abandoned; reopening it brings the same
  change back, so the history stays on one row.
- **The code host never changes your item.** Its status, assignee, sprint and version stay exactly as they were. A
  merge shows on the change; closing the item is still yours (or your agent's, at the right autonomy level).
- **Merged outside policy.** A merge is *flagged* when nobody other than the author approved the merged commit
  (*unreviewed*), or when someone committed straight to the default branch where direct commits are not allowed
  (*direct*). Project leads see flagged items under **Merged outside policy** on the Work home and get a notice;
  other members do not. A lead chooses **Acknowledge** on the change to say it has been seen. Acknowledging does not
  rewrite what was found: the change keeps its flag and shows who acknowledged it, and the item leaves the lane.
- **A flag can clear by itself.** Code hosts can deliver an approval after the merge it belongs to. If an approval
  stamped before the merge for the merged commit arrives later, and no lead has acknowledged the flag yet, the change
  becomes *merged after review* and the flag is removed. A lead who already got a notice is not told it cleared. An
  acknowledged flag, or an approval stamped after the merge, never changes anything.

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

**Fixing a failed deployment by deploying again.** When a deployment fails, or is rolled back, the usual repair is a
new deployment with the fix. Choose the earlier one in **Remediates** when you request the new deployment (REST and
the work MCP: `remediates`, the earlier deployment's number). It must be a deployment of the same product and
environment that was verified, failed or rolled back. Naming it is what lets the delivery figures in section 11 know
the failure was repaired, and how long that took. Leave it empty for an ordinary release. You can only name a
deployment you can see; a number you cannot see is answered exactly like one that does not exist.

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

## 10. Attaching files

Add files to an item, or to a comment on it, from the item page; you can also paste or drop a file into the item text or a comment box. An image shows in the text where you pasted it, and any other file becomes a link in the text. The item's attachment list shows every file with its size, who added it and when.

- **Who:** members and leads attach files, and agents that are members of the project do too. A viewer can see and download them but not add or remove. You remove a file you added; a project lead or an administrator can remove any file.
- **Limits:** a file is at most 10 MB and an item holds at most 50 MB in all (your administrator may have set other limits; a too-large file is refused with a message). Removed files stop counting.
- **Images in text:** PNG, JPEG, GIF and WebP are shown in the text. Everything else, HTML and SVG included, downloads instead of opening in the browser.
- **Removing** a file deletes its content. The activity still records that it was attached and removed, with its name.
- Anyone who can see the item can open its files; nobody else can, not even with the link.

## 11. Flow and delivery figures

Each project has a **Flow** tab (`/work/<project>/flow`): how fast work moves through the project, and how well it
is delivered. Wiki administrators also get **Delivery figures** for a whole product from the Products page. Nothing
here is stored: every figure is worked out from the item, change and deployment records when you open the page, so it
is always the current truth and there is nothing to refresh. The figures are for talking about how the team works,
not for ranking people; with a handful of items in the window every one of them is one or two events and can swing
a lot.

**The window.** Both views show a window of dates, by default the last 90 days ending today; change **From** and
**To**, or choose a sprint to show its own dates. A window is at most 366 days. Weeks start on Monday at 00:00, in the
time zone the page names, which is the server's.

### Flow (per project)

- **Throughput.** How many items were finished in each week, and how many points they were worth. An item counts in the
  week of its *last* move to Done, and only while it is still Done: one that was closed and then reopened is not
  counted until it is closed again, and an item closed twice is counted once. Points add up the estimates; an item
  with no estimate adds 0 points. Epics are not counted. This does not tell you whether the work was the right work or
  how big the items were. It is not the sprint velocity of the Sprints view: velocity counts what each sprint
  completed when it closed, throughput counts items in the week they were finished and ignores sprint boundaries, so
  the two are not meant to match.
- **Cycle time.** For items finished in the window: the time from the first move to In progress until the last move to
  Done, shown as the median (half the items were quicker) and the 85th percentile (85 in 100 were quicker), in days
  and hours, with the number of items it is based on. Items that were never In progress are left out. It does not
  include time spent waiting before someone started.
- **Lead time.** The same finished items, measured from when the item was created until it was last closed. It
  includes waiting in the backlog, so it is always at least the cycle time of the same item and says more about the
  queue than about the work.
- **Work in progress.** Right now (not limited by the window): how many items are In progress and how many are in
  Review, and the oldest of each with its age since it was first started. Many old items is the usual sign that work
  is being started faster than it is finished. It lists at most 50 items per status.

The Flow tab can be narrowed by sprint, label, type and assignee. These filters use the items' *current* values, as the
board does, so an item that has since changed label or owner moves with the change.

### Delivery (DORA-style figures)

These follow the four well-known DORA delivery measures, plus rework and a look at how merges happened. They count only
**production** deployments; a deployment to another environment appears in no figure. They are computed from
the deployment, change and event records, and they are only as complete as those records: a team that deploys by hand
and never records it here will see zeros.

- **Deployment frequency.** For each week, how many production deployments were verified. A deployment that was
  verified and later rolled back still counts, because it did go out. Deployments that failed, were rejected or are
  still waiting are not counted. It does not say how big a deployment was.
- **Lead time for changes.** For each merged change that was shipped in the window: the time from its first commit until
  the first verified production deployment (of the same product) that carries its item and went live at or after the
  merge. A deployment that was verified and then rolled back ships nothing. Shown as the median and the 85th percentile
  with the number of changes. A change with no recorded first commit time starts at the moment the change was opened,
  and is counted in "changes with no commit time", so its figure is shorter than the real one. **A first commit more
  than 30 days before the change was opened is treated as 30 days before opening**: after a rebase or a cherry-pick
  the commit's date can be months or years old, and one such change would otherwise dominate the percentile. Such
  changes are counted separately ("had a first commit more than 30 days before they were opened"). One pull request is
  one change however often it was closed and reopened, so a reopened pull request is not counted twice. A change that
  never reaches a verified production deployment has no lead time and is left out. This differs from DORA's own
  wording (commit to *running in production*) only in that "running" here is the deployment record's verified state,
  and in the 30-day cap.
- **Change failure rate.** Of the production deployments that started in the window and have finished (verified,
  failed or rolled back), how many failed, shown as "N of M" and not as a percentage, so a small sample cannot look
  precise. A deployment counts as failed when it failed, when it was rolled back (even after it was verified), or when
  a later deployment that was not rejected names it in **Remediates**. A deployment still running or waiting is not in
  the figure yet. It does not say how bad a failure was.
- **Time to recover.** For each failed deployment, the time from when it went live (or, if it never ran, when it
  failed) until the earliest of: it was rolled back; a deployment that **Remediates** it was verified; or, only for a
  deployment that never went live, the next verified production deployment of the product. Shown as the median and the
  85th percentile with the count. A failure that has not ended yet is not in the median, because it has no duration;
  it is shown as "not yet recovered" instead, so a bad week cannot hide by being unfinished. **The fix may belong to a
  different project.** A fix-forward filed under another project of the same product ends the clock and is counted,
  and a member of this project sees only the duration, never that project's name, items or deployments. The same
  holds the other way round.
- **Rework rate.** Of the same finished deployments, how many were themselves a fix, that is, ran and named an earlier
  deployment in **Remediates**. A high figure means fixes are being shipped as new deployments rather than avoided.
- **How changes were merged.** For changes merged in the window: how many were reviewed, merged without a standing
  approval, or committed directly, and how many were flagged as outside policy (see section 5) and how many of those
  have not been acknowledged yet. This is a count for the *window*; the "Merged outside policy" lane on the Work home
  shows every flagged merge that is still waiting, however old. A direct commit that a pull request's merge replaced
  is not counted twice.

**Who sees what.** A member of a project sees the deployments that carry at least one item of that project, and the
changes of its items. A wiki administrator can open the product view, which covers every project. A product's own
flow of deployments therefore looks different from each project's: that is expected.

**Reading them honestly.** The medians and percentiles are *nearest rank* values taken from the actual items, so with
fewer than ten items they are single events. A window with nothing in it shows "0 of 0" and no figure, never a zero
duration. DORA's published definitions are about a whole service in production; here a "deployment" is a record
somebody made, and the figures say only what those records say.

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
