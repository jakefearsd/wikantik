# Search Index Rebuild Guide

## When You Need to Rebuild

The Lucene search index must be rebuilt when:

1. **Frontmatter or content fields change** — The index includes `tags`,
   `cluster`, `summary`, `canonical_id`, `type`, and `audience` from
   YAML frontmatter. Pages indexed before any one of these fields was
   added have no value in the index, so faceted/structural searches
   won't find them. (Note: typed `relations:` frontmatter — `links_to`,
   `mentions`, `part_of` — was retired 2026-05-02 in V023; if you have
   an old index with `relations` fields, a rebuild will drop them and
   that's intentional.)

2. **The index is corrupted** — Search returns no results or throws errors.

3. **The analyzer or field configuration changes** — Any change to how
   content is tokenized or what fields exist requires a full rebuild.

4. **You're enabling hybrid retrieval for the first time** — A rebuild
   re-runs the chunker and re-populates `kg_content_chunks`, which is
   the foundation for `content_chunk_embeddings` and
   `chunk_entity_mentions`. See
   [KnowledgeGraphRerank.md](KnowledgeGraphRerank.md) for the full
   downstream pipeline.

## How to Rebuild

### Live (no Tomcat restart needed)

The fastest path on a running wiki is the `/admin/content/rebuild-indexes`
endpoint, which Wikantik exposes as a one-click admin operation. Use the
`bin/trigger-rebuild-indexes.sh` helper:

```bash
bin/trigger-rebuild-indexes.sh           # kicks off rebuild
bin/trigger-rebuild-indexes.sh status    # polls /admin/content/index-status
bin/trigger-rebuild-indexes.sh --help
```

It logs in as `testbot` (credentials sourced from `test.properties` —
see CLAUDE.md > Manual Testing Credentials) and POSTs to
`/admin/content/rebuild-indexes`. The rebuild runs in a background
thread; search returns 0 results while it's in flight.

For an end-to-end rebuild that *also* re-runs the embedding pipeline
and re-extracts KG entities, use `bin/kg-rebuild.sh` (which orchestrates
this rebuild plus `/admin/content/reindex-embeddings` plus
`bin/kg-extract.sh`).

### Local (bare-metal Tomcat) — full reset

When the live path isn't enough (e.g. a corrupt index), do a full reset.
The Lucene index lives under `wikantik.workDir`, which is configured in
`wikantik-custom.properties` (rendered from the template by
`bin/deploy-local.sh`) and defaults to:

```
tomcat/tomcat-11/data/workdir/
```

The Lucene index directory is therefore:

```
tomcat/tomcat-11/data/workdir/lucene/
```

```bash
# 1. Stop Tomcat
tomcat/tomcat-11/bin/shutdown.sh

# 2. Delete the Lucene index directory
rm -rf tomcat/tomcat-11/data/workdir/lucene/

# 3. Start Tomcat — the index rebuilds automatically on startup
tomcat/tomcat-11/bin/startup.sh

# 4. Wait ~30-60 seconds for the background rebuild to complete
# Check logs for: "Full Lucene index finished in N milliseconds"
tail -f tomcat/tomcat-11/logs/wikantik/wikantik.log | grep -i lucene
```

### Docker (production containers)

```bash
# 1. Stop the wikantik container
bin/container.sh -e prod down       # or: docker compose ... stop wikantik

# 2. Delete the index from the work volume
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm \
  wikantik rm -rf /var/wikantik/work/lucene/

# 3. Start wikantik — index rebuilds automatically
bin/container.sh -e prod up -d

# 4. Monitor the rebuild
bin/container.sh -e prod logs -f wikantik | grep -i lucene
```

## What Happens During Rebuild

1. On startup, `LuceneSearchProvider` checks the `lucene/` directory
2. If empty (no index files), it triggers `doFullLuceneReindex()` in a background thread
3. The rebuild iterates ALL pages and ALL attachments, indexing each one
4. **Search is unavailable** during the rebuild (returns 0 results)
5. Once complete, search resumes with the new index

For ~200 pages, the rebuild takes 1-3 seconds. For 1000+ pages, expect 10-30 seconds.

## What Gets Indexed Per Page

| Lucene Field | Source | Searchable |
|-------------|--------|------------|
| `id` | Page name (exact) | Exact match only |
| `contents` | Full page body text | Full-text |
| `name` | Page name (beautified + raw) | Full-text |
| `author` | Page author | Full-text |
| `attachment` | Attachment filenames | Full-text |
| `keywords` | Page keywords attribute | Full-text |
| `tags` | Frontmatter `tags` field (space-separated) | Full-text |
| `cluster` | Frontmatter `cluster` field | Full-text |
| `summary` | Frontmatter `summary` field | Full-text |

## bin/kg-rebuild.sh — full pipeline rebuild

`bin/kg-rebuild.sh` orchestrates a four-phase rebuild:

1. Chunk + Lucene rebuild (`/admin/content/rebuild-indexes`)
2. Chunk embedding reindex (`/admin/content/reindex-embeddings`)
3. Optional KG reset (`--reset-kg`) or full KG purge (`--purge-kg`)
4. Entity extraction (`bin/kg-extract.sh`)

Each phase polls to completion before the next starts.

### Skip flags

| Flag | Effect |
|------|--------|
| `--skip-chunks` | Skip phase 1 (chunk + Lucene rebuild) |
| `--skip-embeddings` | Skip phase 2 (embedding reindex) |
| `--skip-extract` | Skip phase 4 (entity extraction) |
| `--dry-run` | Print the plan and exit without executing |
| `--reset-kg` | Before phase 4: delete pending proposals + ai-inferred KG nodes |
| `--purge-kg` | Before phase 4: TRUNCATE all KG-layer tables (destructive, requires confirmation) |
| `--yes` / `-y` | Skip interactive confirmation for `--purge-kg` |

Arguments after `--` are forwarded to `bin/kg-extract.sh`:

```bash
bin/kg-rebuild.sh --reset-kg -- \
    --ollama-model qwen2.5:1.5b-instruct \
    --concurrency 6 --prefilter --force
```

### Polling interval env vars

| Variable | Default | Effect |
|----------|---------|--------|
| `POLL_SECONDS` | `10` | How often to poll `/admin/content/index-status` |
| `PROGRESS_SECONDS` | `30` | Minimum seconds between progress lines while RUNNING |

Example:

```bash
POLL_SECONDS=5 PROGRESS_SECONDS=15 bin/kg-rebuild.sh --skip-extract
```

## Verifying the Rebuild

After the index rebuilds, verify with:

```bash
# Should return results (not 0)
curl -s 'http://localhost:8080/api/search?q=Main' | python3 -c "import json,sys; print(json.load(sys.stdin)['total'], 'results')"

# Tag search should work
curl -s 'http://localhost:8080/api/search?q=ai' | python3 -c "import json,sys; print(json.load(sys.stdin)['total'], 'results for ai tag')"
```

## No Rebuild Needed For

- Adding/editing/deleting individual pages (the index updates incrementally)
- Restarting Tomcat (the existing index persists in the work directory)
- Deploying a new WAR that doesn't change indexing logic
