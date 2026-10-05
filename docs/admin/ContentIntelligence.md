# Content Intelligence (Search Visibility)

This page is for administrators and content curators who want to know what to write or
fix next. Content Intelligence combines search-engine visibility data with Wikantik's
own retrieval and verification data into a ranked **opportunity backlog**. It covers
the **Search Visibility** screen at `/admin/insights`, the data that feeds it, how to
load that data, and how to act on or decline an opportunity. The module is
`wikantik-insights`; the full design is in
[the content-intelligence design](../superpowers/specs/2026-08-16-content-intelligence-design.md).

The subsystem is on by default (`wikantik.insights.enabled`, default `true`) and needs
a configured datasource. Without one, the backlog endpoint and the two MCP tools
answer that the subsystem is not available.

## Use the Search Visibility screen

Open **Admin → Search Visibility** (`/admin/insights`, `InsightsPanel.jsx` and
`BacklogPanel.jsx`). It has two parts.

**Acquisition.** The screen always asks for the site `wiki.wikantik.com` (a constant in
`InsightsPanel.jsx`); call the endpoint yourself to see another site. One row per search engine for the most recent snapshot date: clicks,
impressions, click-through rate, average position, and a clicks sparkline over the last
90 days. Backed by `GET /admin/insights/acquisition?site=&days=` (`InsightsResource`;
`days` is clamped to 1-400). Totals come from page-rollup rows only, because every
engine omits low-volume queries and summing query rows would undercount. An engine that
sends no page-rollup rows (Yandex, at the time of writing) is absent, not shown as zero,
and a missing position is shown as an em dash, not `0`, since `0` would mean rank 1.

**Opportunity backlog.** The ranked list, with a **Type** filter and an **Include
snoozed** checkbox. Each row shows type, target, priority, first-seen date, suggested
action and the evidence that fired the rule. Backed by
`GET /admin/insights/backlog` (`InsightsBacklogResource`) with `site`, `type`,
`minPriority`, `limit` (1-200, default 50) and `includeSnoozed`.

Read three things beside the list:

- **Suppressed rules.** Three of the four native rules depend on search volume. Below a
  site traffic gate they do not run at all and are listed as suppressed with the
  measured and required values. An empty list with suppressed entries means "not enough
  traffic to tell", not "nothing to do".
- **Uncalibrated badge.** An opportunity whose `calibrated` flag is false has a priority
  weight that is still a starting guess, not a validated value; weigh it lower.
- **`ctrCurveSource`.** `imported:<date>` means the click-through-by-position curve came
  from the latest jakemon shipment; `builtin` means jakemon has not shipped one and a
  placeholder table is in use. A stale date means the shipper has stopped.

## Know what feeds it

| Source | What it supplies | Where it lands |
|---|---|---|
| jakemon search-visibility snapshots | per-engine page and query clicks, impressions and position (Google, Bing, Yandex) | `search_visibility_snapshot` (`V050`) |
| jakemon detector output | five imported opportunity types | `imported_opportunity` (`V056`) |
| jakemon expected-CTR curve | measured CTR by position, positions 1-10 | `expected_ctr_curve` (`V057`) |
| Wikantik's own retrieval log | queries that agents ask and get weak or empty answers for | `retrieval_query_log` (signal columns added by `V051`) |
| Page frontmatter and verification state | page age, verification and confidence | read live from the wiki |

jakemon is the observability plane that collects the search-engine data; Wikantik only
stores and reads it. See `SimpleAgilityObservabilityPlane` on the wiki for the boundary.

### Opportunity types

Four **native** rules are computed inside Wikantik (`OpportunityEngine`):

| Type | Fires when | Gated by traffic? | Target |
|---|---|---|---|
| `agent_gap` | a query comes back empty or weak on every agent surface, with minimum occurrences and distinct sessions | no | the query text |
| `engine_divergence` | a page ranks materially better on one engine than another (a diagnostic, not a fix) | yes | a page path |
| `vocabulary_gap` | a query with real clicks lands on a page whose metadata does not mention the query's content words | yes | a page path |
| `stale_high_traffic` | a page with real traffic whose verification is old, missing, or not authoritative | yes | a page path |

Five **imported** types arrive pre-built from jakemon and pass straight through:
`striking_distance`, `ctr_gap`, `content_gap`, `cannibalization` and `decay`. Imported
`ctr_gap` and `striking_distance` rows are suppressed for a page when `engine_divergence`
explains them.

### Tune the engine

The settings are in `wikantik.properties` under `[Content Intelligence]`; the generated
[ConfigurationReference.md](../ConfigurationReference.md) lists every key with its
default. The ones you are most likely to change:

| Property | Default | Effect |
|---|---|---|
| `wikantik.insights.rules.sites` | `wiki.wikantik.com,wikantik.com` | sites the rules act on; the first is the default site |
| `wikantik.insights.rules.gate.impressions28d` | `5000` | site impressions in 28 days below which the three volume-driven rules do not run |
| `wikantik.insights.opportunity.stale.days` | `180` | age after which a high-traffic page counts as stale |
| `wikantik.insights.imported.max_age_days` | `7` | the backlog reads only the latest imported set, and only if it is this recent |
| `wikantik.insights.calibration.min_verdicts` | `20` | evaluated changes needed before a type's weight is recalibrated |
| `wikantik.insights.change.cooldown.days` | `60` | at most one automatic optimisation per page per this many days |

Set your own site names in `wikantik.insights.rules.sites`; the defaults name the
public Wikantik sites. The backlog uses the first entry when no `site` is given.

## Load search-visibility data

`POST /admin/insights/ingest` accepts one jakemon snapshot document per request. It is
gated by `AdminAuthFilter` like every `/admin/*` path. Use an administrator, or better,
a service account that holds only the `insights` area (see below).

```bash
curl -u shipper:... -X POST http://localhost:8080/admin/insights/ingest \
     -H 'Content-Type: application/json' --data @snapshot.json
```

The document has a top-level `engine`, `site`, `snapshot_date` (ISO date) and optional
`window_days` (default 28), plus the data arrays:

- `by_page`: rows `{key, impressions, clicks, position?}`; `key` is a page URL or path
  (scheme, host and fragment are stripped, trailing slash removed).
- `by_query`: rows with the same shape; `key` is the query text.
- `query_page`: rows `{query, page, impressions?, clicks?, position?}`.
- `opportunities` (optional): the five imported detector types; each row needs a `type`
  and a `query` or `target_page`.
- `expected_ctr` (optional): an object mapping position to CTR.

A successful response reports counts:

```json
{"rows_upserted": 1200, "rows_rejected": 0}
```

`opportunities_upserted` / `opportunities_rejected` and `expected_ctr_upserted` /
`expected_ctr_rejected` appear only when the payload carried those keys. A bad row is
counted in the `*_rejected` fields rather than failing the request; the endpoint never
returns 500 for a malformed payload.

| Property | Default | Effect |
|---|---|---|
| `wikantik.insights.ingest.max_bytes` | `4194304` | largest accepted body; larger requests get 413 |
| `wikantik.insights.ingest.engines` | `google,bing,yandex` | engines accepted; others are rejected and counted |
| `wikantik.insights.ingest.sites` | blank | sites accepted; blank accepts every site |

### Give the shipper least privilege

The shipper only needs `/admin/insights/*`. Grant it that one area instead of putting
its account in the `Admin` group. Post the grant to `/admin/policy`:

```json
{"principalType":"user","principalName":"jakemon-shipper","permissionType":"admin",
 "target":"insights","actions":"access"}
```

Details of scoped admin grants are in
[Security.md](Security.md#scoped-admin-access).

## Act on or decline an opportunity

Two MCP tools on `/wikantik-admin-mcp` expose the same backlog to an agent, using the
same service as the screen so the two never disagree (see [McpAgents.md](McpAgents.md)):

- `list_content_opportunities` takes optional `type`, `limit` (default 20, max 200),
  `min_priority`, `include_snoozed` and `site`. It returns `opportunities`, `count`,
  `suppressed`, `uncalibratedTypes`, `ctrCurveSource` and `generatedAt`. Always read
  `suppressed` as well as `opportunities`.
- `snooze_opportunity` declines one so it stops being re-proposed. All four arguments
  are required: `type`, `target` (a page path, or the query text for `agent_gap`),
  `days` (1-365) and `reason`. The reason is mandatory so a declined suggestion is
  auditable. It returns `snoozedUntil` and `previouslySnoozed`. A snooze is a deferral,
  not a deletion; re-snooze if the item is still not worth doing.

The suggested action depends on the type. For `agent_gap`, curate the Knowledge Graph
relations or write the missing section. For `stale_high_traffic`, re-verify or refresh
the page.

## Where to go next

- [McpAgents.md](McpAgents.md): connect an agent to the two backlog tools.
- [RetrievalQuality.md](RetrievalQuality.md): the retrieval side that produces `agent_gap` evidence.
- [Security.md](Security.md): scoped admin grants for the shipper account.
- [AdminPanel.md](AdminPanel.md): the other admin screens.
