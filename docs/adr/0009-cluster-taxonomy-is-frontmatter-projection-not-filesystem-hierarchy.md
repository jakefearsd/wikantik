# Cluster taxonomy is a frontmatter projection, never a filesystem hierarchy

The content store stays **flat**. A page's position in the taxonomy is declared in
frontmatter and materialised by the structural index — it is never encoded as a directory
location. A cluster **exists if and only if** exactly one page declares it via the pair
`type: hub` + scalar `cluster: <path>`; every other page names that path to become a member.
Sub-clusters are written `parent/child` in the value, **one level, permanently**. Exactly one
condition blocks a write: two live pages declaring the same cluster path.

Rationale: the motivating defect was never flatness. The wiki carried **three** competing
membership mechanisms — `cluster:` (1,171 pages, validated), the undeclared list-valued
`hubs:` (473 pages, 89 of them already multi-membership, absent from `FrontmatterSchema`),
and hub `related:` (66 hubs) — with `HubSyncFilter` bidirectionally rewriting the latter two
on every save. They had fully diverged: `MLHub` published 11 `related` members against 44
actual cluster members, ten of the eleven not in the cluster; 46 `hubs:` entries pointed at
pages that never existed; 13 pages listed themselves. A directory tree would have been a
**fourth** mechanism, and the only one unable to express what 89 pages already assert. This
ADR consolidates to one: `cluster:` wins because every consumer already reads it, slug space
is rename-stable where page names are not, and it alone has schema, validation, editor, and
drift machinery. `hubs:` and `HubSyncFilter` are removed; hub `related:` is rescoped to
editorial highlights and `hasPart` is derived from real membership.

The distinction the decision turns on: **a path in frontmatter is data; a path in the
filesystem is a location.** Data can be validated, re-projected, multi-valued, and edited
through the page editor, `update_page`, ACLs, version history, and backlinks. A location
constrains page identity, `page_slug_history`, `OLD/`, `-att/` directories, URLs, and
wikilinks, and can hold exactly one value. Renaming a cluster therefore touches no page
identity — no `canonical_id` change, no slug-history row, no directory move, no redirect —
where the directory equivalent is an atomic migration across three parallel namespaces that
mutates identity for every page in the subtree.

Rejected: **directory-structured content storage** — flatness is enforced deliberately
(`AbstractFileProvider.mangleName()` percent-encodes `/`), routing truncates at the first
segment (`SpaRoutingFilter.extractPageName()`), it forecloses multi-membership that the
corpus already needs, and it buys retrieval nothing: partition-as-filter is measured
*negative* here (bundle recall@12 `0.500 → 0.706`, the largest step from **removing** page
pre-selection; the KG page-level rerank was deleted for zero lift).

Rejected: **a materialized read-only tree export as a stepping stone.** It cannot produce the
evidence it exists to gather — every cost of directory storage is on the write path, which a
read-only export never exercises, so its outcome is uncorrelated with the decision it would
inform. It would also acquire the constraints of a store once consumers depended on it, and
committing 1,193 duplicate files creates a machine-owned copy of human-owned pages with no
write-back path — the drift generator ADR-0004 exists to prevent. Not deferred: **not in the
plan, in any form.** Likewise cluster nesting deeper than one level.

Consequence discovered while migrating (2026-08-15): the repository corpus and the production page
store are **different corpora, not two copies**. Production holds pages absent from
`docs/wikantik-pages/` — including the hub declaring production's `computer-science` cluster — so a
corpus-wide plan derived from the checkout is wrong for production, and `bin/remote.sh pages-pull`
cannot reconcile them (it fails `Permission denied` on container-owned pages and silently returns a
partial corpus). Production is therefore **authoritative for content**; the checkout is a mirror and
the local-development corpus. Corpus-wide changes are derived from the live structural index, never
from the checkout.

Design detail, measured evidence, and the seven-phase migration: `ClusterDeclarationDesign`
(`docs/wikantik-pages/ClusterDeclarationDesign.md`).
