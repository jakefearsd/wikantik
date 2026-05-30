# Wikantik Development News

A narrative of recent development on Wikantik — the six weeks from mid-April to
the end of May 2026. In that span the project crossed from `1.1.x` to its `2.0`
line (six tagged releases, `2.0.0` through `2.0.5`), went live on real
infrastructure, and shifted its center of gravity from "a wiki that an AI can
read" to "a wiki built, operated, and consumed with AI agents as first-class
citizens."

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

*This page is a narrative summary; the authoritative record of every change is the
git history and the per-release `CHANGELOG`.*
