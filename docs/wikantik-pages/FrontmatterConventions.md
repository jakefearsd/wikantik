---
status: active
type: reference
tags:
- frontmatter
- yaml
- knowledge-graph
- conventions
- wikantik-development
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD2K72YBHBZHHANDBZ65MJ
date: '2026-05-04'
kg_include: true
audience:
- humans
verified_at: '2026-05-04T21:10:44.598011331Z'
cluster: wikantik-development
title: Frontmatter Conventions
summary: 'Frontmatter reference: the fields, what blocks a save versus only warns,
  hub-declared clusters, and the endpoints that validate a block.'
---
# Frontmatter Conventions

In Wikantik, YAML frontmatter is the "Structural Spine" of a page. It is the machine-readable metadata behind search, SEO output (meta description and JSON-LD), the structural index (clusters and hubs), the RDF ontology, Knowledge Graph inclusion and the agent-grade page projection. The field list lives in one place, `FrontmatterSchema` on the server (`GET /api/frontmatter-schema`), and the same validator runs on every save path: the editor, REST and the MCP write tools.

## Core fields

No field is mandatory for a save to succeed, and `canonical_id` is added for you. By convention every content page sets these:

```yaml
---
title: Human-Readable Title
type: article | hub | reference | runbook | design
cluster: thematic-cluster-name
status: draft | active | archived
date: 'YYYY-MM-DD'
summary: One sentence, 50 to 160 characters, for search and previews.
tags: [networking, security, java]
---
```

`verify_pages` flags a missing `type`, `tags` or `summary`, and with its `seo_readiness` and `retrieval_readiness` checks also a missing `date`, `cluster` or `title`.

### The Canonical ID

The `canonical_id` is a stable, 26-character ULID that identifies the page regardless of its slug or title.

- **Auto-injection:** If you create a page without an ID, the `StructuralSpinePageFilter` injects one on the first save (flag `wikantik.structural_spine.enforcement.enabled`, default true).
- **Never modify:** Do not change or delete this ID once assigned. It is the page's rename-stable identity: `GET /api/pages/by-id/{canonical_id}`, the `get_page_by_id` MCP tool and the page's ontology IRI all key on it.

## All fields

| Key | Value | Notes |
|---|---|---|
| `canonical_id` | ULID | Read-only; see above. |
| `title` | text | Also the `<title>`, and embedded in every chunk. |
| `aliases` | list of text, each up to 100 characters | Used by the quick switcher and by `[[wikilink]]` resolution. |
| `type` | `article`, `hub`, `reference`, `runbook`, `design` | Selects the schema.org type in the page's JSON-LD. Other values warn. |
| `status` | `draft`, `active`, `archived` | Other values warn and suggest one (`published` becomes `active`). A label only: the server does not hide a `draft` page. |
| `summary` | text, 50 to 160 characters | Meta description, and prepended to every chunk. Outside that range warns. |
| `tags` | list, lowercase kebab-case | Search facets, `<meta keywords>` and the keywords of the sitemap's news entries. |
| `cluster` | kebab-case slug, or `parent/child` | See Clusters below. |
| `related` | list of page names | Curated see-also links; a name that does not resolve warns. |
| `date` | ISO date | Becomes `datePublished`. |
| `author` | text | Decides the initial page owner when it names a real login (`docs/admin/PageOwnership.md` in the repository). |
| `kg_include` | `true` or `false` | Overrides the cluster-primary KG policy; see below. |
| `verified_at`, `verified_by` | ISO timestamp, login | Set by `mark_page_verified`. |
| `audience` | `humans`, `agents`, `both`, or a list | Anything else is an error. |
| `confidence`, `agent_hints` | read-only | Derived; see below. |
| `runbook` | block | Required on `type: runbook` pages. |

Keys outside the schema are kept as written. The platform also reads `image` (per-page `og:image`) and the machine-stamped `derived_*` keys on pages made by document ingestion or a connector.

## Optional and special fields

### Tags

A list of topic tags for granular discovery, in lowercase kebab-case (`graph-theory`). Non-kebab tags warn.

### Related pages

`related: [PageName, OtherPage]` is a curated see-also list. The typed `relations:` field (`part-of`, `implements`, and so on) was removed on 2026-05-02: Page Graph edges are now only real links in the page body (see [Markdown Links in Wikantik](MarkdownLinks)), and the `hubs:` field is gone too.

### Verification metadata

Used to track content trustworthiness.

```yaml
verified_at: '2026-05-04T12:00:00Z'
verified_by: jakefear
```

`confidence` (`authoritative`, `provisional` or `stale`) is derived, not authored: `stale` when `verified_at` is absent or older than `wikantik.verification.stale_days` (default 90), `authoritative` when a trusted author verified the page inside that window, otherwise `provisional`.

### Agent-specific metadata

```yaml
audience: humans | agents | both
kg_include: true | false    # override the cluster-level KG policy
```

`kg_include` overrides the cluster-primary, default-exclude policy that decides whether a page's extracted entities enter the Knowledge Graph ([KG Inclusion Policy](KgInclusionPolicy)). Any value other than `true` or `false` refuses the save.

### The runbook block

A `type: runbook` page needs a `runbook:` block with `when_to_use` (at least 1 entry), `steps` (at least 2) and `pitfalls` (at least 1); problems are save errors.

## Clusters

A cluster exists if and only if exactly one hub page declares it: a page with `type: hub` and a single scalar `cluster: <path>`. A sub-cluster is written `parent/child`, one level only. A non-hub page joins a cluster by naming it and may list several (`cluster: [cooking, food-science]`); the first entry is its primary cluster, which decides its breadcrumbs, `articleSection` and sidebar placement. A page naming a cluster no hub declares still saves, with a `cluster.undeclared` warning. The value also feeds the BM25 boost, the embedding prefix, the ontology topic hierarchy and Knowledge Graph inclusion, so a mistyped cluster has real consequences. `rename_cluster` (`POST /admin/clusters/rename`) rewrites a cluster across all members and returns a plan unless `confirm=true`. The design is in [Cluster Declaration Design](ClusterDeclarationDesign).

## What blocks a save and what only warns

Errors refuse the save (HTTP 422, or an MCP refusal); warnings let it through and come back with the response.

**Errors:** malformed YAML (most often an unquoted colon, as in `title: Foo: Bar`; quote the value, or pass it through the MCP `metadata` argument, which quotes for you); an `audience` outside the allowed values; a malformed `runbook:` block; a list-valued `cluster` on a hub; a non-boolean `kg_include`; a second hub declaring an already-declared cluster, only if `wikantik.cluster_declaration.enforcement.enabled` is on (it ships `false`); and a non-canonical `type` or `status`, only if `wikantik.frontmatter.enum.nonCanonical.severity` is `error` (default `warning`).

**Warnings:** `summary` outside 50 to 160 characters; a cluster or tag that is not kebab-case; a cluster no hub declares; a `related` name that does not resolve; a non-ISO `date`; a `verified_by` outside `wikantik.frontmatter.trustedAuthors` (checked only when that list is set); and non-canonical `type` or `status` values.

Corpus-wide counts of each warning are on the `/admin/drift` dashboard, and `GET /admin/frontmatter-issues` lists pages whose YAML fails strict parsing.

## Checking a block before you save it

- `POST /api/frontmatter/validate` is a dry run. Send `{"frontmatter": "<yaml>"}` or `{"metadata": {...}}`; the response is `{metadata, violations}`, each violation with a field, severity, code, message and, where known, a suggested replacement.
- `PUT /api/pages/{name}` answers 422 with field-addressed violations, or 200 with a `warnings` array.
- `write_pages` and `update_page` (admin MCP) run the same validator. `update_page` merges `metadata` onto the existing frontmatter, so a one-field edit cannot drop the rest; to delete a key pass `removeKeys`.
- `verify_pages` with `seo_readiness` and `retrieval_readiness` reports authoring-quality checks that are not part of the schema, such as thin summaries and generic headings.

## Best Practices

1. **Keep summaries concise:** 50 to 160 characters. They are the meta description, appear in tool outputs and link previews, and are prepended to every chunk before it is embedded, so name the concrete concepts a reader would search for and skip generic keyword lists.
2. **Use stable clusters:** clusters should represent broad thematic areas (for example `wikantik-development`, `personal-finance`), and each needs a hub page that declares it.
3. **Type correctness:** make sure `type` reflects the page's purpose. `runbook` pages undergo additional schema validation.
4. **Link with real links:** Page Graph edges come from links in the body, so link related pages explicitly and list the curated ones in `related`.

## See Also

- [Markdown Links in Wikantik](MarkdownLinks) — link syntax, which builds the Page Graph
- [Structural Spine Design](StructuralSpineDesign) — `canonical_id` and the structural index
- [Agent-Grade Content Design](AgentGradeContentDesign) — runbooks, verification and the for-agent projection
- [Content Program Policy](ContentProgramPolicy) — how summaries and titles are written for retrieval
