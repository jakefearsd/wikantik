# Existing Hubs Panel — Design

**Date:** 2026-04-10
**Author:** Jake Fear (brainstormed with Claude)
**Status:** Approved — ready for plan
**Scope:** Add a read-focused "Existing Hubs" view to the Hub Discovery admin tab, with
per-hub drilldown, computed health statistics, and a single mutation (remove-member).

## Goal

Help an administrator understand the health of existing Hub pages at a glance and surface
the one action — pruning low-coherence members — that the statistics most directly
justify. This is an *inventory with drilldown detail* (not an action-centric dashboard and
not just a plain list).

## Non-Goals

- No schema migration — nothing persisted; all statistics are computed on demand.
- No "Add to hub" / grow-actions from the near-miss and MoreLikeThis lists. Those lists
  are read-only second opinions in the first cut.
- No cross-hub mutations (merge, split, move-member).
- No hub deletion from this panel. Deleting an orphaned `type=hub` KG node belongs on the
  Knowledge Graph admin.
- No paging, filtering, or client-side sorting. Current scale is ~10 hubs; the list
  renders in a single fetch, sorted server-side.

## User-Facing Behaviour

The Hub Discovery admin tab gains a new collapsible panel — `▸ Existing Hubs (N)` —
placed **above** the existing `Dismissed Proposals` panel, **collapsed by default**,
symmetric with the Dismissed panel's pattern.

Expanded, the panel shows a table of every `type=hub` KG node with four columns:

| Column            | Source                                                       |
|-------------------|--------------------------------------------------------------|
| Name              | `kg_nodes.name`, linked to the wiki page if it has one       |
| Members           | Distinct targets of `related` edges from this hub            |
| Inbound Links     | Distinct external pages linking into any member              |
| Near-Miss (TF-IDF)| Non-member articles above the centroid-cosine threshold     |

Orphaned hubs (`type=hub` KG node with no backing wiki page) are flagged with an "orphan"
badge and their name is not hyperlinked.

**Sort:** coherence ascending (worst first), alphabetical tiebreak. Coherence is not
displayed as a column, but drives the row order — the worst-coherence hubs sort to the
top so the admin sees pruning candidates first. Hubs with fewer than 2 model-backed
members have `coherence = NaN` and sort to the end.

Clicking a row toggles an inline drilldown beneath it. The drilldown has five sections:

1. **Members table** — each member with its TF-IDF cosine to the hub centroid, sorted
   ascending (worst first), and a `[Remove]` action per row. Disabled when only 2
   members remain. Stub members (referenced but no page) are flagged.
2. **Stub members callout** — warning-styled list of members that don't exist as wiki
   pages. Read-only. Section hidden when empty.
3. **Near-miss TF-IDF list** — top-N non-member articles whose cosine to the centroid
   is above the threshold. Read-only. Section hidden when empty.
4. **MoreLikeThis (Lucene) list** — top-N results from Lucene's `MoreLikeThis` over the
   hub page's `contents` (or the exemplar member if the hub has no backing page), with
   current members filtered out. Read-only. Section hidden when empty or when Lucene is
   unavailable.
5. **Overlap hubs** — other hubs whose centroid cosine to this hub is above the overlap
   threshold, with `sharedMemberCount`. Read-only. Section hidden when empty.

The only mutation is per-member removal: confirm modal → POST → optimistic row removal
from the drilldown → background drilldown refresh to pick up new coherence and
near-miss counts.

## Architecture

### Backend — new service `HubOverviewService`

Located at `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`.
Follows the same builder-pattern conventions as its sibling `HubDiscoveryService`.

**Collaborators (all injected via builder):**

- `JdbcKnowledgeRepository` — hub nodes, `related` edges, `links_to` edges
- `Supplier<TfidfModel>` — live content model (retrains picked up automatically)
- `PageManager` — read existing hub page text; `pageExists` for stub-member detection
- `PageWriter` (the functional interface declared in `HubDiscoveryService`) — save the
  mutated hub page on remove-member
- `LuceneSearchProvider` — MoreLikeThis query for the drilldown's secondary list

**Configurable properties** (all in `ini/wikantik.properties`, with sensible defaults):

| Property                                           | Default | Meaning                                           |
|----------------------------------------------------|---------|---------------------------------------------------|
| `wikantik.hub.overview.nearMissThreshold`          | `0.50`  | Min cosine for an article to count as near-miss   |
| `wikantik.hub.overview.overlapThreshold`           | `0.60`  | Min centroid cosine for two hubs to be "overlap"  |
| `wikantik.hub.overview.nearMissMaxResults`         | `10`    | Top-N near-miss articles in the drilldown list    |
| `wikantik.hub.overview.moreLikeThisMaxResults`     | `10`    | Top-N MoreLikeThis results in the drilldown list  |

**Public records:**

```java
public record HubOverviewSummary(
    String name, int memberCount, int inboundLinkCount,
    int nearMissCount, double coherence, boolean hasBackingPage
) {}

public record HubDrilldown(
    String name, boolean hasBackingPage, double coherence,
    List<MemberDetail> members,
    List<StubMember> stubMembers,
    List<NearMissTfidf> nearMissTfidf,
    List<MoreLikeThisLucene> moreLikeThisLucene,
    List<OverlapHub> overlapHubs
) {}

public record MemberDetail( String name, double cosineToCentroid, boolean hasPage ) {}
public record StubMember( String name ) {}
public record NearMissTfidf( String name, double cosineToCentroid ) {}
public record MoreLikeThisLucene( String name, double luceneScore ) {}
public record OverlapHub( String name, double centroidCosine, int sharedMemberCount ) {}

public record RemoveMemberResult( String removed, int remainingMemberCount ) {}
```

**New exception type** — `HubOverviewException` (same shape as `HubDiscoveryException`)
for REST-mapped service errors.

### Backend — `listHubOverviews()` algorithm

Runs synchronously on the request thread. One KG snapshot + one TF-IDF pass per call
(no caching, per design decision — revisit if the list ever measures slow).

1. Resolve the content model. If null or empty, return empty list with an INFO log.
2. Load all nodes: `kgRepo.queryNodes(null, null, 100_000, 0)`. Partition into `hubNodes`
   (where `properties.type == "hub"`) and all-node-names.
3. Load all `related` edges: `kgRepo.queryEdgesWithNames("related", null, 100_000, 0)`.
   Group by `source_name` into `Map<String, Set<String>> hubMembers`, using `Set` to
   distinct the targets.
4. Load all `links_to` edges: `kgRepo.queryEdgesWithNames("links_to", null, 100_000, 0)`.
   Index by target name for O(1) member lookup.
5. For each hub, compute centroid and coherence from the members that exist in the
   TF-IDF model. Reuse `normalizedCentroid` and `meanDot` from `HubDiscoveryService`
   (make them package-private visible). Hubs with fewer than 2 model-backed members
   get `coherence = NaN`.
6. For `nearMissCount`: one pass over the full non-member candidate pool, computing
   cosine to every hub centroid. For each (candidate, hub) pair where cosine ≥
   threshold, bump that hub's near-miss counter. O(candidates × hubs) dot products.
7. For `inboundLinkCount`: for each hub, the set of `links_to` source pages whose target
   is any of this hub's members, minus the hub itself, minus other members of the same
   hub. Set cardinality is the count.
8. `hasBackingPage` per hub via `pageManager.pageExists(name)`.
9. Sort by coherence ascending, name ascending as tiebreak. `NaN` coherences sort to
   the end (treated as greater than any finite double).

### Backend — `loadDrilldown(String hubName)` algorithm

Independent of the list — an admin can refresh a single row without reloading the panel.

1. Find the hub node: query `queryNodes` with filter `node_type = "hub"` and `name =
   hubName`. Return `null` if not found (REST layer maps to 404).
2. Load this hub's members from `related` edges with `source_name = hubName`. Distinct,
   sorted.
3. Partition members into existing pages and stubs via `pageManager.pageExists`.
4. Compute hub centroid from model-backed members (same helper as the list path).
5. Per-member cosine: dot each member's vector against the centroid. Sort ascending.
6. Near-miss TF-IDF list: score every non-member, non-hub article against the centroid,
   keep ≥ threshold, take top-N by cosine descending.
7. Overlap hubs: load all hub nodes and all `related` edges (a second pair of KG
   queries, scoped to the drilldown path — the list-view collaborator loads already
   happened on a different request). For every other hub, compute its centroid from
   its model-backed members, cosine against this hub's centroid, and keep those ≥
   `overlapThreshold`. `sharedMemberCount` is the intersection cardinality of the two
   hubs' `related`-edge target sets.
8. Lucene MoreLikeThis: seed doc is `hubName` if `hasBackingPage`, otherwise the
   exemplar (highest-cosine member). `LuceneSearchProvider` does not currently expose
   a MoreLikeThis method — add a new public method `moreLikeThis(seedDocName,
   maxResults, excludeNames)` as a thin wrapper around Lucene's `MoreLikeThis` class,
   querying the `contents` field and filtering `excludeNames` out of the hit list.
   Caller passes the hub's current members + the hub name as excludes. On any
   exception, the service logs at WARN and returns an empty list — the MLT list is a
   secondary opinion.

### Backend — `removeMember(hubName, member, reviewedBy)` algorithm

1. Validate `member` is non-null and non-blank.
2. Load the hub page text via `pageManager.getPageText(hubName, LATEST_VERSION)`. If
   the page doesn't exist → `HubOverviewException` → 404.
3. Parse via `FrontmatterParser.parse(text)` → `ParsedPage(metadata, body)`.
4. Assert `metadata.get("type") equals "hub"`, else `IllegalArgumentException` → 400.
   Prevents the endpoint from being used to mutate arbitrary pages.
5. Pull `related` from metadata, require it to be a `List<String>` containing `member`.
   If not present → `IllegalArgumentException` → 400.
6. Remove `member` from the list, preserving order of the remaining entries.
7. If fewer than 2 members remain → `HubOverviewException("...would leave hub with
   fewer than 2 members")` → 409. Admin should delete the hub instead.
8. Re-serialize via `FrontmatterWriter.write(metadata, body)`, then
   `pageWriter.write(hubName, newText)`. Wrap save failure in
   `HubOverviewException` → 500.
9. The existing `HubSyncFilter` on page save automatically diffs the `related` edges
   and drops the removed one from `kg_edges`. No direct KG mutation here.
10. Log INFO with `hubName`, removed `member`, reviewer, remaining count.

### Wiring — `KnowledgeGraphServiceFactory`

Add a `HubOverviewService` construction block adjacent to the existing
`HubDiscoveryService` block. Same content-model supplier, same `PageWriter` and
`pageExists` collaborators. Register with the engine so
`engine.getManager(HubOverviewService.class)` works from the REST layer.

### REST — `AdminHubDiscoveryResource` (extended)

Three new routes under the existing `/admin/knowledge/hub-discovery/*` prefix, all
protected by the same `AdminAuthFilter`:

| Method | Path                                           | Handler                |
|--------|------------------------------------------------|------------------------|
| GET    | `/hubs`                                        | `handleListHubs`       |
| GET    | `/hubs/{name}`                                 | `handleHubDrilldown`   |
| POST   | `/hubs/{name}/remove-member`                   | `handleRemoveMember`   |

Hub names in the path are URL-decoded via `URLDecoder.decode(segment,
StandardCharsets.UTF_8)`. Hub names contain `+` (the "SomeName+Hub" convention), so
decoding is mandatory — `encodeURIComponent` on the frontend and matching decode on the
backend.

**Response shapes:**

```json
// GET /hubs
{
  "total": 10,
  "hubs": [
    {
      "name": "JavaMemoryManagement+Hub",
      "memberCount": 5,
      "inboundLinkCount": 23,
      "nearMissCount": 4,
      "coherence": 0.4182,
      "hasBackingPage": true
    }
  ]
}

// GET /hubs/{name}
{
  "name": "JavaMemoryManagement+Hub",
  "hasBackingPage": true,
  "coherence": 0.4182,
  "members": [
    { "name": "GarbageCollection", "cosineToCentroid": 0.31, "hasPage": true }
  ],
  "stubMembers": [
    { "name": "PhantomReferences" }
  ],
  "nearMissTfidf": [
    { "name": "JvmTuning", "cosineToCentroid": 0.68 }
  ],
  "moreLikeThisLucene": [
    { "name": "JvmTuning", "luceneScore": 4.21 }
  ],
  "overlapHubs": [
    { "name": "JvmTuning+Hub", "centroidCosine": 0.74, "sharedMemberCount": 1 }
  ]
}

// POST /hubs/{name}/remove-member — request
{ "member": "PhantomReferences" }

// POST /hubs/{name}/remove-member — success response
{ "removed": "PhantomReferences", "remainingMemberCount": 4 }
```

**Error mapping:**

| Condition                                       | HTTP Status |
|-------------------------------------------------|-------------|
| Service absent                                  | 503         |
| Hub not found (GET drilldown, POST remove)      | 404         |
| Missing/invalid request body                    | 400         |
| `IllegalArgumentException` from service         | 400         |
| 2-member minimum violated                       | 409         |
| Page save failure                               | 500         |
| Unexpected RuntimeException                     | 500         |

**New DTO** — `wikantik-rest/src/main/java/com/wikantik/rest/dto/RemoveHubMemberRequest.java`:

```java
public class RemoveHubMemberRequest {
    public String member;
}
```

Single mutable field parsed via `GSON.fromJson`, same shape as `BulkDeleteDismissedRequest`.

### Frontend — API client

Three new methods added to `api.knowledge` in
`wikantik-frontend/src/api/client.js`:

```js
listExistingHubs: () =>
  http.get('/admin/knowledge/hub-discovery/hubs'),

getHubDrilldown: (hubName) =>
  http.get(`/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}`),

removeHubMember: (hubName, member) =>
  http.post(
    `/admin/knowledge/hub-discovery/hubs/${encodeURIComponent(hubName)}/remove-member`,
    { member }
  ),
```

### Frontend — new components

**`ExistingHubsPanel.jsx`** — self-contained collapsible panel, mounted by
`HubDiscoveryTab` above the Dismissed Proposals panel. Props: `onError(message)` for
toast surfacing. Internal state:

- `expanded`, `loaded`, `loading`
- `hubs: HubOverviewSummary[]`, `total`
- `openHubs: Set<string>` — hub names whose drilldown is currently expanded
- `drilldowns: Map<string, HubDrilldown>` — keyed by hub name
- `drilldownLoading: Set<string>`
- `removingMember: { hubName, member } | null`

Uses the same monotonic-request-id pattern `HubDiscoveryTab` already uses for the
dismissed list to prevent out-of-order drilldown renders.

**`ExistingHubDrilldown.jsx`** — pure presentational component. Props: `drilldown`,
`onRemoveMember(hubName, member)`, `isRemoving: boolean`. Renders the five sections
described above. Empty sections are hidden (not rendered as "(none)").

**`HubDiscoveryTab.jsx`** — add `<ExistingHubsPanel onError={(m) => setToast({kind:
'error', message: m})} />` above the dismissed-proposals block. No other changes.

**Testid hooks** — every row, drilldown, section header, and button gets a
`data-testid` attribute following the existing `hub-discovery-*` convention:

- `existing-hubs-toggle`
- `existing-hubs-panel`
- `existing-hub-row-{name}`
- `existing-hub-drilldown-{name}`
- `existing-hub-member-{hub}-{member}`
- `existing-hub-member-remove-{hub}-{member}`
- `existing-hub-member-remove-confirm-modal`
- `existing-hub-member-remove-confirm-cancel`
- `existing-hub-member-remove-confirm-ok`

## Data Flow

```
admin opens Hub Discovery tab
  → ExistingHubsPanel mounts collapsed (no fetch)

admin clicks "▸ Existing Hubs (N)"
  → api.knowledge.listExistingHubs()
  → HubOverviewService.listHubOverviews()  (one KG+TFIDF pass)
  → table renders sorted by coherence asc

admin clicks a hub row
  → api.knowledge.getHubDrilldown(name) if not already cached
  → HubOverviewService.loadDrilldown(name)  (single-hub compute)
  → ExistingHubDrilldown renders beneath the row

admin clicks [Remove] on a member
  → confirm modal
  → api.knowledge.removeHubMember(hub, member)
  → HubOverviewService.removeMember() parses hub page,
     mutates `related:`, saves via PageWriter
  → HubSyncFilter diffs kg_edges and drops the removed edge
  → frontend optimistically removes the row, then reloads the drilldown
     in the background to pick up new coherence and near-miss counts
```

## Error Handling & Edge Cases

- **Content model null / empty.** List returns empty. Drilldown still populates
  `name`/`hasBackingPage`/`stubMembers` from KG + PageManager, other lists empty.
- **Member missing from TF-IDF model.** Skipped in centroid math. Member still appears
  in drilldown with `cosineToCentroid = NaN` → JSON `null` → UI `—`.
- **Hub with <2 model-backed members.** `coherence = NaN`, hub still listed, sorts last.
- **Orphaned hub node (no backing page).** Lists with `hasBackingPage = false`.
  Drilldown works (reads members from KG, not the page file). MoreLikeThis seed falls
  back to the exemplar member. `removeMember` returns 404.
- **Lucene unavailable.** `loadDrilldown` catches and logs WARN, returns empty
  `moreLikeThisLucene`. Rest of drilldown renders.
- **KG query failure.** `JdbcKnowledgeRepository` wraps in `RuntimeException`; REST
  catches, logs ERROR, returns 500.
- **Page-save failure during remove-member.** Wrapped in `HubOverviewException` →
  500. In-memory frontmatter mutation is discarded; wiki is unchanged.
- **Concurrent member removal.** Page save is last-writer-wins; both removals survive
  (target sets are independent). No lock needed.
- **Concurrent with runDiscovery.** No interaction — discovery only touches
  `hub_discovery_proposals`.

## Testing

### Unit — `HubOverviewServiceTest` (wikantik-main)

Test doubles for all collaborators. Scenarios:

1. `listHubOverviews` happy path — 3 hubs with distinct coherence, verify sort.
2. `listHubOverviews` empty content model — returns empty, no exception.
3. `listHubOverviews` hub with all non-model members — `coherence = NaN`, sorts last.
4. `listHubOverviews` inbound-link exclusion — hub itself + other members of same hub
   don't count.
5. `listHubOverviews` near-miss threshold — cosine `< threshold` excluded, `==
   threshold` counted.
6. `loadDrilldown` happy path — members sorted ascending by cosine, stubs separated,
   near-miss / MLT / overlap populated.
7. `loadDrilldown` unknown hub — returns null.
8. `loadDrilldown` orphaned hub — `hasBackingPage = false`, drilldown populates from
   edges only.
9. `loadDrilldown` Lucene throws — drilldown returns empty `moreLikeThisLucene`, WARN
   logged.
10. `removeMember` happy path — page text captured, verify exact `FrontmatterWriter`
    output.
11. `removeMember` missing `member` — `IllegalArgumentException`.
12. `removeMember` member not in hub's `related:` — `IllegalArgumentException`.
13. `removeMember` `type` not `hub` — `IllegalArgumentException`.
14. `removeMember` would leave <2 members — `HubOverviewException`, text NOT written.
15. `removeMember` save throws — `HubOverviewException`, wiki unchanged.
16. `removeMember` body content preserved byte-for-byte across mutation.

### Unit — `AdminHubDiscoveryResourceTest` (wikantik-rest, extended)

Extends the existing test class (fake `WikiEngine`, recorded `sendJson`/`sendError`):

17. `GET /hubs` 200 with sorted list.
18. `GET /hubs` 503 when `HubOverviewService` unavailable.
19. `GET /hubs/{name}` 200 with drilldown.
20. `GET /hubs/{name}` 404 when unknown.
21. `GET /hubs/{name}` decodes `+` in hub name.
22. `POST /hubs/{name}/remove-member` happy path, 200 JSON.
23. `POST /hubs/{name}/remove-member` missing `member` field → 400.
24. `POST /hubs/{name}/remove-member` invalid JSON body → 400.
25. `POST /hubs/{name}/remove-member` service `IllegalArgumentException` → 400.
26. `POST /hubs/{name}/remove-member` service "not found" → 404.
27. `POST /hubs/{name}/remove-member` 2-member-minimum → 409.

### Integration — `HubOverviewAdminIT` + `HubOverviewAdminPage`

New Selenide IT and page object, following the existing `HubDiscoveryAdminIT` pattern.
Seeded via the existing `RestSeedHelper`:

28. Seed: 3 real hub pages with 3-4 real members each; 1 non-member near-miss for
    hub A; 1 overlap pair.
29. Expand Existing Hubs panel, verify row count and sort order (by inspection of
    seed coherences).
30. Click hub A, verify drilldown renders all five sections and specific
    member/near-miss entries.
31. Click Remove on a member, confirm, verify row disappears and member count
    decrements.
32. Remove member that would leave <2 → 409 toast with 2-member-minimum message.

MoreLikeThis is asserted as "non-empty" only — Lucene scoring is version-fragile.

## Observability

One INFO log line per operation, plus WARN/ERROR on failures:

- `Hub overview list: {count} hubs, {duration}ms`
- `Hub overview drilldown: hub='{name}' members={n} nearMiss={n} overlap={n} {duration}ms`
- `Hub overview: removed member '{member}' from '{hubName}', remaining {n} (reviewed by {reviewer})`

No new metrics in the first cut. The existing servlet-layer request counters
automatically cover the new endpoints.

## Files Touched

**New:**
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewService.java`
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubOverviewException.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/HubOverviewServiceTest.java`
- `wikantik-rest/src/main/java/com/wikantik/rest/dto/RemoveHubMemberRequest.java`
- `wikantik-frontend/src/components/admin/ExistingHubsPanel.jsx`
- `wikantik-frontend/src/components/admin/ExistingHubDrilldown.jsx`
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java`
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/pages/admin/HubOverviewAdminPage.java`

**Modified:**
- `wikantik-main/src/main/java/com/wikantik/knowledge/KnowledgeGraphServiceFactory.java`
  (wire `HubOverviewService`)
- `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java` (promote
  `normalizedCentroid` and `meanDot` from private to package-private so the new
  service can reuse them)
- `wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java` (add a
  new `moreLikeThis(seedDocName, maxResults, excludeNames)` public method wrapping
  Lucene's `MoreLikeThis` over the `contents` field)
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminHubDiscoveryResource.java` (three
  new handlers + routing)
- `wikantik-rest/src/test/java/com/wikantik/rest/AdminHubDiscoveryResourceTest.java`
  (scenarios 17–27)
- `wikantik-frontend/src/api/client.js` (three new methods)
- `wikantik-frontend/src/components/admin/HubDiscoveryTab.jsx` (mount
  `ExistingHubsPanel`)
- `ini/wikantik.properties` (four new defaults)

## Open Questions

None — all design decisions (Q1–Q8) resolved during brainstorming.
