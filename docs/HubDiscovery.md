# Hub Discovery

Hub Discovery finds clusters of related orphan articles and proposes new **Hub**
pages to tie them together — a Page-Graph curation aid, not the Knowledge Graph
itself, though it is built on the same mention-embedding data the Knowledge Graph
maintains.

This document covers the operator surface: what a discovery run does, how the
clustering parameters shape proposals, the accept/dismiss workflow, and
troubleshooting.

## Table of contents

1. [How it works](#how-it-works)
2. [Clustering parameters](#clustering-parameters)
3. [Existing-hub health (Hub Overview)](#existing-hub-health-hub-overview)
4. [Admin UI walkthrough](#admin-ui-walkthrough)
5. [REST endpoint reference](#rest-endpoint-reference)
6. [Auth model](#auth-model)
7. [Configuration reference](#configuration-reference)
8. [Troubleshooting](#troubleshooting)
9. [Cross-links](#cross-links)

---

## How it works

`HubDiscoveryService.runDiscovery()` is a batch job (triggered on demand from the
admin UI — there is no nightly scheduler for this one) that:

1. Loads the **candidate pool**: article-typed Knowledge Graph nodes that are
   *not* already a `related`-edge target of any hub-typed node, and that have at
   least one mention with a chunk embedding (i.e. something to cluster on).
2. Retrieves each candidate's centroid vector from the shared mention-embedding
   index (`NodeMentionSimilarity`).
3. Runs **HDBSCAN** (density-based clustering) over those vectors.
4. For each non-noise cluster, computes a normalised centroid, picks the
   **exemplar** (the member whose vector has the highest dot-product with the
   centroid — used as the placeholder suggested name, `"<exemplar> Hub"`), and
   scores **coherence** as the mean dot-product of all members with the centroid.
5. Writes one row to `hub_discovery_proposals` per cluster, **skipping** any
   cluster whose sorted member set exactly matches a previously-dismissed
   proposal (so dismissing a cluster sticks across re-runs).

Points HDBSCAN can't confidently assign to any cluster are **noise** — they are
counted (`noisePages` in the run summary) but produce no proposal.

**Short-circuit conditions** (logged at INFO, the run returns a zero-filled
summary rather than erroring):

- The mention-embedding index isn't ready yet (no embeddings indexed).
- No mentioned entities have embeddings.
- The candidate pool is smaller than `minCandidatePool`.

## Clustering parameters

Hub Discovery uses HDBSCAN, not k-means — you don't tell it how many clusters to
find; you tell it how dense a group of points has to be before it counts as one.
Two parameters drive that:

- **`minClusterSize`** — the minimum number of members for HDBSCAN to form a
  cluster at all. Below this, the points involved are classified as noise
  instead. Raise it to demand larger, more obviously-coherent hub candidates;
  lower it to surface smaller, more speculative groupings.
- **`minPts`** — HDBSCAN's density parameter: how many neighbours a point needs
  within its neighbourhood to count as a "core" point that can anchor a cluster.
  Raising it makes the algorithm pickier about what counts as a dense region,
  which tends to produce fewer, tighter clusters and more noise.

Both default to `3`. There is no "right" value — treat a first run's proposal
count and coherence scores as the tuning signal: too many low-coherence
proposals with barely-related members means the parameters are too loose;
zero proposals against a reasonably large corpus means they're too strict (or
the candidate pool is genuinely too small — see `minCandidatePool` below).

**`minCandidatePool`** is a safety floor, not a clustering parameter: if the
candidate pool (non-hub, non-hub-member articles with embeddings) has fewer
members than this, the run short-circuits without attempting to cluster at all.
Default `6` — clustering fewer points than that produces noise, not signal.

## Existing-hub health (Hub Overview)

A companion read-only service, `HubOverviewService`, backs the **Existing Hubs**
panel — statistics for hubs that have *already* been accepted, computed live from
the current KG snapshot and mention-embedding vectors (nothing persisted, no
caching). Per hub it surfaces:

- **Members** — current member pages, each with `cosineToCentroid` and whether
  a backing page still exists (a member can go stub if its page was deleted).
- **Near-miss candidates (TF-IDF)** — non-member articles whose centroid cosine
  to the hub's centroid is at or above `nearMissThreshold` — pages that look
  like they *should* have been in this hub.
- **More-like-this (Lucene)** — a complementary lexical-similarity list from a
  Lucene MoreLikeThis query seeded on the hub's content, capped at
  `moreLikeThisMaxResults`.
- **Overlapping hubs** — other hubs whose centroid cosine to this one is at or
  above `overlapThreshold`, with the shared-member count — a signal that two
  hubs may want to be merged.

The drilldown also supports **removing a member**: it edits the hub page's
`related:` frontmatter and saves it back (`HubSyncFilter` reconciles the
underlying KG edges on save). Removing a member below 2 remaining members is
refused (`409` — dismiss/delete the hub itself instead).

## Admin UI walkthrough

**Route:** `/admin/knowledge-graph` → **Hub Discovery** tab (`HubDiscoveryTab`),
alongside Proposals, Extraction, Node/Edge Explorer, Content Embeddings, Hub
Proposals, and LLM Activity (see [NewUI.md](NewUI.md)).

- **Run discovery** — triggers a batch run; the resulting proposal cards appear
  once the run completes (synchronous from the UI's perspective).
- **Proposal cards** — each shows the suggested name, exemplar page, member
  list, and coherence score. **Accept** opens an edit step (rename the hub,
  drop individual members before creating the page) before writing the stub
  hub page; **Dismiss** marks the row `dismissed` so the same cluster won't be
  re-proposed.
- **Dismissed proposals** — a separate view lists previously-dismissed clusters;
  delete one (individually or in bulk) to permanently clear the dismissal and
  allow that exact cluster to be re-proposed on a future run.
- **Existing hub memberships** — the Hub Overview panel described above, with a
  per-member **remove** action.

Accepting a proposal writes a minimal stub page (`type: hub`, `auto-generated:
true`, a `related:` list of members) — it is meant as a starting point for a
human to flesh out, not a finished article.

## REST endpoint reference

Mapped to `/admin/knowledge-graph/hub-discovery/*` (`AdminHubDiscoveryResource`).
Protected by `AdminAuthFilter` (`Admin` role via `AllPermission`) like the rest of
`/admin/*`. Cross-origin requests are not allowed.

Every handler first checks `wikantik.knowledge.enabled` (default `true`) and
returns `503` naming the flag if the Knowledge Graph subsystem is disabled —
see [Troubleshooting](#troubleshooting). Per-handler `503`s below (e.g.
`HubDiscoveryRepository is not available`) cover the narrower case where the
subsystem is enabled but a specific dependency failed to wire.

| Method & path | Purpose |
|---|---|
| `POST /run` | Trigger a discovery run. Returns the `RunSummary` (`proposalsCreated`, `candidatePoolSize`, `noisePages`, `skippedDismissed`, `durationMs`). `500` on an unexpected clustering failure. |
| `GET /proposals?limit=&offset=` | List pending proposals, newest-first. `limit` defaults to 50, clamped to 1–200. |
| `POST /proposals/{id}/accept` | Body `{"name": "...", "members": ["A","B"]}`. Writes the hub stub page and deletes the proposal row. `404` unknown id, `409` on name collision with an existing page, `400` on an empty name or a member not in the original proposal. |
| `POST /proposals/{id}/dismiss` | Marks the proposal `dismissed`. `204` on success, `404` if not pending (unknown or already dismissed). |
| `GET /proposals/dismissed?limit=&offset=` | List dismissed proposals with `reviewedBy`/`reviewedAt`. |
| `DELETE /proposals/dismissed/{id}` | Permanently delete one dismissed row (re-enables rediscovery of that exact cluster). `204` / `404`. |
| `POST /proposals/dismissed/bulk-delete` | Body `{"ids": [1,2,3]}`, up to 500 ids. Returns `{"deleted": N}`. |
| `GET /hubs` | List existing (already-accepted) hubs with health stats — the Hub Overview panel's data source. |
| `GET /hubs/{name}` | Drilldown for one hub: members, near-miss/MoreLikeThis candidates, overlapping hubs. URL-decoded name; `404` if not found. |
| `POST /hubs/{name}/remove-member` | Body `{"member": "PageName"}`. `400` bad request, `404` hub/member not found, `409` if fewer than 2 members would remain. |

`POST /proposals/seed` exists for integration tests only — it is gated behind
`-Dwikantik.test.fixture-seam.enabled=true` and returns `403` in any other
configuration; it is not a production surface.

## Auth model

Same as the rest of `/admin/*`: `AdminAuthFilter` enforces `AllPermission`. No
finer-grained access — any admin can trigger runs, accept/dismiss proposals, and
edit hub membership.

## Configuration reference

All keys live in `ini/wikantik.properties`; override in
`wikantik-custom.properties`. Defaults shown.

| Property | Default | Effect |
|---|---|---|
| `wikantik.hub.discovery.minClusterSize` | `3` | Minimum members for HDBSCAN to form a cluster; below this, points are noise. |
| `wikantik.hub.discovery.minPts` | `3` | HDBSCAN density requirement for a "core" point. |
| `wikantik.hub.discovery.minCandidatePool` | `6` | Safety floor — skip the run entirely if the candidate pool is smaller than this. |
| `wikantik.hub.overview.nearMissThreshold` | `0.50` | Minimum centroid cosine for a non-member article to count as a near-miss in a hub's drilldown. |
| `wikantik.hub.overview.overlapThreshold` | `0.60` | Minimum centroid cosine for two hubs to be flagged as overlapping. |
| `wikantik.hub.overview.nearMissMaxResults` | `10` | Cap on the TF-IDF near-miss list per drilldown. |
| `wikantik.hub.overview.moreLikeThisMaxResults` | `10` | Cap on the Lucene MoreLikeThis list per drilldown. |
| `wikantik.knowledge.enabled` | `true` | Master flag for the whole Knowledge Graph subsystem — every hub-discovery endpoint 503s (naming the flag) when this is `false`. |

## Troubleshooting

**Every `/admin/knowledge-graph/hub-discovery/*` call returns `503` naming
`wikantik.knowledge.enabled`**

The Knowledge Graph subsystem is disabled by config. Hub Discovery is built
entirely on KG data (mention embeddings, KG nodes/edges), so it has no
independent existence — set `wikantik.knowledge.enabled=true` (the default) and
restart.

**`503` `"HubDiscoveryService is not available"` / `"HubDiscoveryRepository is
not available"` / `"HubOverviewService is not available"` (with
`wikantik.knowledge.enabled` true)**

The subsystem is enabled but a specific dependency failed to wire at startup —
check `catalina.out` for Knowledge Graph subsystem construction errors (missing
JNDI DataSource, pgvector unavailable, migrations not applied).

**A discovery run always returns `proposalsCreated: 0`**

Check the run summary's other fields:
- `candidatePoolSize` below `minCandidatePool` → not enough eligible articles
  yet (either the corpus is small, or most articles are already hub members).
  Lower `minCandidatePool`, or grow the corpus / embedding coverage.
- `candidatePoolSize` healthy but still zero proposals → `minClusterSize` /
  `minPts` are likely too strict for how tightly your content actually
  clusters. Try lowering both by 1 and re-running.
- Check `catalina.out` for `"skipped — chunk embeddings not yet indexed"` or
  `"skipped — no mentioned entities have embeddings yet"` — the mention
  embedding index (shared with the Knowledge Graph) hasn't caught up yet;
  re-run after the embedding backlog drains.

**Accepting a proposal returns `409`**

The edited hub name collides with an existing wiki page — pick a different
name. (This is a name collision, not a member-count problem; a
too-few-surviving-members failure is a distinct `HubDiscoveryException`, not
this exception type.)

**A discovery run keeps re-proposing a cluster I already dismissed**

The member set has to match **exactly** (same sorted member names) for the
dismissed-signature check to skip it. If even one member joined or left the
cluster since the dismissal, it's treated as a new proposal. If you actually
want the old dismissal gone, delete it from the dismissed list first — that's
the only way to re-enable proposing the exact same cluster again.

## Cross-links

- [NewUI.md](NewUI.md) — the Knowledge Graph admin tab layout (`HubDiscoveryTab` among its siblings).
- [KgInclusionPolicy.md](KgInclusionPolicy.md) — which pages feed the Knowledge Graph (and therefore the candidate pool) in the first place.
- [PageGraphVsKnowledgeGraph.md](wikantik-pages/PageGraphVsKnowledgeGraph.md) — Hub Discovery proposes Page Graph structure (hub pages + `related` links) but is powered by Knowledge Graph mention data; read this if the distinction is unclear.
- `bin/db/migrations/V006__hub_discovery_proposals.sql` / `V007__hub_discovery_proposal_status.sql` — DDL for `hub_discovery_proposals`.
