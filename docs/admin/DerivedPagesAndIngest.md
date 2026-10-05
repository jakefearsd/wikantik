# Derived Pages and Document Ingest

This page is for administrators and curators who turn documents (PDF, Word, plain text
and similar) or external sources into wiki pages. It explains what a **derived page**
is, how to ingest documents through the REST endpoint or the batch script, how to
re-extract pages after an extractor improvement, and how connectors produce derived
pages.

## Understand derived pages

A page is **derived** when its frontmatter has a `derived_from` field. The value is the
name of the source file, which Wikantik keeps as an attachment on the page. The page
**body is machine-owned**: it is regenerated from the retained source whenever the
document is re-ingested or an improved extractor is run across the corpus, and a
regeneration overwrites hand edits to the body. This is deliberate
([ADR-0004](../adr/0004-derived-page-body-is-machine-owned-regenerable.md)): there is no
edit lock and no automatic merge. Page history is the recovery path if you edited a body
and lost it.

Curation that is independent of the body survives regeneration: tags, cluster and type,
verification, Knowledge Graph curation and comments. Existing `type` and `title` are
kept; they are set only when absent.

| Frontmatter field | Meaning |
|---|---|
| `derived_from` | source attachment file name; marks the page as derived |
| `derived_extractor`, `derived_extractor_version` | which extractor produced the body, and its version |
| `derived_source_sha` | SHA-256 of the source bytes, used to skip unchanged re-ingests |
| `derived_connector` | id of the connector that owns the page, when it came from a sync |
| `derived_source_url` | link back to the external origin |
| `derived_orphaned` | `true` when the owning connector was deleted but its pages were kept |

These fields are described for authors in
[Frontmatter.md](../user/Frontmatter.md#derived-page-provenance). The page name comes
from the file name: the extension is dropped, only letters, digits, spaces, hyphens and
underscores are kept, whitespace is collapsed and the result is capped at 200
characters (`Document` if nothing is left).

## Ingest a document over REST

`POST /api/ingest` takes a `multipart/form-data` request with a `file` part
(`DerivedIngestResource`). The caller needs the `createPages` wiki permission; without it
the response is 403. Other failures: 400 if the `file` part is missing or the file name
is invalid, 415 if the request is not multipart.

```bash
curl -u admin:... -X POST 'http://localhost:8080/api/ingest' \
     -F 'file=@/data/docs/Handbook.pdf'

# re-ingest even if the source is unchanged
curl -u admin:... -X POST 'http://localhost:8080/api/ingest?force=true' \
     -F 'file=@/data/docs/Handbook.pdf'
```

The server stores the source as an attachment, extracts a Markdown body with Apache
Tika, and creates or updates the derived page. The response is JSON:

```json
{"page": "Handbook", "status": "created", "message": "created"}
```

`status` is one of `created`, `updated`, `unchanged` (same source SHA and no `force`) or
`failed` (with the reason in `message`, for example an empty extraction). If storing the
attachment fails for a new page, the page is rolled back.

## Ingest a folder with the batch script

`bin/ingest-documents.sh` walks a directory recursively and posts each supported file
(`.pdf .txt .md .docx .pptx .xlsx`) to `/api/ingest`.

```bash
bin/ingest-documents.sh --base-url http://localhost:8080 \
                        --dir /data/docs \
                        --user admin \
                        --password secret
bin/ingest-documents.sh --help       # usage
bin/ingest-documents.sh --jar-help   # full Java CLI flag reference
```

Add `--force` to re-ingest unchanged files. `--user` and `--password` are sent as HTTP
Basic credentials, so the account needs `createPages`. The script rebuilds
`wikantik-extract-cli` if its jar is missing or older than the module's sources, and
logs to standard output; pipe through `tee` to keep a copy.

## Re-extract after an extractor change

When the extraction logic improves, re-run it over the retained sources. These
endpoints are administrator-only and have no admin screen.

```bash
# how many derived pages exist, and how many were made by an older extractor
curl -u admin:... http://localhost:8080/admin/derived/status

# reflow one page
curl -u admin:... -X POST 'http://localhost:8080/admin/derived/reflow?page=Handbook'

# reflow every derived page
curl -u admin:... -X POST http://localhost:8080/admin/derived/reflow
```

`status` returns `derivedTotal`, `staleCount` (pages whose `derived_extractor_version`
is below the current version; a missing or unparseable version counts as stale) and
`currentExtractorVersion`. The counters are cached for
`wikantik.admin.derived.statusCacheTTL` seconds (default `30`, `0` or less disables the
cache) because computing them reads every page; a reflow clears the cache.

A single-page reflow returns `{page, status, message}` as above. A corpus-wide reflow
returns `{"reflowed": n, "skipped": n, "failed": n}`. Non-derived pages and unchanged
results count as skipped, and one failing page is logged and does not stop the run. A
reflow forces re-extraction from the attachment, so it overwrites body edits (see
above).

## Produce derived pages with connectors

Connectors sync external sources (websites, feeds, sitemaps, Google Drive, GitHub,
Confluence) into derived pages on a schedule or on demand. Pages they create carry
`derived_from` plus `derived_connector` and, where useful, `derived_source_url`, so
the reader shows a provenance banner and a derived badge. Each sync regenerates the
bodies. Manage connectors at **Admin → Connectors**; the full guide is
[Connectors.md](Connectors.md), and the screen is described in
[AdminPanel.md](AdminPanel.md#connectors).

## Where to go next

- [Connectors.md](Connectors.md): set up an external-source sync.
- [Frontmatter.md](../user/Frontmatter.md#derived-page-provenance): the provenance fields as an author sees them.
- [the derived-pages design](../superpowers/specs/2026-06-14-derived-pages-design.md): the full design.
