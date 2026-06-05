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

SCIM requests are authenticated by a static bearer token. Set it in
`wikantik-custom.properties` (or as a JVM system property):

```properties
wikantik.scim.token = <a-long-random-secret>
```

The token is read by `ScimAccessFilter` at startup — first from the system
property `wikantik.scim.token`, then from the servlet filter init parameter of
the same name in `web.xml`. If neither is set, the filter logs a warning and
denies all SCIM requests with HTTP 401.

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

Default page size is 100; the maximum the SCIM server advertises is 1000
(see `ServiceProviderConfig`).

### Replace — `PUT /scim/v2/Users/{uid}`

Full replacement of mutable attributes (`name.formatted`, `emails[0].value`,
`displayName`, `externalId`, `password`, `active`). `userName` is not
replaced by PUT. Active/inactive transitions are routed through
`UserLifecycleService`.

### Partial update — `PATCH /scim/v2/Users/{uid}`

Supported PATCH operations: `Replace` on `active`, `displayName`,
`name.formatted`, `emails[0].value`, and `externalId`. Active/inactive
changes go through `UserLifecycleService` (deactivate / reactivate).

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

All SCIM user operations are recorded in the tamper-evident audit log
(see [AuditLog.md](AuditLog.md)) under category `ADMIN` with event types
`scim.user.create`, `scim.user.update`.

## Groups (`/scim/v2/Groups`)

Groups route exclusively through `GroupManager` and `UserDatabase`. The
`roles` table is never touched. **A SCIM group named "Admin" creates a group
only — it does not grant the Admin role** to any of its members.

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
