---
summary: 'Narrative of Wikantik development from mid-April to November 2026: releases
  1.1 through 2.5.15, month by month.'
date: ''
canonical_id: 01M04K5CQ4S441N5B6TVRHJYPT
type: article
tags:
- wikantik
- news
- release-history
- development
---
# Wikantik Development News

A narrative of development on Wikantik from mid-April to 12 November 2026. The
first four sections tell the six weeks from mid-April to the end of May, when the
project crossed from `1.1.x` to its `2.0` line, went live on real infrastructure,
and shifted its center of gravity from "a wiki that an AI can read" to "a wiki
built, operated, and consumed with AI agents as first-class citizens." The
sections after that go month by month from June to November: about 2,700 commits
and 103 tagged releases, from `2.0.8` to `2.5.15`, the release production runs
today.

---

## The through-line: an agent-grade knowledge base

The dominant story of these weeks is making the corpus genuinely useful to
retrieval-augmented agents — not as an afterthought, but as a designed surface.

**Hybrid retrieval (mid-to-late April).** The window opens with hybrid search
landing in earnest: BM25 lexical scoring fused with dense vector similarity via
weighted reciprocal-rank fusion. A `QueryEmbedder` wraps the embedding client
with a cache, a timeout, and a hand-rolled circuit breaker so a slow or failing
embedding backend degrades to lexical search instead of taking the page down. An
in-memory chunk-vector index served the first dense top-k, and a retrieval
**experiment harness** with reproducible eval reports let us pick fusion weights
from evidence rather than intuition. This shipped as `v1.1.6`.

**Agent-grade content and the structural spine (late April).** From there the
work turned structural. A `ContextRetrievalService` was extracted as the single
seam through which search, the REST API, and the tool servers all retrieve —
collapsing several near-duplicate read paths into one. On top of it came the
**structural spine**: rename-stable canonical IDs, cluster/hub membership, and a
machine-queryable `/api/structure/*` index with matching MCP tools, all enforced
at save time. Pages gained **verification metadata** — `verified_at`,
`verified_by`, a computed `confidence` (authoritative / provisional / stale), and
an `audience` flag — backed by a trusted-authors registry, a `mark_page_verified`
tool, and an operator triage view. The **`/for-agent` projection** was born here
too: a token-budgeted view of any page (summary, key facts, headings outline,
recent changes, tool hints, verification state) that an agent can consume without
pulling the full markdown body. To keep all of this honest, a **retrieval-quality
CI** runner began scoring a curated query set with nDCG, Recall, and MRR across
retrieval modes and persisting the aggregates.

**Knowledge-graph inclusion policy (late April).** As the LLM-extracted knowledge
graph grew, it needed a governance story. A cluster-primary inclusion/exclusion
policy arrived with an admin dashboard, a CLI, per-page frontmatter overrides, and
an audit trail — defaulting to exclude, so entities are opted in deliberately.

**Page Graph vs. Knowledge Graph (early May).** A naming and conceptual cleanup
that paid for itself many times over: formally separating the **Page Graph**
(edges are real page-to-page wikilinks) from the **Knowledge Graph** (nodes are
extracted entities, edges are co-mention or typed relations). Packages, routes,
admin navigation, and tool descriptions were all disambiguated; the typed
`relations:` frontmatter experiment was retired; and identity normalization
(`NodeSignature` / `EdgeSignature`, NFC + predicate-synonym folding) tightened
entity dedup. A staged machine/human validation tier and a `/knowledge-graph`
viewer (mirroring the existing `/page-graph` reader) rounded it out.

**Derived agent hints (mid-May).** The projection got smarter without adding
author burden: derived `prefer_tools` and `prefer_pages` hints ranked across a
page and its cluster hub, a hub-summary synthesizer that overlays a Top-3
highlight when an authored hub summary is generic, a `read_pages` batch tool to
amortize the per-page read tax, and an agent-grade audit report for weak signals.

**Knowledge-graph curation (mid-May).** Curators got real tools: edge-curation v0
in the admin UI with an append-only audit table and one-click human-curated
elevation, then a full bulk curation surface on the MCP server (inspect / review /
curate nodes and edges), admin-bypass read paths so curators see freshly created
entities, and a closed `node_type` / `relationship_type` vocabulary enforced at
write time.

---

## Under the hood: decomposition and a hard look at scaling

**Subsystem decomposition (early May).** A multi-phase architectural campaign
broke `wikantik-main` into typed subsystem factories, deleted the old
service-locator manager registry on `WikiEngine` in favor of typed fields,
decomposed the 800-line `WikiContext` into scoped sub-objects, and finished with
two static-analysis sweeps (SpotBugs + PMD) that split the worst god classes and
cleared real bugs. The result is a codebase where subsystem wiring is explicit
and testable in isolation.

**The scaling characterization study (third week of May).** Rather than guess at
performance, we ran a deliberate JFR-instrumented load study and let it drive the
fixes. The headline finding: the ceiling was not CPU but a per-request
DB-connection tax and a chain of shared-lock hotspots. The response was a sweep of
targeted surgeries — short-TTL caches for API-key verification, user lookup, and
KG mentions (removing per-request connections); hoisting shared JDK objects
(`Collator`, `DateTimeFormatter`, `SecureRandom`) off the hot path; a read/write
lock on the versioning file provider to kill per-search contention; an
event-manager dispatch-outside-the-lock fix; and a fixed-permit **backpressure
semaphore** that sheds load with a clean 503 before the thread pool saturates.

**Dense retrieval, productionized.** The brute-force in-memory vector scan — about
60% of search CPU — was replaced with selectable ANN backends: an in-process
**Lucene HNSW** index (now the production default, reading metadata via DocValues
rather than stored fields) and a server-side **pgvector HNSW** option for split-DB
topologies, each gated behind a retrieval-quality parity test so the speed-up
couldn't silently degrade relevance. These landed across `2.0.1` and `2.0.2`.

---

## From code to a running product

**Operations and packaging (early-to-mid May).** The project grew the trappings of
something meant to be run by others: a community documentation set (CONTRIBUTING,
SECURITY, code of conduct, a definitive operations handbook), a `bin/container.sh`
wrapper over Docker Compose, a release-on-tag workflow, and — the big one — a
`bin/remote.sh` single entry point for deploying and administering Wikantik on a
remote host over SSH (image transfer, pages rsync, health-polled deploys with
auto-rollback). `2.0.0` was cut and the stack went live in Docker on its first
real host.

**Observability, handed off.** An in-repo Prometheus + Grafana overlay was built
and then deliberately **retired** in favor of an external monitoring stack
(jakemon) that scrapes the container's `/metrics`; a k6 load-test harness with a
`--verify` gate that confirms dashboard panels actually moved was added alongside.

**Off-box backup and disaster recovery (third week of May).** A 3-2-1 backup
posture arrived as a pull model: a NAS reaches in and pulls read-only snapshots on
a daily timer, with restore drills, per-tier Prometheus metrics, and a
`dr-restore.sh` that stands a fresh host up from a verified snapshot with a single
command.

**SSO and account lifecycle (late May).** A dedicated SSO hardening test suite
closed SAML and edge-case gaps — multi-valued claim normalization, fail-closed
identity-collision handling, session rotation against fixation, configurable
identity claims — and **Google OIDC went live** in production, with SAML support
and self-service account deletion alongside published privacy and terms pages.
This was `2.0.3`.

**Go-to-market (late May).** A static marketing site for `www.wikantik.com` was
built and shipped — hero, problem framing, comparison table, hosting options, an
"ask your wiki" terminal moment, and a lead-capture form wired to a serverless
backend — together with SEO fixes (correct sitemap domain, per-page titles,
JSON-LD). Releases `2.0.4` and `2.0.5` carried these out.

---

## Reading, writing, and owning pages

The last stretch of the window returned to the everyday reader and author
experience.

**Anchored comments (last week of May).** A Google-Docs-style commenting system —
threads anchored to selected text via robust text-quote selectors, a reader-side
drawer with highlights, re-anchoring that survives edits, reply/resolve/reopen,
and admin-only thread deletion — built on new `comment_threads` / `comments`
tables and a fresh REST surface, capped by a comment UX overhaul.

**Personalization and blogging (29 May).** A personalized left-navigation "me
zone" with a recently-viewed list that updates live across instances, plus a round
of blog read-path and delete-UI fixes.

**Page ownership (30 May).** A page-ownership admin surface — orphaned-page triage
and reassign-by-user — gained a consistent data model when AI-agent-authored pages
(whose frontmatter author is an agent name, not a login) were given a default
owner: a dedicated `agents` service account, configurable and backfilled across
the corpus, so ownership is never silently null. The ownership table also began
showing each page's current name beside its canonical ID.

---

## June: retrieval becomes a service

June was the busiest month of the year: 742 commits and 23 releases, `2.0.8`
through `2.2.0`.

**A formal ontology (first week of June).** An RDF/OWL layer on Apache Jena arrived
with a public read-only SPARQL endpoint, per-resource linked-data URLs and full
dumps, and the JSON-LD `@type` on every page began to come from the ontology
rather than from a lookup table.

**Structured curation (second week).** The frontmatter editor gained live,
server-authoritative validation and a denser layout, LaTeX was linted on save, and
a batch fixer repaired the math syntax of the existing corpus. A fresh install now
forces a password change at first login and ships a getting-started guide.

**The context bundle (14–19 June).** The defining change of the month:
`GET /api/bundle` and the `assemble_bundle` MCP tool return ranked,
de-duplicated, version-pinned sections with citations, and never a synthesized
answer. Around it came a section-level evaluation harness, document ingestion
into machine-owned **derived pages**, persisted **citation edges** that go stale
when their source changes, a query log across every retrieval surface, and a
bundle that fuses BM25 with dense chunks by default. `verify_pages` learned a
`retrieval_readiness` check so authors can see whether a page is findable.

**Security and audit (late June).** Legacy property-file API keys were removed in
favor of database keys. Denied-access audit records gained full forensic context.
A security sweep (`2.1.4`–`2.1.6`) put access control on every REST read path,
stopped `list_clusters` from naming a restricted hub, and made the security
headers reach server-rendered pages. The frontend moved to React 19 and Vite 8,
and Jena to its 6.x line.

---

## July: connectors, briefings and Java 25

Thirteen releases, `2.3.0` through `2.3.13`.

**A public surface that defends itself (3 July).** Two-tier per-IP rate limiting
landed, every bundle began to carry a **coverage signal** saying how well the
corpus answered the question, and the default dense backend became Lucene HNSW.

**Session briefings (5 July).** `get_briefing` and `GET /api/briefing` give a
coding agent its project's standing context in one call at session start.

**Java 25 (8 July).** The toolchain and runtime moved from Java 21 to Java 25.

**The connector framework (15 July).** Six kinds of external source (web crawler,
sitemap, feeds, Google Drive, GitHub, Confluence) sync into derived pages, managed
from `/admin/connectors` with encrypted credentials. In the same release, query
decomposition shipped behind a default-off flag after losing to single-pass
retrieval in measurement.

**Cost and capability switches (16–21 July).** `wikantik.genai.mode` capped model
spend, the Knowledge Graph gained an off switch, and the context bundle learned to
work with no embedding model at all.

**Engineering health (late July).** The pre-commit gate fell from about 24 minutes
to about four and a half with more coverage. A code-health site aggregates
coverage, coupling and static analysis. A quality audit fixed six latent defects,
and the Knowledge Graph's graph-rerank stage, which had measured no retrieval
lift, was deleted (2,464 lines).

---

## August: declared clusters, a closed content loop and a security sweep

Twenty-four releases, `2.3.14` through `2.4.19`.

**Retrieval repairs (first week).** The embedding backfill turned out never to
have persisted anything, so dense retrieval had been silently running on lexical
results alone; that and two indexing gaps on page save were fixed. A dead-code
sweep then removed 28,694 lines.

**Cluster declaration (15 August, `2.4.0`).** A cluster now exists if and only if
one hub page declares it. Pages may belong to several clusters, and
`rename_cluster` rewrites a cluster across its members with a plan-first default.

**Content Intelligence closes its loop (16–17 August).** Search-visibility data
captured by the observability plane is joined with the wiki's own query log into a
ranked backlog of what to write or fix, and a nightly evaluator measures whether
each change worked. The same days brought scoped `/admin/*` grants and a hardened
npm build.

**Security sweep (19 August, `2.4.14`–`2.4.17`).** Two critical defects were
fixed: an unauthenticated disclosure of admin data and an attachment-filename
file write. The sweep also closed a server-side request forgery on the public
SPARQL endpoint, metadata leaks on the structure endpoints and a cross-user render
cache, disabled `JDBCPlugin` by default, and gave connectors an egress policy. MCP
API keys then split into a read-only scope and an admin scope.

---

## September: one way to touch the database

Nine releases, `2.4.20` through `2.4.28`, and a quieter month by design.

**`wikantik-jdbc` (7 September).** All database access was consolidated behind one
primitive, enforced by an architecture test. The numbered migrations became the
only schema definition, database tests moved to a real PostgreSQL container, and
every configuration key was declared in one drift-tested reference file.

**Hardening.** SHA-1 password formats were removed and per-module coverage floors
introduced (`2.4.24`). Production moved to PostgreSQL 18 (`2.4.25`). Entities
extracted from restricted pages stopped reaching the public ontology (`2.4.26`).

**The editor work begins (late September).** Quick switcher, command palette,
link completion, native wikilinks and the Obsidian importer were built through the
last ten days of the month and released at the start of October.

---

## October: an Obsidian-grade editor, display names and diagrams

589 commits and 22 releases, `2.4.29` through `2.5.3`.

**Editing (1–3 October).** The editor workspace, Live Preview, daily notes,
native `[[wikilinks]]` and `![[embeds]]`, and zipped Obsidian vault import and
export all shipped within three days.

**Authorization and identity (5–14 October).** Authorization now requires a login
session everywhere, including the bootstrap administrator. SCIM refuses writes to
the `Admin` group. From `2.4.58` people are shown to each other by **display
name**; logins, which are email addresses under Google sign-in, no longer reach
anonymous visitors or other users.

**Storage and I/O repairs (22–23 October).** A profiling pass found the ontology
store had grown to 79.7 GB in production. The fixes bounded it, made "Rebuild
indexes" non-destructive, made page writes atomic, audited page saves, and
replaced per-query Lucene readers with one long-lived writer and a shared reader.
Context bundles gained links straight to each cited section, and
`get_page_neighbors` exposed a page's Page Graph neighborhood.

**Project-scoped briefings (24 October).** A briefing for a cluster now returns
the project's own pages, then the shared contracts those pages cite, then
one-line pointers elsewhere. Standing context is ranked by how many members link
to a page, not by how recently it was edited.

**Diagrams (25–28 October, `2.5.0`–`2.5.3`).** Mermaid fences draw in the
browser and PlantUML fences are drawn on the server in a sandbox, both validated
fail-closed, with the source text still available to agents.

---

## November: one dialect, collaboration and the end of the Knowledge Graph

Twelve releases in nine days, `2.5.4` through `2.5.15`.

**Obsidian parity (1 November).** Block references, paste as Markdown with pasted
images uploaded as attachments, inline `#tags`, `==highlight==` and
`%%comments%%` that are hidden from every reader surface. Live Preview became the
complete, default editing mode, with the mode saved per user. Page access rules
got a single reader, and a rule it cannot read now locks the page instead of
opening it.

**One dialect (3–6 November).** The editor, the server and the reader now agree on
what is code, math, a comment or a tag, because all three read the same parse. A
flexmark defect that let one long paragraph take minutes to parse was fixed.

**Collaborative editing (7 November).** Shared drafts with presence and attributed
publishing shipped dark in `2.5.10` and were switched on in production in
`2.5.11`. The save path was hardened on the way: strict-mode saves, renames and
deletes can no longer silently overtake one another.

**The Knowledge Graph is removed (8–9 November).** The LLM-extracted Knowledge
Graph never measured a retrieval lift. `2.5.12` switched it off and `2.5.13`
deleted it: the admin screens, the MCP tools, the entity classes in the ontology
and the tables. "Similar pages" and agents' related pages now come from page
embeddings. `2.5.15` then removed the last chat-model code paths, the LLM
reranker and query decomposition, so Wikantik calls an embedding model and no
chat model. The MCP surface went from 51 tools to 36.

**On `main`, not yet released (10–12 November).** Private clusters, which hide
every page of a listed cluster from everyone but administrators and named groups
on every surface; the front page served directly at `/`; and sitemap and JSON-LD
dates that move only on a substantial edit.

---

*This page is a narrative summary; the authoritative record of every change is the
git history and the per-release `CHANGELOG`.*
