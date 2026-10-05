# Security Model and Hardening

This page is for administrators who deploy and operate Wikantik. It describes how
users authenticate, how access is authorised, which protections are on by default, and
which settings can weaken them. It states what the code does today; each section names
the class or property that enforces it. To report a vulnerability, see the
[security policy](../../SECURITY.md) instead. Deep references are linked from each
section.

## Authenticate users

Wikantik authenticates through JAAS. The default login module is
`com.wikantik.auth.login.UserDatabaseLoginModule` (`wikantik.loginModule.class`), which
checks the supplied password against the PostgreSQL-backed user database (see
[PostgreSQL.md](PostgreSQL.md)). New passwords are hashed with bcrypt.

| Method | How it works | Reference |
|---|---|---|
| Database accounts (default) | `UserDatabaseLoginModule` against the `users` table; the React login page posts credentials | [PostgreSQL.md](PostgreSQL.md) |
| HTTP Basic | `BasicAuthFilter` turns an `Authorization: Basic` header on `/api/*` and `/admin/*` into a real login, for scripts and CI | this page |
| Servlet-container auth | credentials supplied by the container (`getUserPrincipal`) are always picked up; to make users log in through the container, uncomment the `<security-constraint>` elements in `web.xml` | `wikantik.properties` (authentication section) |
| Single sign-on | OpenID Connect and SAML 2.0 through pac4j, alongside local login | [SingleSignOn.md](SingleSignOn.md) |
| IdP provisioning | SCIM 2.0 server at `/scim/v2/*` for onboarding and offboarding | [ScimProvisioning.md](ScimProvisioning.md) |
| Custom JAAS module | set `wikantik.loginModule.class` to your own `LoginModule` (zero-argument constructor); options go in `wikantik.loginModule.options.*` | `wikantik.properties` |

Wikantik ships no LDAP login module. To authenticate against LDAP, supply a JAAS
`LoginModule` through `wikantik.loginModule.class`, or put an LDAP-capable IdP in front
of Wikantik and use SSO.

### SSO identity binding

SSO links a login to a local account through `wikantik.sso.identityClaim` (default
`sub`). Set it to `preferred_username` only if your IdP keeps that claim stable and
unique, because it is mutable at most IdPs. SSO never adopts a pre-existing local
account of the same name that was not created by SSO; a name collision without a
matching marker fails closed. Configuration detail is in [SingleSignOn.md](SingleSignOn.md).

### Sessions and the remember-me cookie

- The session cookie (`JSESSIONID`) must be issued with `SameSite=Lax`. The Tomcat
  context template sets `<CookieProcessor sameSiteCookies="lax" />`
  (`Tomcat-context.xml.template`). A `Strict` or missing attribute makes browsers
  withhold the cookie on top-level navigations, so users look randomly logged out.
  `SessionCookiePolicyFilter` logs an `ERROR` (rate-limited) when it sees the broken
  policy; it never changes the response.
- `web.xml` marks `JSESSIONID` `HttpOnly` and cookie-only (no URL rewriting) but
  deliberately does **not** set `Secure`, because the right value depends on topology.
  `Secure` therefore depends on Tomcat knowing the request was HTTPS: either TLS reaches
  Tomcat directly, or a TLS-terminating proxy sends `X-Forwarded-Proto: https` and the
  `RemoteIpValve` is configured with `protocolHeader="X-Forwarded-Proto"` (the
  comment in `web.xml` shows the valve and cookie processor). Without that, the session
  cookie is issued without `Secure`.
- `wikantik.cookieAuthentication` (default `false`) turns on remember-me. A successful
  password login then also issues a `WikantikUID` cookie that is `HttpOnly`,
  `SameSite=Lax`, `Secure` on HTTPS, and holds an opaque, server-validated token.
  `RememberMeAuthFilter` uses it to re-establish the session after a restart or
  timeout. `wikantik.cookieAuthentication.expiry` sets the lifetime in days (default
  14). The token-to-user mapping lives in `$wikantik.workDir/logincookies`; restrict
  read access to that directory, because it can impersonate any user.
- A user whose password was reset or created by an admin can be forced to change it;
  `MustChangePasswordFilter` enforces that before other `/api/*` and `/admin/*` calls.

### Password policy

`PasswordValidator` follows NIST 800-63B: length over composition rules, plus a
blocklist.

| Property | Default | Effect |
|---|---|---|
| `wikantik.password.minLength` | `8` | shortest accepted password |
| `wikantik.password.maxLength` | `128` | longest accepted password |
| `wikantik.password.blocklist.enabled` | `true` | reject passwords on the bundled common-password list (case-insensitive) |
| `wikantik.login.throttling` | `true` | slow repeated failed logins per account |

Deactivating an account locks it out of password and remember-me login.

## Authorise access

Authorisation combines three layers: policy grants for what a role may do, groups for
who holds the role, and page ACLs that narrow access to individual pages.

### Policy grants

Default permissions for each role are rows in the `policy_grants` table
(`principal_type`, `principal_name`, `permission_type`, `target`, `actions`). Manage
them at **Admin → Security** (`/admin/security`), backed by `/admin/policy`. The
migration `V003__policy_grants.sql` seeds the baseline:

| Role | Seeded permissions |
|---|---|
| `All` | view any page; edit own preferences and profile; log in |
| `Authenticated` | modify and rename pages; create pages and groups; export the vault (`V060__wiki_export_permission.sql` added `export`); view groups; edit groups they belong to |
| `Asserted` | view groups |
| `Admin` | `AllPermission` (`V043__admin_allpermission_canonical.sql` collapsed the older page and wiki wildcard rows into a single `all` row) |

Permission types are `page`, `wiki`, `group`, `admin` and `all`. Page permissions are
`view`, `comment`, `edit`, `modify`, `upload`, `rename`, `delete`; wiki permissions are
`createPages`, `createGroups`, `export`, `editPreferences`, `editProfile`, `login`. Always change
grants through the admin UI or `/admin/policy`, because the policy is cached and only
the API refreshes it. If `policy_grants` is unavailable, the file-based fallback
`WEB-INF/wikantik.policy` applies.

Two rules in `DatabasePolicy` matter when you edit grants:

- A grant whose `target` is `*` and whose `actions` is `*` is converted to
  `AllPermission` **whatever its permission type**. A `page` or `wiki` grant of `*`/`*`
  to a role makes that role a full administrator, so do not create one unless you mean
  it. Use permission type `all` to say so explicitly. A wildcard action on a specific
  target is malformed and is skipped with a warning.
- Grants match on `principal_name` only; `principal_type` is ignored. A grant named
  `Admin` therefore applies to the `Admin` group, to a container role named `Admin`, and
  to a user whose login is `Admin`.

### Who counts as an administrator

`/admin/*` is protected by `AdminAuthFilter`. A request passes when the session holds
`AllPermission` or a scoped `admin` grant for the area it targets. `AllPermission`
comes from the `Admin` **group**: membership of the `Admin` group (the `groups` and
`group_members` tables) is what makes an account an administrator. A row in the `roles`
table alone is **not** enough: such an account authenticates but receives HTTP 403 on
`/admin/*`. The tell is that a wrong password returns 401 and a correct one returns 403.

Add an administrator through the API so the in-memory group cache updates. `PUT`
**replaces** the member list, so read it first:

```bash
curl -u admin:... http://localhost:8080/admin/groups/Admin
curl -u admin:... -X PUT -H 'Content-Type: application/json' \
     -d '{"members":["existing1","existing2","new-admin"]}' \
     http://localhost:8080/admin/groups/Admin
```

Other behaviour of `AdminAuthFilter`:

- A browser navigation (GET with `Accept: text/html`) to an `/admin/*` route skips the
  check so the React shell can load and send the user to the login page. JSON and XHR
  calls always go through the check. A path containing a dot that is not `.html` is
  never treated as a navigation, so it cannot skip authentication and still reach a
  servlet.
- The SPA's `AdminLayout` also redirects a signed-in user without the `Admin` role to
  `/wiki/Main`; this is a convenience, and the server-side filter is the control.

### Scoped admin access

`/admin/*` spans many functional areas behind one `AllPermission` check, so any
credential that reaches one reaches all of them, including `apikeys`, `policy` and
`connector-credentials`. To grant one area only, create a policy grant with permission
type `admin`, `target` set to the first path segment after `/admin/`, and action
`access`:

```json
{"principalType":"user","principalName":"shipper-account","permissionType":"admin",
 "target":"insights","actions":"access"}
```

Post it to `/admin/policy` (not raw SQL). `AdminAuthFilter` derives the area from the
request path, so a new endpoint gets its own area that nobody holds a grant for and
keeps requiring `AllPermission`. A bare `/admin` or a segment containing `..` or `:`
never matches an area grant. `AllPermission` implies every area grant, so existing
administrators are unaffected. The content-intelligence shipper uses this; see
[ContentIntelligence.md](ContentIntelligence.md).

### Page ACLs

A page can restrict itself with inline ACL markup in its body:

```text
[{ALLOW view Admin}]
[{ALLOW edit Admin,Editors}]
```

Principals after the permission are comma-separated. Pages shipped in
`wikantik-wikipages` write the directive with a trailing `()`, for example
`[{ALLOW edit Admin}]()`; the ACL itself is read from the `[{ALLOW ...}]` part
(`DefaultAclManager.ACL_REGEX`).

When a page carries an ACL, a user needs both the matching role or group grant and an
ACL entry that names one of their principals. REST endpoints under `/api/*` enforce
ACLs through `RestServletBase.checkPagePermission()`, and `/wiki/{slug}?format=md|json`
hides restricted pages with a 404.

### Page ownership and the audit trail

Page ownership is covered in [PageOwnership.md](PageOwnership.md). Authentication,
authorisation, content and admin events go to a tamper-evident hash-chained log; see
[AuditLog.md](AuditLog.md) and **Admin → Audit** (`/admin/audit`).

## Bootstrap admin override

`wikantik.admin.bootstrap` names a login that gets `AllPermission` regardless of database
grants. It exists only to recover access to a fresh or locked-out installation. The
override applies only to a session that **authenticated** with that login name. An
asserted ("remembered name") identity never qualifies, however it was obtained.

| Property | Default | Meaning |
|---|---|---|
| `wikantik.admin.bootstrap` | blank | login name of the bootstrap admin; blank means no override |
| `wikantik.admin.bootstrap.maxAgeSeconds` | blank (86400, 24 hours) | how long the override lasts after server start |

While it is active, startup logs a `CRITICAL: BOOTSTRAP ADMIN OVERRIDE IS ACTIVE` error
with the expiry. Remove the property once a real administrator works; do not leave it
set in production.

### Asserted identities

With `wikantik.cookieAssertions` (default `true`), a visitor who has not logged in can
"assert" a name through a cookie. The session then gets the `Asserted` role instead of
`Authenticated`. This is the old wiki convenience of remembering who you say you are; it
is not authentication and the property file itself calls it unsafe. An asserted session
holds only what the `Asserted` and `All` policy grants give it. By default that is
viewing groups (`Asserted`) plus the `All` grants: view any page, `editPreferences`,
`editProfile` and `login` (`V003__policy_grants.sql`). It never receives authenticated rights such as the bootstrap
override. Set `wikantik.cookieAssertions=false` if you do not want the feature.

## Control agent and API access

### API keys and agent access

API keys are SHA-256 hashed; only the hash is stored and the plaintext is shown once.
`ApiKeyService.Scope` has four values:

| Scope | Wire value | Reaches |
|---|---|---|
| `MCP_READ` | `mcp_read` | `/knowledge-mcp` only (read-only) |
| `MCP` | `mcp` | `/wikantik-admin-mcp` and `/knowledge-mcp` (full admin) |
| `TOOLS` | `tools` | `/tools/*` only |
| `ALL` | `all` | every key-protected surface; the default if a `POST` omits `scope` |

The MCP scopes are a rank hierarchy (`mcp_read` is contained in `mcp`); `tools` is
separate. The `mcp` wire value predates the split, so older keys remain full-admin.
Prefer the narrowest scope. Non-administrators can self-mint only `tools` and
`mcp_read` keys; `mcp` and `all` keys are issued by an administrator at `/admin/apikeys`. The `/api/*` REST surface uses session or Basic
authentication and does not accept API keys.

`/knowledge-mcp` and the other MCP and tools endpoints also fail closed. With no key,
CIDR allowlist (`mcp.access.allowedCidrs`, `tools.access.allowedCidrs`) or explicit
`allowUnrestricted=true`, they answer 503. Each has its own per-client limiter
(`mcp.ratelimit.*`, `tools.ratelimit.*`). Details: [ApiKeys.md](ApiKeys.md),
[McpAgents.md](McpAgents.md).

### Rate limiting

`RateLimitFilter` limits requests per client IP on a sliding window. It runs on
`/api/*`, `/id/*`, `/export/*` and `/sparql`, after request metrics and before the
authentication filters. It has two tiers:

| Tier | Paths | Per-client limit | Global limit |
|---|---|---|---|
| default | everything the filter maps | 25 requests per second | none (concurrency is bounded by `BackpressureFilter`) |
| expensive | prefixes `/api/bundle`, `/api/search`, `/sparql` | 3 requests per second | 10 requests per second across all clients |

Exempt from limiting: loopback callers (IPv4 and IPv6), any IPv4 CIDR in the exempt
list, and the exact path `/api/health`. The client IP is `getRemoteAddr()`, which is
the real client only if Tomcat's `RemoteIpValve` is configured to read the header your
proxy sets: `CF-Connecting-IP` behind Cloudflare, the deploy-local template hard-codes it,
and Docker deployments set it with `PROXY_REMOTE_IP_HEADER` (see
[DockerDeployment.md](DockerDeployment.md)). A rejected request gets `429` with `Retry-After: 1`, a `SecurityLog`
line, and an increment of `wikantik_ratelimit.rejected_total{tier=...}`.

Configure it with environment variables, read once at startup. A limit of `0` disables
that bucket and all zeros disable the filter:

| Variable | Default |
|---|---|
| `WIKANTIK_RATELIMIT_DEFAULT_PERCLIENT` | `25` |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PERCLIENT` | `3` |
| `WIKANTIK_RATELIMIT_EXPENSIVE_GLOBAL` | `10` |
| `WIKANTIK_RATELIMIT_EXPENSIVE_PATHS` | `/api/bundle,/api/search,/sparql` |
| `WIKANTIK_RATELIMIT_EXEMPT_CIDRS` | empty |

**Get the proxy header right.** If the configured header does not match what your proxy
sends, every request appears to come from the proxy's own loopback or private address.
If the proxy is on loopback, loopback is exempt from rate limiting, so nobody is limited.
If the proxy is on a private but non-loopback address, every client shares one
rate-limit bucket. Either way, `InternalNetworkFilter` would let anyone
reach `/metrics` and `/api/health`, and any `mcp.access.allowedCidrs` or
`tools.access.allowedCidrs` entry covering that address would trust every caller. The
reverse is just as dangerous: a proxy that forwards a client-supplied copy of the
trusted header lets clients spoof `127.0.0.1`. Make the proxy overwrite the header, and
check the result after any proxy change.

### Public ontology surfaces

`/sparql`, `/id/{type}/{id}` and `/export/*` are public, read-only and permissive
about cross-origin access. They are safe to leave public because of a public/restricted
split: `PublicProjectionFilter` keeps pages, entities and edges that an anonymous guest
cannot view out of the materialised dataset entirely, so restricted content cannot be
queried. `/sparql` rejects update operations and caps results and run time. See
[OntologyManagement.md](OntologyManagement.md).

## Reduce risk from risky features

### Connector egress guard

Connector HTTP fetches (web crawler, sitemap, feed, GitHub, Confluence) pass through
`EgressGuard`, a server-side-request-forgery control. It rejects any URL whose scheme
is not `http` or `https`, and any host that resolves to a loopback, private,
link-local (including the cloud metadata address `169.254.169.254`), multicast or
unspecified address, or an IPv6 unique-local address. It re-validates every redirect
hop, up to 5. To crawl an internal host on purpose, start the JVM with
`-Dwikantik.connectors.egress.allowPrivate=true`; the setting is read only as a system
property, and the scheme check still applies. The `filesystem` connector type is
properties-only and cannot be created from the admin UI. See
[Connectors.md](Connectors.md).

### JDBCPlugin

`JDBCPlugin` runs SQL authored in a page against the wiki's own datasource, using the
application's database role. It is off by default behind `wikantik.plugin.jdbc.enabled`
(`false`), checked before anything else. The additional admin check applies to the
user **viewing** the page, not to the author, so the real risk is an editor planting SQL
that runs, with the application's database role, when an administrator views the page.
Enable it only if you accept that a high-privilege database role executes
editor-supplied SQL.

### Render-cache isolation

A render that depends on the viewer (an ACL-aware include, a `$username` variable)
flags itself with `Context.VAR_VIEWER_SENSITIVE`. Those renders are excluded from the
principal-less document and HTML caches, so output rendered for one user is never
served to another.

### Other defaults

- Deserialization uses `ObjectInputFilter` allow-lists wherever an
  `ObjectInputStream` is used.
- Responses carry `Content-Security-Policy`, `Strict-Transport-Security`,
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, a restrictive referrer
  policy and cross-origin isolation headers, applied by the filters in `web.xml`
  (`CSPFilter`, `STSFilter`, `ClickJackFilter`, `ContentTypeOptionsFilter`,
  `ReferrerPolicyFilter`, `COEPFilter`, `CORPFilter`).
- `/api/health` and `/metrics` are reachable only from loopback and RFC 1918 networks
  (`InternalNetworkFilter`).
- MCP write tools refuse system pages; `wikantik.systemPages.mcpEditable` (default
  `About`) lists the exact page names that stay editable.

## Where to go next

- [ApiKeys.md](ApiKeys.md), [McpAgents.md](McpAgents.md): keys, scopes and the tool catalogue.
- [SingleSignOn.md](SingleSignOn.md), [ScimProvisioning.md](ScimProvisioning.md): IdP integration.
- [AuditLog.md](AuditLog.md): review and verify the audit trail.
- [KgInclusionPolicy.md](KgInclusionPolicy.md): control which pages feed the Knowledge Graph.
- [Connectors.md](Connectors.md), [OntologyManagement.md](OntologyManagement.md), [RetrievalQuality.md](RetrievalQuality.md): the subsystems whose security notes are summarised above.
- [Security policy](../../SECURITY.md): how to report a vulnerability.
