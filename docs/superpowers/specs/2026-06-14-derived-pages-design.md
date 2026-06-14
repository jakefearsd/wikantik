# Phase 2 — Derived Pages

**Status:** Approved design (2026-06-14). Part of the RAG-as-a-Service program
(`docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md`).
**Governing ADR:** `docs/adr/0004-derived-page-body-is-machine-owned-regenerable.md`.
**Rides:** the existing page rails (search, embeddings, Knowledge Graph, ontology, chunking,
the Phase 1 context bundle) and `AttachmentManager` / `VersioningFileProvider`.

## Why this phase exists

Ingestion breadth. Today only hand-authored wiki pages flow into retrieval, the Knowledge
Graph, the ontology, and the RAG bundle. Phase 2 lets **PDFs, office documents, and text/markdown
files become first-class wiki pages** — *derived pages* — so document corpora flow into every
rail. The source binary is retained as the durable source-of-truth; the page body is a
**regenerable projection** that an improved extractor can reflow across the whole corpus
cheaply and losslessly (ADR-0004).

## Confirmed decisions

1. **Marker = `derived_from:` presence, not a `PageType`.** "Derived" is *provenance*, orthogonal
   to the semantic `type:` (article/reference/…). A page is derived iff its frontmatter carries
   `derived_from`.
2. **Idempotency = filename-named page, update-in-place on re-ingest, sha-dedup.** Re-ingesting the
   same source updates the existing derived page (new version); an unchanged source sha is a no-op
   unless forced.
3. **Reflow is manual-only in v1.** A staleness count (pages whose extractor version is behind)
   tells the operator when to run it. No auto-reflow.
4. **Extraction fidelity = structured-markdown where Tika provides it, flat-text fallback.**
   Headings are load-bearing (the chunker turns them into `heading_path` for sections + citations).
5. **Scope:** PDF + plain text/markdown + office (docx/pptx/xlsx). **Entry points:** both a REST/UI
   upload path and a CLI/batch path, over one shared ingestion service.

## A — Data model & provenance *(foundation; everything else rides on it)*

A derived page is an ordinary markdown wiki page. Its frontmatter carries:

```yaml
derived_from: ResearchPaper.pdf      # the retained source attachment on this page (the marker)
derived_extractor: tika              # extractor id that produced this body
derived_extractor_version: 1         # integer; drives reflow-staleness detection
derived_source_sha: 9f86d081884c…    # SHA-256 of the source bytes; idempotency / dedup
```

- The **source binary is stored as an attachment on the derived page** via `AttachmentManager`
  (keyed by page + original filename). It is the provenance anchor, never discarded.
- The page rides `StructuralSpinePageFilter` for its `canonical_id` like any page; the semantic
  `type:` defaults to `reference` (overridable) and is independent of derived-ness.
- Per ADR-0004, the **body-independent curation layers** (frontmatter, tags/cluster, KG entity
  curation, verification status, comments) survive reflow; the body does not.
- **Page naming:** the slugified **source filename** — deliberately *not* the extracted title.
  The name must be stable across reflow; an improved extractor can change the extracted title, and
  if that drove the page name a reflow would rename/orphan the page and break update-in-place. The
  extracted title instead populates a frontmatter `title:` for display. On a name collision whose
  `derived_source_sha` differs (a *different* document mapping to the same name), disambiguate with
  a numeric suffix rather than clobber.

Constants/helpers live in `com.wikantik.derived.DerivedPage` (the frontmatter keys + a
`isDerived(metadata)` / `derivedFrom(metadata)` accessor) so every piece agrees on the contract.

## B — Source extraction *(pure, fully independent — the Tika wrapper)*

```java
interface SourceExtractor {
    ExtractionResult extract(InputStream source, String contentType, String filename) throws ExtractionException;
    boolean supports(String contentType);
}
record ExtractionResult(String markdownBody, String extractedTitle, Map<String,String> metadata) {}
```

- **Tika-backed implementation:** `AutoDetectParser` + a structure-preserving content handler
  (`ToXMLContentHandler`) → an XHTML→markdown step that keeps headings, lists, and tables where
  Tika emits them, with a **flat-text fallback** (`BodyContentHandler`) when structure is absent.
- **Per-format reality, documented:** PDFs have weaker heading structure than office docs;
  spreadsheets render as markdown tables; slide decks render as per-slide sections. **Scanned /
  image-only PDFs (no text layer)** are out of scope for v1 — OCR (Tesseract) is deferred; an
  empty/whitespace-only extraction is detected and surfaced as a warning rather than silently
  producing a blank page.
- No coupling to the wiki engine — a stream in, markdown out. Unit-tested against small committed
  fixture documents (one PDF, one docx, one pptx, one xlsx, one txt) with asserted heading/table
  structure in the output.

## C — Ingestion *(two thin entry points over one service)*

`DerivedPageIngestionService.ingest(byte[] source, String filename, String contentType, IngestOptions opts) → IngestResult`:

1. Compute `derived_source_sha`. If a derived page with this name already exists and its sha is
   unchanged and `!opts.force()`, **no-op** (return `UNCHANGED`).
2. Store the source bytes as an attachment on the target page (`AttachmentManager.storeAttachment`).
3. Run the `SourceExtractor` → markdown body + title.
4. Assemble frontmatter (`derived_from`, `derived_extractor`, `derived_extractor_version`,
   `derived_source_sha`, default `type: reference`) and `saveText` the page — which rides every
   existing rail (canonical_id, search, embeddings, KG, ontology, chunking).

Entry points (independent build units, both thin):
- **REST/UI:** a multipart upload endpoint (a sibling to `AttachmentResource`, e.g.
  `POST /api/ingest`) that hands the part to the ingestion service and returns the derived page
  name. The SPA's attachment-upload affordance gains an "ingest as derived page" action.
- **CLI/batch:** a new `IngestDocumentsCli` in `wikantik-extract-cli` that walks a folder and POSTs
  each supported document to the REST ingest endpoint of a **running** wiki instance, reporting
  created/updated/skipped. It is a thin HTTP client — deliberately *not* JDBC-direct like the other
  CLIs in that module, because a full page-save (canonical_id, filters, search/embedding indexing,
  KG) requires the engine. This reuses the entire ingestion rail and matches the existing
  operational pattern of pushing content to a live instance. Auth via an admin token/credentials.

## D — Reflow *(admin op; independent of C; needs A+B)*

Mirrors `/admin/ontology/rebuild`:

- `POST /admin/derived/reflow` — single page (`?page=`) or corpus-wide. For each target derived
  page: re-read the **retained source attachment**, re-extract with the *current* extractor,
  **clobber only the body**, preserve the body-independent frontmatter/tags/KG/verification/
  comments, and `saveText` a new version (recovery via `VersioningFileProvider`).
- `GET /admin/derived/status` — counts derived pages whose `derived_extractor_version` is behind
  the current extractor version (the operator's signal to reflow) plus totals.
- **Manual-triggered only** in v1 (ADR-0004's "clear forceful action, clear forceful consequence").

## E — Rails + content-type-aware chunking

Derived pages ride search/embeddings/KG/ontology for free (the body is markdown). The single
touch-point is the spec's **content-type-aware chunking *strategy selection***: the chunker picks
a strategy keyed off the source content type (carried via `derived_from` / the source's MIME),
**flat-and-tuned for now** — parent-child chunking stays deferred until the bundle-quality harness
shows boundary-straddling misses. **Exit gate: the harness shows no section-recall regression**
with PDFs/docs in the corpus.

## Cross-cutting

- **Edit-at-own-risk (ADR-0004):** no locking, no merge, no edit-protection. `VersioningFileProvider`
  history is the recovery path. UI affordance: the editor shows a "machine-owned body — a reflow
  overwrites hand edits; curate in frontmatter/tags/KG" banner when `derived_from` is present.
- **Placement:** new `com.wikantik.derived.*` (wikantik-main) — `DerivedPage` constants,
  `SourceExtractor` + `TikaSourceExtractor`, `DerivedPageIngestionService`, `DerivedReflowService`.
  REST: `DerivedIngestResource` + `AdminDerivedResource` (wikantik-rest). CLI: `IngestDocumentsCli`
  (wikantik-extract-cli). No new Maven module.
- **Security/ACL:** ingestion + reflow are write/admin operations — gate the REST ingest behind the
  same page-create permission the editor uses; `/admin/derived/*` behind `AdminAuthFilter`. A
  derived page is an ordinary page for view-ACL purposes (inherits the standard model).

## Testing approach (TDD)

- `TikaSourceExtractorTest` — fixture PDF/docx/pptx/xlsx/txt → assert markdown body + preserved
  headings/tables + extracted title; empty-extraction (no text layer) → flagged, not blank.
- `DerivedPageIngestionServiceTest` (mocked `AttachmentManager` + `PageManager`) — source stored as
  attachment; frontmatter assembled (all `derived_*` keys); idempotent re-ingest (same sha → no-op;
  changed sha → new version; name collision w/ different sha → suffix).
- `DerivedReflowServiceTest` — body clobbered, frontmatter/tags/verification preserved, new version
  written; staleness count correct.
- Wire-level IT — upload a fixture PDF via the REST endpoint → derived page exists, is searchable,
  and appears in `/api/bundle`; reflow bumps the version and preserves a hand-added tag.
- Harness regression — derived pages in the eval corpus, section-recall not regressed.

## Out of scope (deferred)

- Auto-reflow on extractor-version change (manual only in v1; the staleness count drives it).
- Parent-child / content-type-specialized chunking beyond strategy selection (harness-gated).
- OCR for scanned/image-only PDFs (Tesseract) — empty extraction is flagged, not OCR'd.
- Three-way merge / edit-protection / locking (ADR-0004 explicitly rejects these).
