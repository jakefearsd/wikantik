# SCIM 2.0 Provisioning

Wikantik supports SCIM 2.0 (RFC 7643/7644) for IdP-driven user and group
provisioning at `/scim/v2/*`. The module is `wikantik-scim`; the implementation
classes are `ScimUserResource`, `ScimGroupResource`, `ScimDiscoveryResource`, and
`ScimAccessFilter`.

SCIM is the **provisioning** path. Authentication is handled separately by SSO
(see [SingleSignOn.md](SingleSignOn.md)). The typical pattern is: the IdP uses
SCIM to create accounts and maintain group membership, while SSO handles the
login flow. SCIM-provisioned accounts authenticate via SSO by default — a random
password is generated at creation time and never revealed.

## Enabling SCIM

### 1. Set the bearer token

SCIM requests are authenticated by a static bearer token. `ScimAccessFilter`
reads it **only** from the `wikantik.scim.token` JVM system property or, failing
that, the filter's `wikantik.scim.token` init-param in `web.xml`. A value in
`wikantik-custom.properties` or `wikantik.properties` is never read and has no
effect.

```bash
# Tomcat: add to CATALINA_OPTS
-Dwikantik.scim.token=<a-long-random-secret>
```

In Docker deployments set `WIKANTIK_SCIM_TOKEN` in `.env.prod`; `docker/entrypoint.sh`
turns it into the system property (and refuses to start if the token contains characters
outside `[A-Za-z0-9+/=_-]`; `openssl rand -hex 32` produces a valid one). If no token is
configured, the filter logs a warning and denies all SCIM requests with HTTP 401.

The token is a powerful credential. It can create and deactivate accounts and, through the
Groups endpoints, change the membership of any group except `Admin`, which SCIM refuses to
write from 2.4.57 on (see [Groups](#groups-scimv2groups)).

### 2. No migration prereq

The SCIM endpoints operate against the `users`, `groups`, and `group_members`
tables created by `V002__core_users_groups.sql`. No additional migration is
required.

### 3. Verify the filter is active

On startup, if the token is absent, `ScimAccessFilter` logs:

```
ScimAccessFilter: no wikantik.scim.token configured — all SCIM requests will be denied.
```

A successful SCIM request (HTTP 200/201) confirms the filter is passing requests
through.

## Authentication model

Every request to `/scim/v2/*` must carry:

```
Authorization: Bearer <wikantik.scim.token>
```

The filter uses constant-time comparison to prevent timing attacks. Any request
without a matching token receives:

```json
{"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],"status":"401","detail":"invalid or missing bearer token"}
```

All SCIM responses use `Content-Type: application/scim+json`.

## Users (`/scim/v2/Users`)

### Create — `POST /scim/v2/Users`

Provisions a new account. `userName` is required; all other fields are optional.

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
  "userName": "alice",
  "name": { "formatted": "Alice Example" },
  "emails": [{ "value": "alice@example.com", "primary": true }],
  "externalId": "idp-subject-123",
  "active": true
}
```

Behavior:

- If `externalId` is provided it is stored as the `sso.subject` attribute,
  linking the account to an SSO identity. If `password` is omitted, a random
  secret is generated and never revealed — the account is SSO-only.
- **SSO fail-closed**: if an account with the given `userName` already exists
  and does not carry an `sso.subject` marker, SCIM returns HTTP 409 with
  `scimType: uniqueness`. SCIM cannot claim a pre-existing local account.
- **Names**: `name.formatted` becomes the account's display name, which other users see in
  place of the login, so it must satisfy the display-name rules: 2 to 64 characters, no `@`
  and no control or invisible formatting characters; not reserved (a built-in role, `Admin`,
  or a role or group name); not another account's login name; and not already another
  account's display name (ignoring case and surrounding whitespace). A name that is taken (it
  is another account's display name or login) is refused with HTTP 409 and
  `scimType: uniqueness`; a malformed or reserved one is refused with HTTP 400 and
  `scimType: invalidValue`. Both apply on `POST`, `PUT` and `PATCH`, nothing is saved, and the
  `detail` names the rule. A `POST` without a `name.formatted` (absent or blank) creates the
  account with the placeholder display name `Member N` (the lowest free N); a login is never
  used as a display name. A `displayName` follows the same two statuses (reserved: `400`;
  another account's login name: `409`). Wiki names stay unique, so a clashing one gets a
  number: `JohnSmith2` when the name comes from `name.formatted`, `John Smith2` when it comes
  from `displayName`. The `201` response's `displayName` is that stored wiki name, and it is
  kept when the identity provider re-sends the same name in a later `PUT` or `PATCH`. A save
  that still collides with another account's login or wiki name returns `409` `uniqueness`
  with a generic message.
- If `active: false` is included in the create body, the account is immediately
  deactivated via `UserLifecycleService` after creation.
- On success: HTTP 201, `Location` header pointing to the new resource, and the
  full user representation in the response body.

### Retrieve — `GET /scim/v2/Users/{uid}`

Returns the user identified by their internal uid.

### List / filter — `GET /scim/v2/Users`

Returns a `ListResponse`. Supports `filter`, `startIndex`, and `count`
parameters. Supported filter attributes:

| Filter attribute | Example |
|---|---|
| `userName` | `filter=userName eq "alice"` |
| `externalId` | `filter=externalId eq "idp-subject-123"` |

Filtering on any other attribute returns HTTP 400 `invalidFilter`.

Default page size (`count`) is 100. `ServiceProviderConfig` advertises a `maxResults` of
1000, but the server does not enforce a cap on `count`.

### Replace — `PUT /scim/v2/Users/{uid}`

Full replacement of mutable attributes (`name.formatted`, `emails[0].value`,
`displayName`, `externalId`, `password`, `active`). `userName` is not
replaced by PUT. Active/inactive transitions are routed through
`UserLifecycleService`.

### Partial update — `PATCH /scim/v2/Users/{uid}`

Supported PATCH operations: `Replace` (and `Add`, treated the same) on `active`,
`displayName`, `externalId`, `name` (a `{"formatted": …}` object) and `emails` (an array
whose first `value` is the address), and `Remove` on any of those except `active`. A
path-less operation whose `value` is an object carrying these attributes (the Entra ID
style) is also accepted. Indexed or filtered paths such as `emails[0].value` or
`emails[type eq "work"].value` are refused with HTTP 400 `invalidPath`; send `emails`
with an array value instead, and do not use dotted paths such as `name.formatted`.
Active/inactive changes go through `UserLifecycleService` (deactivate / reactivate).

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [{ "op": "Replace", "path": "active", "value": false }]
}
```

### Soft-delete — `DELETE /scim/v2/Users/{uid}`

Does **not** remove the database row. Calls
`UserLifecycleService.deactivate(userName, "scim", "scim/delete")`, which locks
the account. The account survives audit history and can be reactivated.

SCIM user writes are recorded in the tamper-evident audit log
(see [AuditLog.md](AuditLog.md)) under category `ADMIN` with event types
`scim.user.create` and `scim.user.update` (actor `scim`). Deactivation and
reactivation (an `active` change, `active: false` on create, and `DELETE`) also record
`user.deactivate` / `user.reactivate` through `UserLifecycleService`; `DELETE` records
only the `user.deactivate` event.

## Groups (`/scim/v2/Groups`)

Groups route exclusively through `GroupManager` and `UserDatabase`, the same
`groups` / `group_members` store behind **Admin → Security**. The `roles` table is
never touched.

**SCIM never writes the `Admin` group.** Administrator access comes from membership of
the `Admin` group (see [Security.md](Security.md#who-counts-as-an-administrator)), so
SCIM refuses every write to it: `POST` with `displayName` `Admin`, and `PUT`, `PATCH` and
`DELETE` on `/scim/v2/Groups/Admin`, return `403` before anything is changed (the name is
matched case-insensitively). Each refusal is logged at WARN and audited as `DENIED` under
the usual `scim.group.*` event type. Reading the group (`GET`, list, filter) still works.
Change administrators in **Admin → Security → Groups** instead. Releases up to and
including 2.4.56 did not have this guard: there, `PUT`/`PATCH` on the Admin group rewrote
its membership and `DELETE` removed it, so on those versions treat the SCIM token as an
administrator-equivalent credential and do not map an IdP group onto `Admin`.

### Create — `POST /scim/v2/Groups`

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
  "displayName": "Engineering",
  "members": [
    { "value": "<uid-of-alice>" }
  ]
}
```

`displayName` is required. Members are resolved by uid; an unresolvable uid
returns HTTP 400 `invalidValue`. Nested groups are not supported (returns HTTP
400 `invalidValue`).

On success: HTTP 201, `Location: /scim/v2/Groups/Engineering`.

### Retrieve — `GET /scim/v2/Groups/{name}`

### List / filter — `GET /scim/v2/Groups`

Supports `filter=displayName eq "Engineering"`. Filtering on other attributes
returns HTTP 400 `invalidFilter`.

### Replace — `PUT /scim/v2/Groups/{name}`

Replaces the full member list. Members are given as uids; all unresolvable uids
fail the entire operation (no partial apply).

### Membership PATCH — `PATCH /scim/v2/Groups/{name}`

Supports `Add` and `Remove` operations on `members`. Each member object
carries the user uid in `value`. Unresolvable uids return HTTP 400.

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [
    { "op": "Add",    "path": "members", "value": [{ "value": "<uid>" }] },
    { "op": "Remove", "path": "members", "value": [{ "value": "<uid>" }] }
  ]
}
```

### Hard delete — `DELETE /scim/v2/Groups/{name}`

Calls `GroupManager.removeGroup(name)` — **permanent**. The group and its
membership records are removed from the database immediately.

Group operations are recorded in the audit log under `ADMIN` with event types
`scim.group.create`, `scim.group.update`, `scim.group.delete`.

## Discovery endpoints

These three endpoints implement RFC 7643 §8. They return static JSON and require
the same bearer-token auth as all other SCIM endpoints.

| Endpoint | Description |
|---|---|
| `GET /scim/v2/ServiceProviderConfig` | Capability advertisement (patch: true, bulk: false, filter: true maxResults 1000, changePassword: false, sort: false, etag: false) |
| `GET /scim/v2/Schemas` | User and Group schema descriptors |
| `GET /scim/v2/ResourceTypes` | User and Group resource types |

## Endpoint reference summary

| Method | Path | Description | Success |
|---|---|---|---|
| `POST` | `/scim/v2/Users` | Provision user | 201 |
| `GET` | `/scim/v2/Users/{uid}` | Retrieve user | 200 |
| `GET` | `/scim/v2/Users` | List/filter users | 200 |
| `PUT` | `/scim/v2/Users/{uid}` | Replace user | 200 |
| `PATCH` | `/scim/v2/Users/{uid}` | Update user | 200 |
| `DELETE` | `/scim/v2/Users/{uid}` | Soft-delete (deactivate) | 204 |
| `POST` | `/scim/v2/Groups` | Create group | 201 |
| `GET` | `/scim/v2/Groups/{name}` | Retrieve group | 200 |
| `GET` | `/scim/v2/Groups` | List/filter groups | 200 |
| `PUT` | `/scim/v2/Groups/{name}` | Replace membership | 200 |
| `PATCH` | `/scim/v2/Groups/{name}` | Update membership | 200 |
| `DELETE` | `/scim/v2/Groups/{name}` | Hard delete | 204 |
| `GET` | `/scim/v2/ServiceProviderConfig` | Capability document | 200 |
| `GET` | `/scim/v2/Schemas` | Schema descriptors | 200 |
| `GET` | `/scim/v2/ResourceTypes` | Resource types | 200 |

## Troubleshooting

**All SCIM requests return 401**

The `wikantik.scim.token` property is missing or blank. Check startup logs for
`ScimAccessFilter: no wikantik.scim.token configured`.

**POST /Users returns 409 uniqueness**

A local account with that `userName` already exists. If it is an existing local
account without an SSO link, SCIM cannot claim it — create the account via the
admin UI instead, or remove it and let SCIM re-provision it.

The same status is returned when `name.formatted` is already another account's display name
(compared ignoring case) or is another account's login name, and when `displayName` is another
account's login name; the response `detail` names the value. Change the name in the identity
provider. Two users can no longer share a display name.

**POST, PUT or PATCH /Users returns 400 invalidValue about a name**

`name.formatted` is malformed (shorter than 2 or longer than 64 characters, or containing `@`,
a control character or an invisible formatting character) or reserved (a built-in role,
`Admin`, or a role or group name), or `displayName` is reserved. The `detail` says which rule.
Change the name in the identity provider.

**POST /Groups returns 400 invalidValue "No user found for member uid"**

The `value` in the members array is a uid that does not exist in the user
database. Retrieve the correct uid via `GET /scim/v2/Users?filter=userName eq
"alice"` first.

**503 "user database unavailable" or "group manager unavailable"**

The engine failed to initialize. Check the Tomcat startup log for earlier
`WikiEngine` errors.

## Related

- [SingleSignOn.md](SingleSignOn.md) — SSO is the authentication path; SCIM is the provisioning path
- [AuditLog.md](AuditLog.md) — SCIM operations are recorded as ADMIN-category audit events
