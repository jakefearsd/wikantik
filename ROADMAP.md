# Roadmap

This document captures the direction Wikantik is heading. It's not a
contract — priorities shift as the project gets used, and the canonical
record of what shipped is [`CHANGELOG.md`](CHANGELOG.md). For why a
specific past decision was made, see the design specs under
[`docs/superpowers/specs/`](docs/superpowers/specs/) and the
[`ArchitectureCritique.md`](docs/ArchitectureCritique.md) for honest
strengths-and-weaknesses self-review.

## Now (target: tag `v2.0.0`)

The current focus is finishing the release-readiness sweep before
cutting a public 2.0 tag. Active work:

- Manual smoke testing pass against the bare-metal deploy.
- Bump `1.0.0-SNAPSHOT` → `2.0.0`, cut `[2.0.0]` CHANGELOG section,
  tag `v2.0.0`, let `.github/workflows/release.yml` publish the
  container image to `ghcr.io/jakefearsd/wikantik:2.0.0` and create
  the GitHub Release.
- README screenshots (reader + admin) once the manual testing pass
  exercises the surfaces.

## Next (target: 1.x minors over the next quarter)

- **Demo deployment.** A public read-only Wikantik instance, ideally
  running the Wikantik project's own documentation, so evaluators can
  click "try it" from the README.
- **MCP write surface fits and finish.** Inline image upload via
  `write_pages`, attachment-aware diff, structured rollbacks.
- **Hybrid retrieval tuning loop.** Currently the
  retrieval-quality CI is wired but not gating; when enough data is
  collected, switch from "smoke-only" to "regression gate."
- **Knowledge Graph reviewer ergonomics.** Current admin surface works
  but is dense; planned work to surface evidence-side-by-side and
  bulk-action inverses.
- **Auth modernisation.** OAuth/OIDC via pac4j shipped; next is
  fine-grained scopes for MCP tokens (per-tool allowlists rather than
  global bearer access).
- **Observability dashboards.** Prometheus exporters exist; ship a
  Grafana dashboard JSON in-repo for the most common operator views
  (ingest rate, retrieval latency, KG queue depth).

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
