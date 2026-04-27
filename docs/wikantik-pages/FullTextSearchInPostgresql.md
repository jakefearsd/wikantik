---
canonical_id: 01KQ0P44QJTG4MR5M81CRQMK7A
title: Full-Text Search in PostgreSQL
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: PostgreSQL's built-in full-text search — tsvector, tsquery, indexes, and
  the cases where it's enough vs. where you need Elasticsearch.
tags:
- postgresql
- full-text-search
- tsvector
- search
- databases
related:
- ElasticsearchFundamentals
- CloudDatabases
- ReadReplicasAndReplication
---
# Full-Text Search in PostgreSQL

PostgreSQL has full-text search built in. For many applications, it's enough — no separate Elasticsearch cluster, no syncing infrastructure. The same database that holds the data does the search.

This page covers when it's the right choice and how to use it.

## The basics

### tsvector and tsquery

PostgreSQL stores searchable text as `tsvector` (tokenized; sorted; deduplicated):

```sql
SELECT to_tsvector('english', 'The quick brown fox jumps over the lazy dog');
-- 'brown':3 'dog':9 'fox':4 'jump':5 'lazi':8 'quick':2
```

Stop words removed; words stemmed; positions tracked.

### Searching

```sql
SELECT * FROM articles
WHERE to_tsvector('english', body) @@ to_tsquery('english', 'fox & dog');
```

The `@@` operator matches a tsvector against a tsquery.

### Generated column

For performance, store the tsvector:

```sql
ALTER TABLE articles
ADD COLUMN search_vector tsvector
GENERATED ALWAYS AS (to_tsvector('english', body)) STORED;
```

Now searches don't recompute the vector each time.

### GIN index

For fast lookup:

```sql
CREATE INDEX articles_search_idx ON articles USING GIN (search_vector);
```

GIN indexes for tsvector are the standard.

## Ranking

Rank results by relevance:

```sql
SELECT title, body,
       ts_rank(search_vector, query) AS rank
FROM articles, to_tsquery('english', 'fox & dog') query
WHERE search_vector @@ query
ORDER BY rank DESC
LIMIT 20;
```

`ts_rank` produces a relevance score. Higher rank = more relevant.

## Phrase and proximity

```sql
-- Phrase
to_tsquery('english', 'quick <-> brown')  -- "quick" immediately followed by "brown"

-- Proximity
to_tsquery('english', 'quick <2> dog')  -- within 2 words
```

For exact phrase matching.

## Multiple languages

```sql
to_tsvector('spanish', body)
to_tsvector('french', body)
```

PostgreSQL ships with stemming dictionaries for major languages. For others, additional dictionaries available.

## Highlighting

Show matched terms in results:

```sql
SELECT ts_headline('english', body, query, 'StartSel=<b>, StopSel=</b>')
FROM articles, to_tsquery('english', 'fox') query
WHERE search_vector @@ query;
```

Returns body with matched terms wrapped in `<b>` tags.

## When PostgreSQL FTS is enough

- **Small to moderate corpus**: thousands to millions of documents
- **Single-language or few languages**: dictionaries available
- **Simple ranking**: tf-idf-like scoring works
- **You already have PostgreSQL**: no separate infrastructure
- **No real-time aggregations needed**: just search

For typical CRUD apps with search, this is enough.

## When you need Elasticsearch

- **Very large scale**: tens of millions+ documents with high query rate
- **Sophisticated relevance tuning**: machine-learning ranking, synonyms, custom scoring
- **Faceted search**: aggregations, drill-downs
- **Real-time analytics**: not just search
- **Heavy aggregations**: percentiles, histograms over search results

See [ElasticsearchFundamentals](ElasticsearchFundamentals).

## Specific patterns

### Multiple fields with weights

```sql
SELECT title, body,
       ts_rank(
           setweight(to_tsvector('english', title), 'A') ||
           setweight(to_tsvector('english', body), 'B'),
           query
       ) AS rank
FROM articles, to_tsquery('english', 'fox') query;
```

Title matches weighted higher than body.

### Trigram for fuzzy matching

PostgreSQL's pg_trgm extension provides trigram-based similarity:

```sql
CREATE EXTENSION pg_trgm;

SELECT * FROM articles
WHERE title % 'foks';  -- "%" is similarity operator
```

Useful for handling typos, partial matches.

### Combining FTS and trigram

For autocomplete: trigram for prefix matching; FTS for full-content.

### JSON full-text

PostgreSQL's JSONB columns can be searched with FTS too:

```sql
SELECT * FROM events
WHERE to_tsvector('english', data->>'description') @@ to_tsquery('english', 'fox');
```

For semi-structured data.

## Performance considerations

### Index maintenance

GIN indexes are slower to update than B-tree. For high-write workloads, consider GIN with `fastupdate=on` (writes go to a pending list; consolidated periodically).

### Index size

GIN indexes are large. Plan storage accordingly.

### Generated column vs. trigger

Generated column (PostgreSQL 12+) recomputes on update; transparent.
Trigger-based updates are flexible but require maintenance.

For modern PostgreSQL, generated columns are simpler.

### Reindexing

Bulk loads: drop the index; load; recreate. Faster than incremental.

## Common failure patterns

- **Searching without indexes.** Sequential scans of huge tables.
- **Wrong language config.** English stemmer on Spanish content.
- **Computing tsvector per query.** Use stored generated column.
- **Ignoring relevance.** Listing matches without ranking.
- **Choosing Elasticsearch when Postgres would suffice.** Extra infrastructure for small needs.

## A reasonable starter

For a typical app needing search:

1. Add `tsvector` column with appropriate language
2. GIN index on the column
3. Use `ts_rank` for ordering
4. Iterate based on user feedback
5. Migrate to Elasticsearch if scale or features require it

Most apps never need to migrate.

## Further Reading

- [ElasticsearchFundamentals](ElasticsearchFundamentals) — When Postgres isn't enough
- [CloudDatabases](CloudDatabases) — PostgreSQL options
- [ReadReplicasAndReplication](ReadReplicasAndReplication) — Adjacent scaling
