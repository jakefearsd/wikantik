# Search Index Rebuild Guide

## When You Need to Rebuild

The Lucene search index must be rebuilt when:

1. **Frontmatter indexing was added** — The index now includes `tags`, `cluster`, and `summary` fields from YAML frontmatter. Pages indexed before this change have no frontmatter fields in the index, so tag/cluster searches won't find them.

2. **The index is corrupted** — Search returns no results or throws errors.

3. **The analyzer or field configuration changes** — Any change to how content is tokenized or what fields exist requires a full rebuild.

## How to Rebuild

### Local (bare-metal Tomcat)

```bash
# 1. Stop Tomcat
tomcat/tomcat-11/bin/shutdown.sh

# 2. Delete the Lucene index directory
rm -rf tomcat/wikantik-work/lucene/

# 3. Start Tomcat — the index rebuilds automatically on startup
tomcat/tomcat-11/bin/startup.sh

# 4. Wait ~30-60 seconds for the background rebuild to complete
# Check logs for: "Full Lucene index finished in N milliseconds"
tail -f tomcat/tomcat-11/logs/jspwiki/jspwiki.log | grep -i lucene
```

### Docker (production containers)

```bash
# 1. Stop the wikantik container
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik

# 2. Delete the index from the work volume
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm \
  wikantik rm -rf /var/wikantik/work/lucene/

# 3. Start wikantik — index rebuilds automatically
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik

# 4. Monitor the rebuild
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f wikantik | grep -i lucene
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

## Verifying the Rebuild

After the index rebuilds, verify with:

```bash
# Should return results (not 0)
curl -s 'http://localhost:8080/api/search?q=Main' | python3 -c "import json,sys; print(json.load(sys.stdin)['total'], 'results')"

# Tag search should work (new feature)
curl -s 'http://localhost:8080/api/search?q=ai' | python3 -c "import json,sys; print(json.load(sys.stdin)['total'], 'results for ai tag')"
```

## No Rebuild Needed For

- Adding/editing/deleting individual pages (the index updates incrementally)
- Restarting Tomcat (the existing index persists in the work directory)
- Deploying a new WAR that doesn't change indexing logic
