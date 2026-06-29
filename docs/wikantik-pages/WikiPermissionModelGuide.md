---
canonical_id: 01KQ0P44Z4JH82M2KYBEME5M2H
title: Wiki Permission Model Guide
type: article
cluster: wikantik-development
status: active
date: '2026-04-26'
summary: How wiki permission models work — role-based, ACL-based, group-based — and
  the patterns for managing access at scale without making every page require approval.
tags:
- wiki
- permissions
- access-control
- wikantik-development
related:
- WikiContentManagementWorkflow
- NetworkSecurityFundamentals
- CloudSecurityFundamentals
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
- Database-backed policy grants for default role permissions (via `/admin/security` UI)
- Database-backed groups via `/admin/groups`
- Page-level ACLs via inline `[{ALLOW view Admin}]` syntax
- REST API permission enforcement via `RestServletBase.checkPagePermission()`
- Bootstrap admin override for initial setup

The model is hybrid — role-based default with per-page ACL override.

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
