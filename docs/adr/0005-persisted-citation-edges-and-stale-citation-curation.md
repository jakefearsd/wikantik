# Persisted citation edges, version-pinned, with span-level stale-citation curation

When an agent (or human) grounds a claim in page content against another page, that grounding
is persisted as a **Citation edge** — a distinct edge type, separate from wikilinks (the Page
Graph) and from the *ephemeral bundle citations* RAG-as-a-Service attaches to query results.

**Representation.** Citations are written as **inline markup in the page body** and parsed at
save into a derived `citations` edge table, exactly mirroring how wikilinks are parsed from
bodies into the Page Graph: the body is the self-contained source-of-truth, the table is a
queryable index. Rejected: storing citations as out-of-band structured metadata — the body
and the metadata inevitably drift apart, which defeats grounding. A citation is human-visible
in the prose, exports in raw markdown, and re-derives on reflow.

**The handle is version-pinned.** Each citation pins the target `canonical_id`, page
*version*, section heading-path, the verbatim span, and a **content hash of that span**.
Pinning the citation does **not** freeze the cited content — the knowledge base is expected
to be highly dynamic (agentic editing changes it rapidly and sometimes dramatically). The pin
is the *observability layer* that makes rapid change safe to cite from: it lets the system
detect when the ground moved under a claim instead of silently misrepresenting it.

**Staleness is graded and span-level.** *Version drift* (target version moved) is a near-
constant, meaningless pre-filter in a fast-churning base. "Stale" means **span drift** — the
cited span's content hash no longer matches — i.e. the specific thing cited actually changed.
An optional, expensive *semantic-contradiction* check (LLM) runs only on span-drifted, high-
value citations. Staleness is a **patient curation task, never a save-time error and never
alarming** — churn is the steady state.

**Surfaces (human–machine parity).** Each page exposes its stale citations both ways —
**outbound** (the stale citations it makes) and **inbound** (citations others make to a span
of it that has since changed) — in the page-owner/admin UI (extending the `/admin/drift`
burn-down dashboard) *and* via an MCP tool (`list_stale_citations`) plus the for-agent
projection.

**Consequence — the self-healing loop.** Span-drift queues a citation; agents patiently
re-ground it via RAG-as-a-Service and re-pin; the queue drains. The base heals its own
grounding as fast as it churns, with agents as the maintenance crew and humans working the
same queue. This is the intended agentic workflow, not a side effect.
