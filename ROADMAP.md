# Roadmap

This document captures the direction Wikantik is heading. It's not a
contract — priorities shift as the project gets used, and the canonical
record of what shipped is [`CHANGELOG.md`](CHANGELOG.md). For why a
specific past decision was made, see the design specs under
[`docs/superpowers/specs/`](docs/superpowers/specs/) and the
[`ArchitectureCritique.md`](docs/ArchitectureCritique.md) for honest
strengths-and-weaknesses self-review.

## Now (2.4.x — live in production)

Wikantik runs in production (`wiki.wikantik.com`, containerised on
docker1, images published to `ghcr.io/jakefearsd/wikantik` and deployed
via `bin/deploy-release.sh`). Day-to-day work is an incremental 2.x
stream. The canonical record of what shipped is
[`CHANGELOG.md`](CHANGELOG.md); the highlights since 2.0:

- **Enterprise hardening** — SSO (OIDC + SAML via pac4j), SCIM 2.0
  provisioning, the tamper-evident hash-chained audit log, off-box
  backups, and a two-tier per-IP rate limiter. The 2.4.12–2.4.18
  security-hardening wave added an SSRF egress guard on connector
  fetches, `JDBCPlugin` disabled by default, viewer-dependent renders
  excluded from the shared render caches, and the split `mcp_read` /
  `mcp` API-key scopes.
- **RAG-as-a-Service** — the wiki assembles a ranked, de-duplicated,
  version-pinned-cited *context bundle* rather than synthesizing an
  answer ([ADR-0001](docs/adr/0001-rag-returns-context-bundle-not-synthesized-answer.md)),
  plus budgeted session briefings for coding agents and first-class
  `cite://` citation edges with stale-citation self-healing.
- **External-source connectors** — seven connector types syncing into
  derived pages, with DB-backed configs, an encrypted credential store,
  and a guided admin wizard.
- **The `wikantik:` RDF/OWL ontology** — a write-time SHACL gate and a
  public SPARQL / JSON-LD / dump surface behind a public-vs-restricted
  ACL split.
- **Cluster declaration** (2.4.0) — the hub page is now the
  authoritative declaration of its cluster, with scalar-or-list
  multi-membership and bulk `rename_cluster`
  ([ADR-0009](docs/adr/0009-cluster-taxonomy-is-frontmatter-projection-not-filesystem-hierarchy.md)).
- **Cloud reference deployments** — single-VM Terraform modules for AWS
  and GCP, and a `wikantik.genai.mode` cost ceiling so LLM spend is a
  dial rather than an all-or-nothing bet.

## Next (target: 2.x minors over the next quarter)

- **Demo deployment.** A public read-only Wikantik instance, ideally
  running the Wikantik project's own documentation, so evaluators can
  click "try it" from the README.
- **MCP write surface fits and finish.** Inline image upload via
  `write_pages`, attachment-aware diff, structured rollbacks.
- **Hybrid retrieval tuning loop.** Currently the
  retrieval-quality CI is wired but not gating; when enough data is
  collected, switch from "smoke-only" to "regression gate."
- **Turn on cluster-declaration enforcement.** The duplicate-declaration
  save-time 422 shipped dark —
  `wikantik.cluster_declaration.enforcement.enabled` defaults to
  `false`. Flipping it requires a corpus verified free of duplicate
  declarations first (via `/admin/drift`), because enabling it against
  a corpus that has them makes the offending hub pages un-saveable.
  The production corpus migration off the retired `hubs:` field is the
  other half of that work.
- **Knowledge Graph reviewer ergonomics.** Current admin surface works
  but is dense; planned work to surface evidence-side-by-side and
  bulk-action inverses.
- **Auth modernisation.** OAuth/OIDC via pac4j shipped; a first cut at
  fine-grained MCP scopes shipped in 2.4.18 (`mcp_read` vs `mcp`, splitting
  read-only knowledge access from full admin). Next is a third,
  intermediate `mcp_content` tier (page/KG read-write, no admin
  capability) — deferred because it needs per-tool enforcement threaded
  through the MCP session rather than a request-thread guard, since the
  SDK dispatches tool calls off-thread.
- **Observability dashboards.** Prometheus exporters exist and the
  container exposes `/metrics`, but monitoring itself now lives in the
  external **jakemon** stack (Grafana Alloy → Prometheus + Loki +
  Grafana), so there is no in-repo observability stack to ship a
  dashboard into. The open work is a set of curated operator views in
  jakemon (ingest rate, retrieval latency, KG queue depth) rather than
  a dashboard JSON in this repo.
- **Audit log egress.** The tamper-evident audit log is DB-only today;
  add an exporter so records can stream to an external SIEM / log
  pipeline (syslog/CEF or an outbound webhook) for orgs that centralise
  security monitoring. The in-DB hash chain stays the source of truth.

## Later (1-2 years)

- **Multi-tenant deployment.** A single Wikantik instance hosting
  multiple isolated wikis with shared MCP infrastructure.
- **Real-time collaborative editor.** Currently single-author per page;
  eventually CRDT-based co-editing without giving up the file-tree
  authoring model.
- **Plugin marketplace.** Today Wikantik supports plugins but discovery
  is manual; eventually a curated registry.
- **More extractor backends.** Currently Ollama is the only
  Knowledge-Graph extraction LLM provider. Adding Anthropic, OpenAI,
  and a self-hosted vLLM path so operators can pick their constraint.

## Considering / undecided

These are open questions. If you have an opinion, please weigh in via
an issue.

- **License direction.** Currently Apache 2.0. AGPL would protect the
  project from SaaS forks; staying Apache 2.0 maximises adoption. No
  decision yet; if relicensing happens, contributors will be notified
  before any change.
- **Hosted offering.** A paid hosted Wikantik (`wiki.example.com`-style
  multi-tenant SaaS) would fund development; no decision yet on
  whether to ship it.
- **Sponsorship.** GitHub Sponsors is configurable in
  [`.github/FUNDING.yml`](.github/FUNDING.yml) but disabled until a
  funding strategy is settled.

## Out of scope

- A heavyweight CMS feel (page templates, drag-drop layout builders).
  Wikantik is a knowledge base, not a website builder.
- A general-purpose vector database. PostgreSQL + pgvector is the
  one-and-only data store; that's a feature, not a limitation.
- Mobile-app-quality offline editing. Reader works offline; the editor
  doesn't and won't.
- Rebuilding the JSP UI. The React SPA is the front end; the JSP-era
  rendering layer was retired during the rebrand.
