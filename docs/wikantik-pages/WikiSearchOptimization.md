---
related:
- WikiAnalyticsAndEngagement
- WikiPageTemplates
- ElasticsearchFundamentals
- FullTextSearchInPostgresql
title: Wiki Search Optimization
cluster: wikantik-development
tags:
- wiki
- search
- relevance
- wikantik-development
is_comparable_to:
- Solr
canonical_id: 01KQ0P44Z5D81GQV4GNZ896036
uses:
- BM25
date: '2026-04-26'
status: active
summary: How to make wiki search work well — relevance, ranking, query understanding,
  and the patterns that distinguish a wiki users can find things in vs. one they can't.
type: article
is_alternative_to:
- MySQL
---
# Wiki Search Optimization

Search is the primary way users find content in larger wikis. Browsing works for small wikis; for thousands of pages, search dominates.

Bad search means users can't find what's there. The wiki has the answer; users don't.

This page covers the patterns for good wiki search.

## What makes search work

### Relevance ranking

Pages most relevant to the query come first. Not chronological; not alphabetical.

Standard scoring: BM25 or similar. Modifications:
- Exact title match boosts
- Tag match boosts
- Recency factor (recent pages slight boost)
- Popularity factor (high-traffic pages slight boost)

### Stemming and stopwords

"running" matches "run." "the" doesn't match anything.

Most search libraries handle this by default per language.

### Query understanding

User searches "how to deploy"; finds pages titled "Deployment Guide" even without the words.

Synonym expansion; concept matching; semantic search (with embeddings).

### Multi-field matching

Title, body, tags, headings, comments. Different weights:
- Title match: high weight
- Heading match: medium
- Body match: lower
- Comment match: lowest (or skipped)

### Faceted filtering

Search "deployment" → narrow by section, tags, date, author.

### Typo tolerance

"deplyoment" still finds "deployment." Edit-distance matching.

## Implementation options

### Database full-text search

For small wikis, the database's built-in search. PostgreSQL's `tsvector`; MySQL's full-text.

Simple; same infrastructure. See [FullTextSearchInPostgresql](FullTextSearchInPostgresql).

For larger wikis, performance ceiling.

### Dedicated search engine

Elasticsearch, Solr, OpenSearch. Indexed; fast; feature-rich.

For wikis at scale, this is the right answer. See [ElasticsearchFundamentals](ElasticsearchFundamentals).

### Cloud-managed

AWS OpenSearch, Algolia, Azure Cognitive Search. Less ops; managed scaling.

### Vector / semantic search

Embeddings-based. "Conceptually similar" rather than "word-match."

For wikis with diverse vocabulary, semantic search finds pages keyword search misses.

Combined with keyword search (hybrid retrieval), often the best results.

### Wikantik approach

Wikantik uses BM25 + dense embeddings for hybrid retrieval. KG reranking is **off by default** (boost=0, never wired into production; shelved 2026-06-16 after a measured zero-lift ceiling spike). See `KnowledgeGraphRerank`.

This pattern (hybrid retrieval) has emerged as the modern best-of-breed for wikis with diverse content.

## Indexing

### Initial index

Crawl all pages; tokenize; index. Can be slow for large wikis.

### Incremental indexing

Page edited → incrementally update index. Don't rebuild from scratch.

### Re-indexing

Periodically, full reindex. Catches inconsistencies; applies any indexing changes.

### Async vs. sync

Sync: edit blocks until indexed.
Async: edit returns; indexing happens in background.

Async is usually better. Brief lag between save and searchability is acceptable.

## Page authoring for searchability

### Titles matter

Titles are searched first. Use natural-language titles users might search for.

Bad: "Wiki Page 47"
Good: "How to deploy to production"

### Headings as anchors

H2/H3 headings are searched. Helpful for finding specific sections within pages.

### Frontmatter tags

Explicit tags help filtering and topic-based discovery.

### First paragraph

Many search engines weight first paragraph higher. Lead with the page's purpose.

### Synonyms in body

Cover terms users might search for, even alternatives. "User" and "customer" might both apply; use both.

### Meta descriptions

For search-result snippets. Brief, accurate.

## Operational practices

### Monitor failed searches

Searches that return no results: gaps. Either content missing or content not findable.

### Top searches

Most-searched queries: invest in those areas. Improve titles; expand content.

### Click-through rate

For each search query, what percentage of users click a result? Low CTR = bad search results.

### Search vs. navigate split

How often users search vs. click links. Reveals search adoption.

For most modern wikis, search dominates after a certain size.

## Specific patterns

### Did-you-mean

For typos: "Did you mean: deployment?"

### Related searches

After viewing results: "Other people searched for X."

### Autocomplete

As user types, suggest queries. Reduces query effort.

### Recently viewed

Personalization: surface recently-viewed pages.

### Popular pages

Highlight high-traffic pages.

## Common failure patterns

### Database full-text search at scale

Slow. Eventually unusable.

### No relevance tuning

Results in wrong order. Right page on page 10 of results.

### No synonym handling

User searches term A; page uses term B. No match.

### Title-only search

Body content not searched. Missing matches.

### No stemming

"deploy" doesn't match "deployment."

### No analytics

Don't know what searches fail.

### Misindexed (stale)

Recent pages not searchable; old content searched as if current.

## A reasonable starter

For wikis up to a few thousand pages:

1. Database full-text search initially
2. Migrate to Elasticsearch when search is slow
3. Hybrid (keyword + semantic) for diverse content
4. Track failed searches; fill content gaps
5. Tune relevance based on click-through rates
6. Auto-complete and synonym handling

For larger or more complex wikis: dedicated search infrastructure from the start.

## Further Reading

- [WikiAnalyticsAndEngagement](WikiAnalyticsAndEngagement) — Search analytics
- [WikiPageTemplates](WikiPageTemplates) — Templates affect findability
- [ElasticsearchFundamentals](ElasticsearchFundamentals) — Dedicated search
- [FullTextSearchInPostgresql](FullTextSearchInPostgresql) — DB search
