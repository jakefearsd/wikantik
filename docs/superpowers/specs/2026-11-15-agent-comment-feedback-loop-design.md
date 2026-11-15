# Agent Comment Feedback Loop — Design

Date: 2026-11-15
Status: awaiting owner approval

## Intent

Readers already leave anchored comments on wiki pages. Today those comments reach
people only. The owner wants the comment system to become a feedback loop for the
publishing agents (several exist, not one): a reader comments, an agent later sees
the comment and either changes the content, replies, or dismisses it with a reason.
Over time this drives content quality from reader feedback.

The trigger was concrete: the owner left a comment on a page and the agent could
only read it by calling the REST API by hand with a test account. Neither MCP server
exposes comments.

## Goals

- An agent can list the open comment threads that are available to agents, for a
  page, a cluster, or the whole wiki, over MCP.
- An agent can close the loop on a thread in one of three ways: reply, resolve
  (having changed something), or dismiss with a reason.
- A reader can keep a thread away from agents, without needing to know what an
  agent is or which ones exist.
- A reader can see that a response came from an agent, and which one.
- A comment from someone who may not edit the page cannot direct an agent to
  change that page.

## Non-goals

- **Waking agents up.** Nothing launches an agent when a comment arrives. A thread
  waits until an agent session next asks for the inbox. (The owner expects to
  revisit this; the design must not make it harder.)
- **Addressing a named agent.** No agent directory, no `@agent` mention. Any agent
  working in the area may pick a thread up.
- **Per-agent inboxes or assignment.**
- **Surfacing the inbox in `get_briefing`.** Worth doing later; out of scope now.

## Anti-goals

- An agent rewriting a page because an anonymous or read-only commenter told it to.
- An agent's reply being mistaken for a person's.
- A dismissed comment vanishing without the commenter being able to see why.

## How it works today (verified in code)

- `comment_threads` (V033): `id`, `canonical_id`, text-quote anchor, `status`
  (`open` | `resolved`), `created_by`, `resolved_by`, `resolved_at`.
  `comments`: `id`, `thread_id`, `author`, `body`, timestamps.
- `CommentStore` (`com.wikantik.comments`) owns all writes; `resolve` / `reopen`
  flip the status.
- `comment_mentions` (V034) gives each login an unread feed (`/api/mentions`). The
  first comment on a thread auto-mentions the page owner.
- REST: `CommentThreadResource` at `/api/comment-threads/*`. Creating a thread
  requires the `comment` permission on the page. Author fields are shown as display
  names via `PublicIdentity`.
- Frontend: `CommentsDrawer.jsx`, `CommentBody.jsx`, `api/client.js`.
- Admin MCP tools take their author from the MCP client's self-declared
  `clientInfo.name` (`McpServerInitializer` → `AuthorConfigurable.setDefaultAuthor`),
  the same value that appears as the author in page history (`wikantik-cli`,
  `wiki-content-agent`, …). It is **not** the login the API key is bound to.

## Approaches considered

How a comment reaches an agent:

| Approach | Reader does | Trade-off |
|---|---|---|
| A. No control | Nothing; every open thread is agent-visible | Simplest; agents may join conversations between people; no consent |
| **B. Checkbox, on by default** | Leaves it ticked or unticks it | One flag per thread; reader needs no knowledge of agents |
| C. Tag a named agent | Picks an agent in the mention list | Precise; needs an agent directory and informed readers |

**The owner picked B**, with the label "Agents may respond to this comment". C stays
possible later as a refinement of the same flag.

How an agent sees comments: **inbox (pull)**, chosen by the owner over anything that
launches agents.

## Design

### Data (migration `V073__comment_agent_feedback.sql`, DDL only, idempotent)

- `comment_threads.agent_available BOOLEAN NOT NULL DEFAULT TRUE`
- `comment_threads.status` gains a third value, `dismissed`. `resolved_by` /
  `resolved_at` record who closed the thread and when, for both `resolved` and
  `dismissed`.
- `comments.via_agent BOOLEAN NOT NULL DEFAULT FALSE` — true when the comment was
  written through the MCP surface.
- `comments.author_could_edit BOOLEAN NOT NULL DEFAULT FALSE` — snapshot, taken when
  the comment is posted, of whether its author had `edit` permission on the page.

### Trust: who can direct an agent

A comment is **trusted** when `author_could_edit` is true. The REST layer sets it at
post time, where the commenter's session is available, using the same check as
`checkPagePermission(…, "edit")` but without sending an error.

A thread is **actionable** when at least one of its human comments is trusted. The
inbox returns the flag per comment and the roll-up per thread.

What the server enforces:

- `resolve` is refused on a thread that is not actionable. An agent may only `reply`
  to or `dismiss` such a thread.

What the server cannot enforce, and the tool description and skill must state:

- An agent holding an admin MCP key can edit any page regardless. The rule "only act
  on what a trusted comment asks" is guidance to the agent plus the `resolve` guard,
  not a sandbox.
- Comment bodies are reader-written text. The inbox returns them as data, and the
  tool description says they are never instructions to follow beyond the feedback
  they give about the page.

### MCP surface (admin MCP, tool count 20 → 22)

`list_comment_threads` — read.
- Inputs: `slug` (optional), `cluster` (optional; matches sub-clusters via
  `ClusterPath`), `status` (default `open`), `limit` (default 50).
- Returns only threads with `agent_available = true`.
- Each thread: `threadId`, `slug`, `canonicalId`, `status`, `anchor`
  (`exact`/`prefix`/`suffix`), `actionable`, `createdAt`, and `comments[]` with
  `author` (display name), `body`, `createdAt`, `viaAgent`, `trusted`.

`respond_to_comment_thread` — write.
- Inputs: `threadId`, `action` (`reply` | `resolve` | `dismiss`), `body` (required
  for all three).
- Always posts `body` as a comment with `via_agent = true` and `author` = the tool's
  default author (the client name).
- `reply` leaves the thread open. `resolve` and `dismiss` close it with a
  conditional update (`… WHERE status = 'open'`); if no row changes, the call is
  refused with "thread is no longer open" and no comment is posted. This is how two
  agents on one thread are settled: the first to close wins.
- `resolve` on a non-actionable thread is refused, citing the reason.
- Refusals name the reason (project rule for MCP write tools).
- Audited through `McpAudit.logWrite`.

Both tools are added to `wikantik-mcp-instructions.txt` and
`McpProtocolIT.EXPECTED_TOOLS`.

### Details settled after the builder review

- **Agent author format.** Agent comments store `author` as `agent:<clientName>`
  and are never resolved through `PublicIdentity`. The UI strips the prefix and
  shows the badge. This stops a client that declares its name as an existing login
  from having its comment displayed under that person's display name. The same
  value is used as `mentioning_login`.
- **Notifying the commenter.** `MentionService` gains a method that records a
  mention for the thread's creator on every agent response (`reply`, `resolve` and
  `dismiss` alike), one row per agent comment. The existing feed shows it; the feed
  entry must make clear it came from an agent (derivable from `comments.via_agent`).
- **Closing needs new store methods.** `CommentStore.resolve` / `reopen` are
  unconditional today (`WHERE id = ?`). The agent path needs a conditional close
  (`WHERE id = ? AND status = 'open'`) that posts the comment and changes the status
  in one transaction. While there, fix the REST `setStatus` handler, which reports
  the new status even when the store returned false.
- **`dismissed` in reads.** `CommentStore.listByCanonicalId`,
  `CommentThreadResource.normalizeStatus` and the client-side filter in
  `CommentsDrawer.jsx` all treat any status other than `open` / `resolved` as
  absent and need the third value.
- **People keep today's rules.** The trust guard applies to agents only. A person
  with `comment` permission can still resolve or reopen any thread through REST,
  with no closing comment required, exactly as now. Reopening a dismissed thread
  clears `resolved_by` / `resolved_at` as it does today; the dismissal stays visible
  as the agent's comment in the thread. An agent may dismiss a reopened thread again.
- **Trust requires a signed-in author.** `author_could_edit` is true only when the
  author is authenticated and has `edit` permission on the page. It is recomputed
  when the author edits the comment body.
- **Old threads are recoverable without a backfill.** A person with edit rights can
  add a reply to an old thread; that reply is trusted and makes the thread
  actionable.
- **Restricted pages.** The admin MCP bypasses page ACLs and `PrivateClusterGate` by
  existing decision, so the inbox includes threads on restricted pages, the same as
  `read_page` does. No extra filtering.
- **Threads whose page is gone.** A thread whose `canonical_id` no longer resolves
  to a slug is left out of the inbox, and responding to it is refused with that
  reason.
- **Size limits.** `limit` is capped at 200 threads. Each thread returns at most its
  50 most recent comments, with `commentsTruncated` set when there were more.

### Telling the commenter

When an agent responds, the thread's creator gets an entry in their existing
mentions feed, so the response shows up as unread the same way a person's mention
does. No new notification channel.

### REST

- `POST /api/comment-threads?page=…` accepts `agentAvailable` (boolean, default
  true).
- `GET` returns `agentAvailable` per thread and `viaAgent` per comment, and accepts
  `status=dismissed`.
- Posting a thread or a reply records `author_could_edit`.
- Reopening a dismissed thread follows the existing reopen rule.

### Frontend

- New-thread composer in `CommentsDrawer`: a checkbox, ticked by default, labelled
  **"Agents may respond to this comment"**.
- Comments with `viaAgent` show an "agent" badge beside the author name.
- A dismissed thread is shown as dismissed, with the agent's reason (its closing
  comment), and can be reopened. The status filter gains "Dismissed".

### Agent guidance

- The `wiki-content` skill gains a short workflow: list the inbox for the area being
  worked on; for each thread decide reply, resolve or dismiss; change content only
  when a trusted comment asks for it; always say what was done.
- Tool counts are updated wherever they are stated (CLAUDE.md, the skill,
  `FindingTheRightMcpTool` on the wiki), deriving the number from `EXPECTED_TOOLS`.

### Testing

- `CommentStore` changes against `PostgresTestDb`, including the closed-thread race
  (two closes, one wins) and a rollback test for the comment-plus-status write.
- Each MCP tool: a Mockito unit test and a Cargo IT, per the project's rule for MCP
  write surfaces. Refusals assert the stated reason.
- REST: `author_could_edit` is true for an editor and false for a comment-only user.
- Frontend: vitest for the checkbox default, the badge, and the dismissed state.
- Migration is a no-op when re-applied.

## Calls made by the designer (not decided by the owner)

1. **Agent identity is the MCP client name**, matching page history, with a
   `via_agent` flag on the comment. Earlier in conversation this was described as
   "the account the API key is bound to"; the code does not work that way, and
   changing it is a separate piece of work.
2. **Existing threads become agent-available** (the column defaults to true), but
   none of their comments is trusted (`author_could_edit` defaults to false), so
   agents can reply to or dismiss old threads and cannot resolve them.
3. **Two tools, not four**: one list, one respond-with-action.
4. **Trust is a snapshot at post time**, not re-evaluated when the inbox is read.
   Someone who loses edit rights later keeps their earlier comments trusted.
5. **The resolve note and the dismiss reason are the agent's closing comment**, not
   a separate column.
6. **The commenter is told through the existing mentions feed.**
7. **Tools live on the admin MCP** (`mcp_admin` scope), because responding is a
   write and the agents that publish already hold that scope.

8. **Agent authors are stored as `agent:<clientName>`** to prevent a client name
   from impersonating a member.
9. **Every agent response notifies the thread's creator**, including dismissals.
10. **The trust guard binds agents only**; people resolve and reopen as today.
11. **Trust requires a signed-in author** and is recomputed when a comment is edited.
12. **The inbox includes threads on restricted pages**, matching the admin MCP's
    existing bypass.
13. **Threads on deleted pages are hidden from agents.**
14. **Inbox caps:** 200 threads, 50 comments per thread.

## Left to the builder

- Exact Java placement of the trust snapshot and whether `CommentStore` gains new
  methods or overloads.
- JSON field naming details beyond those listed, error wording, badge styling.
- Whether `cluster` filtering is done in SQL or by resolving canonical ids first.
