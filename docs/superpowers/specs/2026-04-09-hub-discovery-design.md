# Hub Discovery (Cluster-Based) Design

**Date:** 2026-04-09
**Status:** Approved

## Problem

The existing `HubProposalService` answers the question "which existing hub should this orphan page belong to?" by scoring non-member pages against pre-existing hub centroids. It cannot answer the inverse question: **"what hubs don't exist yet that ought to?"** Pages that share a strong topical theme but that theme has no hub go unrecognized — there is no tooling for an admin to discover latent clusters and stand up new hub pages around them.

The wiki currently has 739 pages, of which a meaningful fraction are non-members of any hub. Browsing that residual set to spot themes by hand is impractical.

## Goals

1. Discover coherent clusters of non-member pages using their pre-computed TF-IDF content embeddings.
2. Present each discovered cluster to the admin as a reviewable proposal with a sensible default hub name, an editable member list, and a coherence score.
3. On accept, create a stub wiki page with frontmatter that triggers the existing save pipeline to project the hub node and membership edges into the knowledge graph automatically.
4. Mirror the existing admin-UX patterns (review queue, per-proposal accept/dismiss) so the feature feels consistent with `HubProposalService`.
5. Keep the implementation narrow: no retraining triggers, no soft-merging with existing hubs, no automated summarization of the stub body.

## Non-Goals

- **Assigning existing pages to existing hubs.** That is what `HubProposalService` already does; this feature deliberately does not compete with it.
- **Clustering over all pages.** The candidate pool is strictly pages that are not currently an edge-member of any existing hub (pure gap-filling).
- **Rejection memory.** Dismissed clusters will be re-proposed on the next discovery run. We accepted this trade-off in favor of implementation simplicity.
- **Automated stub body authoring.** The stub contains a placeholder comment and a member list; the admin opens the page afterward to write the actual description.
- **A coherence-threshold filter.** HDBSCAN's stability-based selection already filters weak clusters; the admin sees a coherence score per card and exercises judgment.

## Architecture

The feature adds a `HubDiscoveryService` that sits alongside `HubProposalService` in `com.wikantik.knowledge`. Both services read from the same `ContentEmbeddingRepository` and write proposals to their own tables. The knowledge-graph projection of accepted hubs reuses the existing `GraphProjector` save-filter pipeline — no new filter classes.

```
POST /admin/knowledge/hub-discovery/run
  |
  v
HubDiscoveryService.generateClusterProposals
  |
  +--> ContentEmbeddingRepository.loadLatestModel  (TF-IDF model)
  +--> JdbcKnowledgeRepository                     (candidate pool)
  +--> SmileHdbscanClusterer                       (Smile library HDBSCAN)
  +--> HubDiscoveryRepository.insert(proposal)

POST /admin/knowledge/hub-discovery/proposals/{id}/accept
  |
  v
HubDiscoveryService.acceptProposal
  |
  +--> PageSaveHelper.saveText(stubName, stubMarkdown, options)
  |         |
  |         v
  |      GraphProjector.postSave (existing PageFilter)
  |         - upserts kg_node(type=hub) from frontmatter 'type'
  |         - upserts 'related' edges from frontmatter 'related' list
  |
  +--> HubDiscoveryRepository.delete(id)
```

Separation of responsibilities:

- **`HubDiscoveryService`** — orchestrator. Runs HDBSCAN, computes exemplars, writes proposals, handles accept/dismiss workflows. Mirrors the builder pattern from the recently refactored `HubProposalService`.
- **`HubDiscoveryRepository`** — JDBC CRUD over `hub_discovery_proposals`. Thin, mirrors `HubProposalRepository`.
- **`SmileHdbscanClusterer`** — thin wrapper over `smile.clustering.HDBSCAN`. Isolates the Smile dependency behind a minimal interface so it is mockable in tests and easy to swap later.
- **`AdminHubDiscoveryResource`** — REST endpoints under `/admin/knowledge/hub-discovery`. Same base class and auth filter as `AdminKnowledgeResource`.
- **React admin page** — new `/admin/hub-discovery` route in the SPA. Single-column review queue with editable name field, member checkboxes, and accept/dismiss buttons per card.

## Candidate Pool

Discovery runs only over pages that are *not* currently an edge-member of any existing hub. The query:

1. Load all `kg_nodes` with `type='article'`.
2. Load all `kg_edges` with `relationship_type='related'` and where the source is a hub node (`type='hub'`).
3. Candidate pool = articles minus any article that is the target of such an edge.

Pages that exist as articles but have no TF-IDF vector (newly created, not yet embedded by the most recent training pass) are dropped from the pool with a single `LOG.debug` counting the drops. They will become eligible once the next retrain pass completes.

## Clustering

**Library:** [Smile](https://haifengl.github.io/) (`com.github.haifengl:smile-core`, Apache 2.0), added as a new dependency on `wikantik-main`. Smile provides a tested HDBSCAN implementation. No hand-rolled clustering code.

**Parameters:**

| Property | Default | Meaning |
|---|---|---|
| `wikantik.hub.discovery.minClusterSize` | 3 | Smallest allowed cluster; groups below this are labeled noise. |
| `wikantik.hub.discovery.minPts` | 3 | HDBSCAN density parameter for core points. |
| `wikantik.hub.discovery.minCandidatePool` | 6 | Safety floor: skip the run entirely if fewer non-members exist. |

Wrapped behind `SmileHdbscanClusterer` so the service layer passes `float[][] vectors, int minClusterSize, int minPts` and receives `int[] labels` (-1 = noise). Three unit tests on the wrapper protect us from Smile behavior changes across dependency upgrades.

**Distance:** Because `TfidfModel` vectors are L2-normalized (see `TfidfModel.normalize`), Euclidean distance in 512-dim space is monotonic with cosine distance. Spherical semantics fall out for free; no custom metric needed.

**Per-cluster post-processing:**

For each non-noise cluster label, compute:

1. Centroid = arithmetic mean of member vectors, then L2-normalized.
2. Exemplar = member whose dot product with the centroid is highest (the most representative page).
3. Coherence score = mean dot product of members with the centroid. Range 0.0–1.0 for normalized vectors.

The exemplar's page name seeds the proposal's default hub name. The coherence score is stored per proposal and displayed in the review UI.

**Safety guards:**

- No content model loaded yet → zero-count summary, `LOG.info "Hub discovery skipped: no content model available"`.
- Candidate pool smaller than `minCandidatePool` → zero-count summary, `LOG.info "Hub discovery skipped: candidate pool too small ({})"`.
- All candidates classified as noise → zero proposals, log the noise count at `INFO`, return success.

## Database Schema

One new table plus a migration as the next `V<NNN>__hub_discovery_proposals.sql` under `wikantik-war/src/main/config/db/migrations/`.

```sql
CREATE TABLE IF NOT EXISTS hub_discovery_proposals (
    id              SERIAL PRIMARY KEY,
    suggested_name  TEXT             NOT NULL,
    exemplar_page   TEXT             NOT NULL,
    member_pages    JSONB            NOT NULL,
    coherence_score DOUBLE PRECISION NOT NULL,
    created         TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_created
    ON hub_discovery_proposals ( created DESC );

GRANT SELECT, INSERT, UPDATE, DELETE ON hub_discovery_proposals TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE hub_discovery_proposals_id_seq TO :app_user;
```

**Deliberate omissions:**

- No `status` column. Accept and dismiss both delete the row. A row's existence means "pending review"; nothing else needs representation.
- No `reviewed_by` / `reviewed_at`. There is no row left to record them on.
- No foreign keys from `member_pages` entries to `kg_nodes.name`. Pages may be renamed or deleted between discovery and review; the accept flow handles that by dropping missing members at runtime.
- No `noise_count` denormalization (considered, then removed as scope creep).

**Column rationale:**

- `suggested_name` — the exemplar page name at discovery time; shown as the default in the review UI and used to compute collision defaults.
- `exemplar_page` — stored separately from `suggested_name` because the admin can edit the name; the original exemplar stays visible in the UI as context.
- `member_pages` — JSONB array of page names. JSONB so future queries can use `jsonb_array_length` or `?` operators if needed.
- `coherence_score` — mean dot product of members with the centroid (0.0–1.0). Used for display; not used for filtering.
- `created` — freshness sorting (`ORDER BY created DESC`) and the only index key, since pending queues are small.

## Stub Page Template

Assembled by a private `HubDiscoveryService.renderStub(String hubName, List<String> members)`:

```markdown
---
title: JavaHub
type: hub
auto-generated: true
related:
  - Java
  - Kotlin
  - Scala
---

# JavaHub

<!-- TODO: describe this hub -->

## Members

- [Java](Java)
- [Kotlin](Kotlin)
- [Scala](Scala)
```

**Design notes:**

- `type: hub` is in `PROPERTY_ONLY_KEYS`, so `FrontmatterRelationshipDetector` stores it as a node property. `GraphProjector.projectPage` reads `properties.get("type")` and sets `nodeType='hub'` on the upsert — the same path hand-authored hubs take.
- `related: [...]` is a `List<String>` under a key not in `PROPERTY_ONLY_KEYS`, so the detector treats it as a relationship list and the projector creates one `related` edge per target. No explicit `kgRepo.upsertEdge` call is needed in the accept flow — the save pipeline handles it.
- `auto-generated: true` is another property-only key. Cheap flag that lets the admin later query "which hubs were created by discovery" for cleanup or sanity checks. Trivially removed by manual edit if the admin wants to.
- The H1 title matches the wiki's markdown conventions.
- `<!-- TODO: describe this hub -->` is an HTML comment — invisible in the rendered page but visible in the editor, so the admin is nudged to write a description.
- Body wiki-links produce `links_to` edges alongside the `related` ones. These are distinct relationship types, not duplicates; `related` is the canonical hub-membership signal that `HubSyncFilter` watches.
- Members are listed alphabetically in the body. The exemplar does not get special positioning; its role was only to seed the default name.

## REST API

All endpoints live on `AdminHubDiscoveryResource` under `/admin/knowledge/hub-discovery`, gated by the existing `AdminAuthFilter` (`AllPermission` required). Request and response bodies are JSON via Gson, consistent with `AdminKnowledgeResource`. The accept request uses a dedicated DTO class (no ad-hoc `JsonObject` parsing).

### `POST /run`

- Body: none or `{}`.
- Response `200`:
  ```json
  { "proposalsCreated": 5, "candidatePoolSize": 142, "noisePages": 37, "durationMs": 814 }
  ```
- Side effects: runs HDBSCAN, inserts proposals. Does **not** clear existing pending rows — runs are append-only.
- Log (INFO): `"Hub discovery run: created {} proposals from {} candidates ({} noise) in {} ms"`.

### `GET /proposals?limit=50&offset=0`

- Response `200`:
  ```json
  {
    "proposals": [
      {
        "id": 17,
        "suggestedName": "JavaHub",
        "exemplarPage": "Java",
        "memberPages": ["Java", "Kotlin", "Scala"],
        "coherenceScore": 0.84,
        "created": "2026-04-09T14:23:11Z"
      }
    ],
    "total": 1
  }
  ```
- Ordered by `created DESC`. `limit` capped at 200 server-side.

### `POST /proposals/{id}/accept`

- Body DTO `AcceptProposalRequest`:
  ```json
  { "name": "JavaHub", "members": ["Java", "Kotlin", "Scala"] }
  ```
- Response `200`: `{ "createdPage": "JavaHub", "members": 3 }`.
- Errors:
  - `404` — proposal not found (already accepted, dismissed, or never existed).
  - `409` — name collides with an existing wiki page, or fewer than 2 of the submitted members still exist as wiki pages.
  - `400` — malformed body, empty name, or submitted member not in the original proposal's stored list.
- Log (INFO): `"Hub discovery: accepted proposal {} as '{}' with {} members (reviewed by {})"`.

### `POST /proposals/{id}/dismiss`

- Body: none.
- Response `204`.
- Errors: `404` if proposal missing.
- Log (INFO): `"Hub discovery: dismissed proposal {} ('{}', {} members, reviewed by {})"`.

## Accept Flow Details

1. Load proposal by id. 404 if missing.
2. Validate the edited name: non-empty after trim. If a wiki page with that name already exists → 409 with the colliding name in the error message.
3. Validate the submitted member list: each name must appear in the proposal's stored `member_pages` array. 400 otherwise. (Prevents injection of arbitrary pages on accept.)
4. Drop any submitted members whose wiki pages no longer exist. Log `LOG.info "Hub discovery: dropping missing member '{}' from accepted proposal {}"` per drop. If fewer than 2 members survive → 409 "too few surviving members, dismiss instead."
5. `renderStub(editedName, survivingMembers)` → markdown string.
6. `pageSaveHelper.saveText(editedName, markdown, SaveOptions.builder().changeNote("Hub discovery: stub created").build())` — `GraphProjector.postSave` fires automatically and projects the hub node plus `related` edges.
7. `hubDiscoveryRepo.delete(id)` — purge the proposal row.
8. Return `200 { createdPage, members }`.

## Dismiss Flow Details

1. Load proposal by id. 404 if missing.
2. `hubDiscoveryRepo.delete(id)`.
3. Return `204`.

## Admin UI

New React page at `/admin/hub-discovery`, registered alongside existing knowledge-graph admin routes.

**Layout:** single scrolling list of proposal cards, newest first. A top bar holds the "Run Discovery" button and a pending count.

**Per-card UI:**

- Editable text input pre-filled with `suggestedName`.
- Exemplar page name and coherence score shown as subtitles.
- Member checkboxes pre-checked for every entry in `memberPages`. Admin can uncheck members they don't want included.
- "Accept" button posts `{ name, members }` to the accept endpoint.
- "Dismiss" button posts to the dismiss endpoint.

**Behaviors:**

- "Run Discovery" shows an inline spinner, then a toast with the run summary. List refreshes after the run completes.
- Each card is a self-contained form. Name edits and checkbox toggles are local state until Accept.
- Accept removes the card from the list on success. Collisions surface as an error toast with the message from the 409 response so the admin can correct the name inline without losing card state.
- Dismiss removes the card optimistically and rolls back on error.
- `data-testid` attributes on every interactive element so Selenide page objects have stable selectors.

**Deliberately out of scope:** history of accepted/dismissed proposals (there is none — rows are deleted), cluster-size filtering, bulk accept/dismiss, sorting controls beyond newest-first.

## Concurrency Model

- **Two concurrent `POST /run` calls** — allowed to race. Both insert proposal rows; `id` primary key prevents collision. Duplicates are resolved by the admin via dismiss.
- **Concurrent accept on the same proposal id** — first `DELETE FROM hub_discovery_proposals WHERE id = ?` wins. The loser's delete is a no-op and its subsequent `saveText` either succeeds (if the winner used a different name) or returns 409 on page collision. Acceptable outcome.
- **Concurrent accept + dismiss on the same proposal** — first delete wins; the other returns 404 on its load-proposal step.
- **Not protected against:** two admins crafting identical name edits simultaneously, both colliding with different pre-existing pages. Both see independent 409s. Fine for a solo-dev tool.

No mutex, no row-level locking. The simplicity is deliberate.

## Testing Plan

### Unit tests — `SmileHdbscanClustererTest` (no DB)

- `cluster_findsTwoObviousGroups` — two tight groups of L2-normalized vectors + one outlier → two non-noise labels + one `-1`.
- `cluster_emptyInput_returnsEmpty` — empty `float[][]` → empty `int[]`, no exception.
- `cluster_belowMinClusterSize_allNoise` — 2 points with `minClusterSize=3` → both `-1`.

Purpose: protect against Smile behavior changes on dependency upgrade.

### Integration tests — `HubDiscoveryRepositoryTest` (`PostgresTestContainer`)

- `insert_thenList_returnsRow` — including JSONB round-trip of member names with characters like `"O'Brien"`.
- `delete_removesRow`.
- `delete_missingId_isNoop`.
- `list_orderedByCreatedDesc`.
- `list_respectsLimit`.

### Integration tests — `HubDiscoveryServiceTest` (`PostgresTestContainer`)

Mirrors `HubProposalServiceTest` setup: real repos, real content model, wipe all relevant tables in `@BeforeEach`.

- `generateClusterProposals_findsClusterOfNonMembers` — seed existing hub members + two non-member clusters (programming languages and hobbies). Assert: two proposals, membership correct, no hub members included.
- `generateClusterProposals_exemplarIsClosestToCentroid`.
- `generateClusterProposals_emptyCorpus_noProposals`.
- `generateClusterProposals_tinyCorpus_noProposals` — exercises the `minCandidatePool` guard.
- `acceptProposal_createsStubPageAndEdges` — the load-bearing test proving the save-pipeline-does-the-edges assumption. Asserts stub page exists, `kg_node` has `type=hub`, three `related` edges exist, proposal row is deleted.
- `acceptProposal_collisionWithExistingPage_throwsHubNameCollisionException` — proposal row is *not* deleted.
- `acceptProposal_memberNotInProposal_throwsIllegalArgumentException` — proposal intact.
- `acceptProposal_missingMemberPagesDroppedWithLog` — stub created with surviving members only, proposal deleted.
- `acceptProposal_allMembersMissing_throwsException` — proposal intact.
- `dismissProposal_deletesRow`.
- `dismissProposal_missingId_throwsException` — not a silent no-op.

Target coverage: 90%+ for the new classes, consistent with the project goal in `CLAUDE.md`.

### Unit tests — `AdminHubDiscoveryResourceTest` (Mockito)

Mock `HubDiscoveryService`. For each endpoint, assert correct body parsing (including DTO deserialization), correct HTTP status mapping (404/409/400/500), the expected `LOG.info` lines, and the `limit` cap on `GET /proposals`. One test per status code per endpoint plus a happy-path test each (~12 tests total).

### Browser integration tests — `HubDiscoveryAdminIT` (Selenide)

Location: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java`. Extends `WithIntegrationTestSetup`.

Introduces the first Selenide page object under `com.wikantik.pages.admin.HubDiscoveryAdminPage`. Locators use `data-testid` attributes attached to the React components during UI implementation.

A REST-API seeding helper in the same module POSTs directly to the backend to prepare corpus state (pages, embeddings, proposals) without going through the browser, keeping each test under 15 seconds.

Three end-to-end tests:

1. **`runAcceptFlow_happyPath`** — seeds programming-language pages via REST (3 already edge-members of a pre-existing `TechHub`, 3 non-members). Admin opens `/admin/hub-discovery`, clicks "Run Discovery", waits for the success toast and a proposal card, clicks Accept without edits, waits for the card to disappear, navigates to the new stub URL, and asserts the page has the expected title, `<!-- TODO: describe this hub -->` comment, and member links. Covers React → REST → DB → graph projection → rendered page.
2. **`acceptCollisionShowsInlineError`** — pre-creates a wiki page via REST, edits a card's name to collide, clicks Accept, asserts the error toast contains the collision message, asserts the card is still present, asserts the pre-existing page is unchanged. Covers 409 error-path rendering.
3. **`dismissRemovesCard`** — seeds a proposal directly via REST, clicks Dismiss, asserts the card disappears and the list endpoint returns empty.

Per `CLAUDE.md`, ITs must run via `mvn clean install -Pintegration-tests -fae` without parallel flags.

### Out of scope for testing

- Smile HDBSCAN correctness in depth (trusted library).
- Member deselection edge cases (covered by unit/integration tests on the service layer).
- Concurrent discovery runs (no mutex, acceptable duplicate behavior).
- Migration idempotency per-file (covered by the `IF NOT EXISTS` conventions).

## Configuration

Added to `wikantik.properties` with commented overrides in the custom properties template:

```properties
# Minimum number of members for HDBSCAN to form a cluster.
# Below this, points are classified as noise.
wikantik.hub.discovery.minClusterSize = 3

# HDBSCAN minPts: density requirement for "core" points.
wikantik.hub.discovery.minPts = 3

# Safety floor: if the candidate pool has fewer non-member
# articles than this, skip the run entirely.
wikantik.hub.discovery.minCandidatePool = 6
```

All three properties are optional; defaults live in `HubDiscoveryService`.

## Logging Summary

All accept/dismiss/run lifecycle events emit at `LOG.info` (per an explicit decision during brainstorming — the admin tools' history of bugs makes a verbose audit trail valuable):

- `"Hub discovery run started"`
- `"Hub discovery run: created {} proposals from {} candidates ({} noise) in {} ms"`
- `"Hub discovery skipped: {reason}"`
- `"Hub discovery: accepted proposal {} as '{}' with {} members (reviewed by {})"`
- `"Hub discovery: dismissed proposal {} ('{}', {} members, reviewed by {})"`
- `"Hub discovery: dropping missing member '{}' from accepted proposal {}"`

Repository SQL errors and save-pipeline failures log at `LOG.warn` with the full exception, matching the rest of the knowledge package.

## Dependency Changes

- **New:** `com.github.haifengl:smile-core` in `wikantik-main/pom.xml` (latest stable, Apache 2.0). Transitive footprint verified during implementation.
- **No other dependency changes.** Everything else reuses existing libraries (Gson, pgvector, Lucene via `TfidfModel`, JUnit, Mockito, Selenide).

## Wiring Changes

- `KnowledgeGraphServiceFactory.create(...)` — extended to construct `HubDiscoveryRepository` and `HubDiscoveryService`, added to the `Services` record.
- `WikiEngine.initKnowledgeGraph` — registers the two new services in the managers map (no additional filter wiring; the save pipeline is reused unchanged).
- `AdminHubDiscoveryResource` — registered in the REST servlet config alongside `AdminKnowledgeResource`.
- React admin router — new `/admin/hub-discovery` route.
- Migration — one new numbered file under `wikantik-war/src/main/config/db/migrations/`, applied automatically by `deploy-local.sh` on every deploy.

## Open Questions (Deferred to Implementation)

- Exact Smile version to pin. Latest stable at implementation time.
- Exact `V<NNN>__` migration number — determined when the implementation commit lands.
- React component reuse: whether the proposal-card layout can share components with the existing `HubProposalService` review queue. Investigated during UI implementation.
