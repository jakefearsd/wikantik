# Work Management Phase 3a — Agent Identity and Authority — Design

Status: draft for owner approval, 2026-11-16
Parent design: [2026-11-15-work-management-design.md](2026-11-15-work-management-design.md) (phases 1 and 2 are live in 2.5.23)

## Intent

Phase 2 gave agents an identity by binding an ordinary personal API key to a `work_agent` row. Nearly every
serious defect found in phase 2 came from that seam: a key sent with a login cookie became its owner's session; a
person could rotate their agent's key into an ordinary one that acted as themselves; the guard against that failed
open when the feature was off; deleting the key's owner left an agent with no valid story.

The owner's question for phase 3 was "what authority can be assigned to an agent based on who is creating it, and
how that links up over time". This design answers it and rebuilds agent credentials on the answer.

Phase 3 is split. This document is **3a: agent identity**. **3b: changes and autonomy** (change records and the
review, merge and close gates of the parent design) follows and asks the policy check built here.

## What the owner decided (2026-11-16)

- A project lead can set up an agent, not only an instance admin.
- The person who set an agent up is its **sponsor**. If the sponsor's permissions are later reduced or increased,
  the agent's follow.
- The sponsor is a **ceiling, not a source**: the agent has only what it was explicitly given and can never hold
  more than its sponsor holds at that moment. An increase in the sponsor's standing raises the ceiling; it never
  spreads the agent into places nobody put it.
- Two agents sponsored by the same person count as independent reviewers of each other. This is not a stopgap:
  **a team of one is a first-class case.**
- Agents get their own credential store. A credential identifies one agent and carries no person's access.
- Finer control over what an agent may see or touch inside a project (the "testing agent that cannot see some
  tickets" case) is plausible but unproven. It is not built now and must not be locked out.

## Goals

- One rule decides what an agent may do, evaluated live on every request.
- A lead can register, configure, rotate and retire agents for their own projects without an admin.
- Any past action by an agent can be tied to the person who stood behind it at that time.
- The phase 2 guarantees hold unchanged: a non-member learns nothing (404, never 403); an agent never gains
  access from a person; every ending of a claim is recorded with its cause.
- The machinery phase 2 needed to defend the shared key system is deleted, not extended.

## Non-goals

- Per-item or per-kind visibility restrictions for an agent inside a project.
- A rule that a reviewer must have a different sponsor.
- Short-lived exchanged tokens. (Possible later on top of this store.)
- Change records and the autonomy gates (3b). The policy columns stay inert in 3a.
- Agents that create or sponsor other agents. A sponsor is always a person.

## Anti-goals

- An agent that can do anything its sponsor cannot do right now.
- A stored copy of the sponsor's access that has to be kept in sync. Nothing is copied; it is always looked up.
- Any agent credential appearing in, or being changeable from, the wiki's API-key pages.
- A lookup failure being read as "no access" in a way that tells an operator to do the wrong thing, or as
  "access" at all. A failed lookup is a 503 and changes nothing.

## Approaches considered for credentials

1. **Agents get their own credential table (chosen by the owner).** The work module verifies agent tokens itself;
   the wiki's key system never sees them. Removes the seam. Costs: the module supplies its own per-credential
   rate limit and last-used tracking.
2. Keep API keys with a system owner. Less code, but agent keys stay on the key pages and every phase 2 guard
   stays.
3. Short-lived tokens exchanged from a long-lived secret. Strongest against a leak, but every agent runtime must
   implement the exchange, against "any vendor or local model can connect".

## The model

### Sponsor

- `work_agent.sponsor` is a login. It is set to the person who registers the agent.
- Registering an agent requires being a lead of at least one unarchived project, or an instance admin.
- Sponsorship changes in two ways (my calls):
  - **Takeover.** The person taking over performs the action themselves; that is the explicit acceptance, and
    nobody can make another person a sponsor. Allowed for an instance admin at any time. Allowed for a lead of
    any project the agent is a member of only while the agent has no sponsor (null, or the account is gone).
  - **Release.** A sponsor may give up an agent, which leaves it without a sponsor (suspended) and so open to
    takeover. This is how a sponsor hands an agent to a colleague, with no offer-and-accept state to store.
  - A sponsor who still has an account but has lost their standing is not displaced by a lead. The agent simply
    has no access there; the lead registers their own agent, or an admin takes this one over.
  - Taking over an agent that is a member of several projects gives the new sponsor nothing beyond their own
    reach: in projects where they have no standing the agent has no access.
- Every sponsor an agent has had is kept in an append-only `work_agent_sponsor_history` (agent, sponsor, from,
  until, changed by). An agent's event at time T is tied to its sponsor at T by this table; event rows are not
  widened.
- An agent whose `sponsor` is null, or whose sponsor's account no longer exists, is **suspended**: it is no actor
  anywhere until someone takes it over. Agents registered under phase 2 start with a null sponsor (migrations
  are DDL-only; production has no agents).

### The rule

An agent may take an action on something only if all three hold when it asks:

1. **Its own grant.** The agent is a member of the project with a role that allows the action, and for a claim,
   serves the item's queue. (As phase 2.)
2. **Its sponsor's standing.** The sponsor, acting as themselves right now, would be allowed the same action on
   the same thing. The sponsor's standing is their effective project role (direct or through a group). A sponsor
   who is an instance admin may do everything in every project, as admins can today, so an admin-sponsored agent
   is limited only by its own grants and the item's policy. It still needs its own member row in each project.
3. **The item's policy.** In 3a this is the phase 2 rules (an agent may not move a draft forward, and so on).
   3b adds the autonomy level here.

Equivalently, the agent's effective role in a project is the lower of its own role and its sponsor's, where an
agent's own role is still at most `member`.

The rule covers **reading as well as acting**. Everything an agent can list, open or search is limited the same
way: an agent sees a project only if it has a member row there and its sponsor can see that project. In the store
this is the agent's member-row condition joined with the visibility condition a person already gets, evaluated
for the sponsor (login, groups, admin). The sponsor's groups and admin status are resolved once per request,
before the query, through the port. No query calls out.

Claiming needs `member`. An agent whose sponsor is a viewer is a viewer: it can read, and it cannot claim, write
or comment.

| Event | Effect on the agent |
|---|---|
| Sponsor demoted from lead to member | Works on as a member. That sponsor can no longer add it to the project or change its queues there; a lead does that. |
| Sponsor demoted to viewer | Read-only in that project. Its claims there are released. |
| Sponsor removed from the project | No access to that project (404). Its member row stays, with no effect. |
| Sponsor's standing restored | The agent's own grants take effect again. Nothing is re-entered. |
| Sponsor's account deleted | Suspended everywhere until taken over. |
| Another lead adds it to their project | Allowed as today. It has access there only while its sponsor does. |
| Takeover | Grants beyond the new sponsor's standing stop having effect; they are not deleted. |

Suspension and reduced access are **computed, never stored**. There is nothing to reconcile.

### Claims when standing changes

- A change the work module makes itself (a member row removed or demoted, an agent retired, a takeover) releases
  the affected claims in the same transaction, as phase 2 does for the agent's own member row. A person's member
  row now also releases the claims of the agents they sponsor in that project. Reason recorded:
  `sponsor access removed` (a new reserved release note).
- A change the work module is not told about (group membership, the admin role, an account deletion) is caught
  by the claim sweeper: each sweep also ends claims whose holder would no longer be allowed to hold them, with
  the same reason. Until the sweep (60 s), the agent's own calls are already refused.
- The sweeper resolves each distinct sponsor once per sweep. If a lookup fails, it ends none of that sponsor's
  agents' claims in that sweep and logs one WARN per outage; a lookup failure is never read as lost standing.

### Who may manage an agent

| Action | Who |
|---|---|
| Register | A lead of any project, or an admin. The registrant becomes sponsor. |
| Add to or remove from a project, set its role there | A lead of that project (as phase 2). |
| Set which queues it serves, rename, edit vendor/runtime | Its sponsor while they are a lead somewhere, or an admin. |
| Rotate its credential, retire it | Its sponsor (always: these only reduce risk), or an admin. |
| Take over | See Sponsor. |
| Create, rename, retire queues | Admin only (queues are instance-wide; unchanged). |

A non-admin sees an agent in the registry only if they sponsor it or it is a member of a project they can see.
Agent names stay unique across the instance.

### Credentials

- New table `work_agent_credential`: id, agent, token hash, created at, created by, last used at, revoked at.
  One live credential per agent. Rotation issues a new one and revokes the old in one transaction; the token is
  shown once.
- Tokens carry a fixed prefix that no personal API key has, so they are recognisable in logs and scanners and a
  filter can tell the two apart without a database read.
- The work module verifies them through its own store. `ApiKeyService` is not involved and has no agent rows.
- Verification lives in `wikantik-work` (a credential store and one service method that turns a token into an
  agent actor or nothing). The servlet filters stay where they are, in `wikantik-rest` and `wikantik-work-mcp`,
  and call it; they tell an agent token from a personal key by its prefix.
- A valid token may be cached briefly, but retiring an agent, rotating its credential, or releasing or taking
  over its sponsorship takes effect on the very next request. `last used` is written at most about once a minute
  per credential, never on every request.
- An agent token is accepted on `/api/work/*` and `/work-mcp` and nowhere else. A request carrying one has no
  user principal and never becomes a session (unchanged).
- A personal API key with the `work` scope still acts as its owner, never as admin, on the same two surfaces
  (unchanged). It can no longer be, or become, an agent.
- Per-credential rate limiting applies on both surfaces (this closes the item deferred in phase 2).
- A store failure while verifying is a 503, never a 401, and is never cached as "unknown".

### What is deleted

A personal key that was an agent's under phase 2 becomes an ordinary personal key again once the history table
is dropped, and would act as its owner. Before upgrading a deployment that has phase 2 agents, retire them or
revoke their keys; the release notes say so. Production has none.

`work_agent.api_key_id`, `work_agent_key_history`, the agent branch of `actorForApiKey`, `AgentKeys` /
`WikiAgentKeys`, `AgentKeyGuard`, the agent badges and refusals on the two key pages, the "owner deleted" and
"work not running" refusals (rulings P23, P31, P32 of phase 2), and the user-deletion hook that looked for
agent keys. Dropped by a new migration; applied migrations are not edited.

### The policy check

`WorkPolicy` stays the one class that answers "may this actor take this action on this item". Its input becomes
a resolved **standing**: the actor, the actor's own role in the project, and for an agent the sponsor's role.
Every caller already goes through it. A later per-agent restriction (visibility or kind) is a new input to this
one class and a new column on the agent or its member row; nothing else would change.

## What people see

- **Agents page** (`/work/agents`): open to leads, not only admins. Each agent shows its sponsor, and a plain
  status when it is limited: "Suspended: no sponsor", "Suspended: its sponsor no longer has an account",
  "No access to PLAT: its sponsor is not a member". A "Take over" action where allowed.
- **Project settings, members:** an agent member whose sponsor lacks standing in the project is shown as having
  no effect, with the reason.
- **Assignment log:** a claim ended by a sponsor's change reads, for example, "Ana was removed from the project,
  ending codex@docker2's claim (now ready)".
- **Key pages:** agent rows and badges are gone.

## Constraints

- All rules of the parent design and of `wikantik-work/CLAUDE.md` hold: every caller goes through `WorkService`;
  database access only through `Jdbc`; the event log is append-only; the module depends on neither
  `wikantik-api` nor `wikantik-main`. The sponsor's groups and existence come through the existing
  `WorkDirectory` port, which gains one method, `isAdmin(login)`, answered by the host the same way it decides
  the admin flag of a signed-in person. `exists` is now asked for sponsors on agent requests as well as on
  behalf of an admin; its answer is never shown to the caller.
- Migrations are DDL-only. No data is carried over: existing agents become sponsorless and their old keys stop
  being agent credentials.
- Still behind `wikantik.work.enabled`.

## Left to the builder

- The token prefix, length and hash (follow what `ApiKeyService` does for high-entropy tokens).
- Where the per-credential rate limit lives and its default, following the MCP endpoint's existing limiter.
- Whether the sponsor's standing is resolved per request or cached for a few seconds. If cached, a change made
  through the work module must clear it at once.
- The exact REST shapes, following the phase 2 agent routes.

## Testing

- The rule as a table-driven unit test: every row of the event table above, for each action class.
- Visibility: an agent whose sponsor lost standing gets exactly what a stranger gets (404, same body).
- Concurrency: a sponsor's removal racing the agent's claim, both orders, using the phase 2 lock-order tests as
  the pattern (the sponsor's member row joins the lock order).
- The sweeper ending a claim after a change the module was not told about.
- A lookup outage on each port call: 503, nothing changed, nothing cached.
- Cargo ITs for a lead registering an agent end to end over REST and MCP, and for takeover.
