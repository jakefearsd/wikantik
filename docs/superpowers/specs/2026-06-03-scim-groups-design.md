# SCIM 2.0 Groups Design

**Status:** approved 2026-06-03
**Builds on:** [ScimProvisioningDesign](../../wikantik-pages/ScimProvisioningDesign.md) (Users, shipped) — same `wikantik-scim` module, bearer filter, and patterns.
**Scope:** the deferred Groups fast-follow — `/scim/v2/Groups` so an IdP (Okta/Entra) can sync group membership, which drives Wikantik authorization.

## Goal

Complete the IdP story: alongside SCIM Users (provisioning + decommission), let the
IdP create/update/delete groups and — the load-bearing case — **sync membership**.
Wikantik groups (`GroupPrincipal`) are referenced by page ACLs and policy grants, so
group membership is real authorization, not cosmetic.

## The hard invariant: SCIM never grants the Admin role

Wikantik has three distinct authorization stores: **groups** (`group_members` /
`GroupPrincipal`), the **role table** (`login_name → role`, e.g. `Admin`), and
**policy grants** (role → permissions). **SCIM Groups map only to Wikantik groups;
they never write to the `roles` table.** This is structurally guaranteed — the SCIM
group code only ever calls `GroupManager`, which touches `group_members`, never the
role store — and is locked in by an explicit unit test: a SCIM group named `Admin`
creates a *group* named Admin and grants no role. Reserved/restricted group names
remain rejected by the existing `validateGroup` / `checkGroupName`.

## Architecture

Reuses, unchanged, from the Users increment: the `wikantik-scim` module,
`ScimAccessFilter` (bearer `wikantik.scim.token`), `ScimError`, the `ListResponse`
envelope, and `ScimFilterParser`. New components:

- **`ScimGroupResource`** (HttpServlet) — `/scim/v2/Groups` and `/scim/v2/Groups/*`,
  registered in `web.xml` behind the existing `/scim/v2/*` filter.
- **`ScimGroupMapper`** — `Group ↔ SCIM Group JSON`.
- **`ScimGroupPatchApplier`** — interprets member `add` / `remove` / `replace`
  (kept separate from the Users `ScimPatchApplier` so each stays single-purpose).
- **`ScimDiscoveryResource`** — extended to advertise the Group resource-type and
  schema.

```
IdP ──Bearer──► ScimAccessFilter ──► ScimGroupResource
                                          │
                ┌─────────────────────────┼─────────────────────────┐
                ▼                         ▼                         ▼
         ScimGroupMapper         ScimGroupPatchApplier        GroupManager
       (Group ↔ SCIM JSON)     (member add/remove/replace)  (parseGroup/setGroup/
                │                                              removeGroup → group_members)
                ▼                                                     │
        UserDatabase (uid ↔ loginName resolution)            AuditService (audit_log)
```

## Group ↔ SCIM mapping

- SCIM **`id` = the group name** (Wikantik groups are name-keyed; there is no
  separate group uid). `displayName` = the same name.
- **`members[]`** → each `{ "value": <member user's uid>, "display": <loginName>,
  "$ref": "<usersBaseUrl>/<uid>" }`. On read, resolve each group member principal's
  `loginName → uid` via `db.findByLoginName(loginName).getUid()`.
- **`externalId`** — accepted in requests but **not persisted** (the `Group` object
  has no attributes field); group identity and dedup are by `displayName`.
- `meta.resourceType = "Group"`, `meta.location`.

## Member PATCH — the crux

`PATCH /scim/v2/Groups/{id}` supports the member operations IdPs send:

- **Add:** `{ "op":"add", "path":"members", "value":[{"value":"<uid>"}] }`
- **Remove one:** `{ "op":"remove", "path":"members[value eq \"<uid>\"]" }` —
  `ScimGroupPatchApplier` parses the `members[value eq "x"]` value-path (the form
  the Users `ScimPatchApplier` deliberately rejects).
- **Remove all:** `{ "op":"remove", "path":"members" }`.
- **Replace all:** `{ "op":"replace", "path":"members", "value":[...] }`.
- Handles both Okta (value-path removes) and Entra (path-less `value` object) idioms.

`ScimGroupPatchApplier.apply(currentMemberUids, patchOp)` returns the **resulting
member-uid set** (it computes adds/removes/replaces against the current set). It
throws `UnsupportedGroupPatchException` for operations outside this subset (e.g. a
value-path on a non-`members` attribute).

**Application strategy:** the resource does not mutate the `Group` in place. For any
change it computes the new member set, resolves each `uid → loginName`, builds the
newline-separated member line, then `gm.parseGroup(name, memberLine, true)` +
`gm.setGroup(systemSession, group)`. This reuses `setGroupInternal`, which fires
`GROUP_REMOVE` + `GROUP_ADD` (→ audit) and rolls back on save failure. Adequate for
the group sizes this deployment sees.

## Member resolution & the system session

- Member references are SCIM uids. Storage needs `db.findByUid(uid) → loginName`;
  rendering needs the reverse. An **unresolvable member uid → `400` `invalidValue`**
  (the whole PATCH/PUT fails; no partial application).
- `setGroup`/`removeGroup` require a `Session`. SCIM requests have no human session,
  so the resource obtains a **system/guest session** from the engine
  (`Wiki.session().find(engine, null)` or equivalent). SCIM attribution is recorded
  via the `scim.group.*` audit events below rather than the session principal.

## Lifecycle (endpoints)

- `POST /Groups` — `displayName` required; if a group with that name exists →
  `409` `uniqueness`; else `parseGroup` + `setGroup`, `201` + `Location` + body,
  audit `scim.group.create`.
- `GET /Groups/{id}` — `200` + mapped group / `404`.
- `GET /Groups?filter=displayName eq "x"&startIndex=&count=` — `ListResponse`;
  no filter → list all (paged); unsupported filter → `400` `invalidFilter`.
- `PUT /Groups/{id}` — replace the whole group (displayName + members);
  audit `scim.group.update`; `200`.
- `PATCH /Groups/{id}` — member ops (§ Member PATCH); audit `scim.group.update`;
  `UnsupportedGroupPatchException` → `400` `invalidPath`/`invalidValue`; `200`.
- `DELETE /Groups/{id}` — **hard delete** via `gm.removeGroup(name)`; `204`. Any page
  ACL or policy grant still naming the group becomes a no-op that matches nobody
  (fails closed). Audit `scim.group.delete`.

All responses `application/scim+json`; JSON via Gson. `usersBaseUrl`/`groupsBaseUrl`
derived from the request URL.

PATCH dispatch: `HttpServlet` has no `doPatch`, so `ScimGroupResource` overrides
`service()` to route PATCH (same approach as `ScimUserResource`).

## Audit

Membership changes flow through `GroupManager`, so the existing `GROUP_ADD` /
`GROUP_REMOVE` → `group.member.*` audit events fire automatically. The resource
additionally emits **`scim.group.create` / `scim.group.update` / `scim.group.delete`**
events (actor principal `"scim"`, category `ADMIN`, target = group name) for explicit
SCIM attribution — mirroring the Users `scim.user.*` pattern, guarded so an audit
failure never breaks the SCIM operation.

## Discovery

`ScimDiscoveryResource` is extended: `/ResourceTypes` adds the `Group` resource type;
`/Schemas` adds the `urn:ietf:params:scim:schemas:core:2.0:Group` schema. The existing
`ServiceProviderConfig` (patch/filter supported) already covers Groups.

## Testing

**Unit:**
- `ScimGroupMapper` round-trip: `Group` → SCIM JSON with `members[].value` = member
  uids (resolved from loginName) and back; `id` = displayName = group name.
- `ScimGroupPatchApplier`: `add` members; `remove` via `members[value eq "x"]`
  value-path; `replace` all; `remove` all (`path:"members"`); Entra path-less `value`
  idiom; non-`members` value-path → `UnsupportedGroupPatchException`.
- **Admin-role invariant:** creating/patching a SCIM group named `Admin` writes only
  to the group store and grants no role (assert no `roles` write / no role principal).
- Unresolvable member uid → error.

**Integration (`ScimGroupsIT`, running app + PostgreSQL):**
- Create user(s) + a group → `POST /Groups` (201) → `GET` shows it →
  `PATCH add member` → `GET` shows the member with `value` = the user's uid →
  `PATCH remove` via the `members[value eq "<uid>"]` value-path → member gone →
  `PUT` replace members → `GET ?filter=displayName eq "<name>"` → 1 result →
  `DELETE` (204) → subsequent `GET` → 404 → bad/absent token → 401. Assert a
  `scim.group.*` audit row (admin-authed `/admin/audit` query, reusing the
  `AuditLogIT`/`ScimUsersIT` admin-login pattern).

## Decisions (recorded)

1. **SCIM `id` = group name** (no separate group uid exists in Wikantik).
2. **Separate `ScimGroupPatchApplier`** (vs extending the Users `ScimPatchApplier`).
3. **Member changes rebuild the group via `parseGroup` + `setGroup`** (reuse the
   audited, rollback-safe path) rather than in-place mutation.
4. **System/guest session** for `setGroup`/`removeGroup`; SCIM attribution via
   `scim.group.*` audit events.
5. **`members` included in list responses** (groups are small here; can be capped to
   GET-`{id}`-only later if needed).
6. **Hard delete** on `DELETE`; **externalId dropped** (key on `displayName`).

## Out of scope / next

- `externalId` persistence for groups (side table) — only if an IdP requires it.
- Capping/paging `members` in list responses for very large groups.
- Mapping any SCIM construct to the Wikantik **role** table — explicitly never (the
  invariant above).
- Nested groups / groups-as-members (SCIM allows `members` of type `Group`) — v1
  supports user members only; a `Group`-typed member → `400` `invalidValue`.
