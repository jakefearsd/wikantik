---
status: active
date: '2026-04-26'
title: Wiki Permission Model Guide
related:
- WikiContentManagementWorkflow
- NetworkSecurityFundamentals
- CloudSecurityFundamentals
canonical_id: 01KQ0P44Z4JH82M2KYBEME5M2H
cluster: wikantik-development
type: article
tags:
- wiki
- permissions
- access-control
- wikantik-development
summary: How wiki permission models work — role-based, ACL-based, group-based — and
  the patterns for managing access at scale without making every page require approval.
---
# Wiki Permission Model Guide

A wiki's permission model determines who can read, edit, delete, and administer. Different models suit different organizations.

This page covers the major models and the patterns for managing access well.

## Common permission models

### Role-based

Users have roles: viewer, editor, admin. Roles have permissions.

```
Viewer: read all
Editor: read all + edit
Admin: read all + edit + admin
```

Simple. Works for small organizations or where everyone has similar access needs.

### Group-based

Users belong to groups. Groups have permissions on specific resources.

```
Group "Engineering": read /engineering, edit /engineering
Group "Finance": read /finance, edit /finance
```

For organizations with departmental boundaries.

### ACL-based

Per-resource access control list. Each page (or section) has its own ACL.

```
Page X: alice can edit; bob can read; engineering group can read
```

Most flexible; most overhead. For wikis with sensitive content.

### Public

Anyone can read; specific users can edit.

For open wikis (Wikipedia-style); some internal "transparency" cultures.

## Common permissions

The standard set:

- **View / read**: see the page
- **Edit**: modify the page
- **Comment**: add comments without editing
- **Upload attachment**: add files
- **Delete**: remove pages
- **Move / rename**: change URL
- **Administer**: change permissions
- **Create**: make new pages

Different wikis combine these differently. Check what your platform supports.

## Specific patterns

### Default deny vs. default allow

Public wikis: default allow (everyone reads; few restrictions).
Corporate wikis: default deny (start restricted; grant explicitly).

### Inheritance

Page inherits parent permissions unless overridden. Reduces ACL bloat.

For Confluence-style hierarchical wikis, this is essential.

### Group + role hybrid

Roles within groups: "engineering admin" has admin within /engineering only.

For larger organizations.

### Time-bounded access

Grant access for a specific period. Auto-expires.

For contractor access; temporary collaboration.

### Explicit deny

Allow group X; deny user Y (who's in group X). Removes user Y specifically.

Useful but adds complexity.

## Authentication

Distinct from authorization. Authentication: who is this user. Authorization: what can they do.

### Built-in users

Wiki manages its own user accounts. Username/password.

For small wikis. Operationally simple; security depends on wiki's auth.

### SSO / OAuth

Users authenticate via central identity provider (Google, Microsoft, Okta, GitHub).

For organizations with central identity. Stronger security; user provisioning automated.

### LDAP / Active Directory

Users come from LDAP. Groups come from LDAP.

Common for enterprise wikis. Permissions managed in LDAP, not in the wiki.

### Mixed

Authenticated via SSO; some local accounts for special cases (admins, automation).

## Access management practices

### Principle of least privilege

Users have only the permissions they need. Not "admin for everyone for convenience."

### Just-in-time access

For sensitive operations, request access; granted temporarily; auto-revokes.

For most wikis, simpler permanent access works. JIT for high-stakes actions.

### Group ownership

Groups owned by specific people who decide membership. Avoids "who's in this group" being a free-for-all.

### Periodic review

Quarterly: review access. Who has admin? Should they still? Remove inactive users.

### Audit logging

Log permission changes. Who granted what to whom.

For regulated environments, required. For others, valuable.

## Wikantik-specific notes

The Wikantik project uses:
- Database-backed policy grants for default role permissions (the `policy_grants` table, edited under **Admin → Security → Policy Grants** at `/admin/security`; see [Database-Backed Permissions](DatabaseBackedPermissions))
- Database-backed groups (the `groups` and `group_members` tables), edited under **Admin → Security → Groups** or through `/admin/groups`
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax; list several principals with commas, for example `[{ALLOW edit Admin,Editors}]`
- REST API permission enforcement via `RestServletBase.checkPagePermission()`
- Bootstrap admin override (`wikantik.admin.bootstrap`) for initial setup or recovery; it applies only to an authenticated session of that login name and lapses after `wikantik.admin.bootstrap.maxAgeSeconds` (24 hours by default)

The model is hybrid: role-based defaults from the policy grants, narrowed by per-page ACLs. A page ACL cannot grant more than the policy allows. When a page carries one, a user needs both the matching role or group grant *and* an ACL entry that names one of their principals. `AllPermission` (held through membership of the `Admin` group) bypasses page ACLs.

### Identify people by login name

Group members, `user`-type policy grants and user entries in page ACLs all match by **login name**. A full name or wiki name is editable profile data and never matches. The group editor rejects any member that is not an existing login name with HTTP 400, and a user entry in an ACL matches only an authenticated session whose login is exactly that name. Role and group entries in an ACL are matched by name.

### Who counts as an administrator

Administrators are the members of the `Admin` group. A row in the `roles` table alone is not enough: the account authenticates, but `/admin/*` answers 403. To give someone one admin area without full rights, add a scoped `admin` policy grant instead of putting them in the `Admin` group.

### Authentication

Local accounts live in PostgreSQL and are verified through the JAAS `UserDatabaseLoginModule`, with bcrypt hashes and a NIST 800-63B-style length and blocklist policy. Single sign-on is optional and additive: OpenID Connect or SAML 2.0 through pac4j, configured with `wikantik.sso.*`, linking accounts on the immutable `sub` claim by default (`wikantik.sso.identityClaim`). SCIM 2.0 at `/scim/v2/*` lets an identity provider create and deactivate accounts. Wikantik ships no LDAP login module; put an LDAP-capable identity provider in front and use SSO, or supply your own JAAS module through `wikantik.loginModule.class`.

### Agents and API keys

Programmatic clients use database-backed bearer API keys. A key has a scope rather than page permissions: `mcp_read` (the read-only `/knowledge-mcp` endpoint), `mcp_admin` (the fully privileged `/wikantik-admin-mcp` endpoint as well), `tools` (`/tools/*`) or `all`. Any logged-in user can mint their own `mcp_read` or `tools` key under **Preferences → API Keys**; `mcp_admin` and `all` keys come from an administrator at `/admin/apikeys`. Key issuance, grant changes and sign-ins are recorded in the hash-chained audit log at `/admin/audit`. See [Connecting Claude Code to the Wikantik MCP Servers](ConnectingClaudeCodeToWikantikMcp) for the agent-side setup.

### View ACLs on the agent read path

A restricted page **never** appears in an agent's retrieved context, search
results, or a RAG context bundle for a caller who lacks `view` permission. View
ACLs are enforced on the **read path**, not just in the UI: every agent-facing
surface runs its candidate pages through a session **view gate**
(`PageViewGate`) before returning them —

- the REST read endpoints (`/api/*`, `/wiki/{slug}?format=md|json`),
- the `/knowledge-mcp` retrieval and Knowledge-Graph tools
  (`retrieve_context`, `assemble_bundle`, `search_knowledge`, `query_nodes`, …), and
- `/api/bundle` (the RAG context bundle).

So restricted content cannot leak into search, retrieval, or a context bundle
through the agent interface. (The public RDF/SPARQL surface applies the same
guest-view ACL split — only anonymously-viewable pages are materialized.)

On `/knowledge-mcp` the MCP transport carries no caller identity, so the gate evaluates view ACLs as an anonymous guest: only publicly viewable pages are returned, whichever account owns the key. An agent that must read restricted content has to use the privileged admin endpoint.

**Not to be confused with KG Inclusion Policy.** View-ACL read-path enforcement
is the *security* boundary and is always on. [KG Inclusion Policy](KgInclusionPolicy)
is a separate *curation* control that decides which (already-viewable) pages
contribute **entities** to the Knowledge Graph — it is **not** access control,
and its admin-bypass on KG reads is for curators only and never bypasses a
guest's view ACL. KG-including a page does not make a restricted page visible;
KG-excluding a page does not hide a viewable page from page search.

## Common failure patterns

### Excessive permissions

Everyone is admin "for convenience." Compromised account = compromised wiki.

### Permission sprawl

Per-page ACLs accumulate; no one knows who has access to what.

For wikis with thousands of pages, sprawl becomes unmanageable. Use group-based access where possible.

### Permission churn

Permissions changed frequently for ad-hoc needs. Hard to audit.

Standardize: groups for common needs; ACLs for exceptions.

### No audit trail

Permission changes happen; no record. Can't investigate after the fact.

### Stale permissions

Users left the company; still have access.

Auto-revoke on user deactivation; periodic review.

### Anonymous access

For public wikis; the right answer.
For internal wikis with sensitive content; the wrong answer. Verify what's intended.

## A reasonable starter

For new internal wikis:

1. SSO authentication (corporate identity)
2. Group-based authorization (LDAP or wiki groups matching org)
3. Default deny on sensitive sections; default read elsewhere
4. Per-page ACL only for exceptions
5. Quarterly access review
6. Audit logging enabled
7. Auto-revoke on user deactivation

For public wikis: anonymous read; authenticated edit; admin role for trusted users.

## Common questions

### Should viewers be able to edit?

Depends on culture. Wikipedia: yes, anyone edits. Internal corporate: usually no.

### Should there be unauthenticated access?

For public wikis: yes (the point).
For internal: rarely.

### Should authorship be public?

Per page: who edited what. Most wikis show this; some hide for privacy reasons.

### Permission to delete?

Often a separate, more restricted permission. Deletion is rarely needed; risky.

## Further Reading

- [WikiContentManagementWorkflow](WikiContentManagementWorkflow) — Editorial overlap
- [NetworkSecurityFundamentals](NetworkSecurityFundamentals) — Broader security
- [CloudSecurityFundamentals](CloudSecurityFundamentals) — Cloud-specific
