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
they open `/work`; a person you did not add does not.

## 4. Items and the board

Items come in three levels: an *epic* holds *stories*, and a story holds *tasks*. Every item has a status, shown
as a board column: Draft, Refining, Ready, In progress, Review, Done (and Cancelled, off the board).

1. Choose **New item**, pick the kind and level, a title, and optionally a parent, product, priority, assignee and
   due date, then **Create item**.
2. Drag a card between columns to change its status, or up and down a column to change its order.
3. Open a card to see its page: the description, comments (mention someone with `@` and their name), links to
   other items (`blocked by`), its history, and its policy fields.

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

1. Open **Deployments** from the Work home and choose **Request a deployment**: the product, the version, the
   environment, and the item keys it ships (separated by commas or spaces), then **Request deployment**.
2. Someone allowed to approve it (by default a wiki administrator, or a lead of every project it carries) chooses
   approve; it then goes to running and finally verified (with the health and smoke-test output as evidence) or
   failed. Before it runs it can be withdrawn by whoever asked, or rejected by an approver.

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
