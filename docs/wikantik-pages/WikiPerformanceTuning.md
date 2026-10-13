---
status: active
date: '2026-04-26'
title: Wiki Performance Tuning
related:
- WikiSearchOptimization
- WikiPluginDevelopment
- DatabasePerformanceMonitoringHub
canonical_id: 01KQ0P44Z3K0P2E7GKKQ4MJJ5M
cluster: wikantik-development
type: article
tags:
- wiki
- performance
- caching
- wikantik-development
summary: Where wikis get slow — rendering, search, large pages, plugin overhead —
  and the patterns for keeping wiki performance acceptable as content grows.
---
# Wiki Performance Tuning

Wikis get slow as content grows. Pages render slower; searches lag; admin operations time out. Most wikis hit performance limits before they hit content limits.

This page covers where wikis get slow and the patterns for fixing it.

## Where wikis get slow

### Rendering

Wiki markup → HTML conversion. For complex pages with macros, plugins, transclusions, this can be slow.

### Search

Indexing many pages; querying with complex filters. Search performance often the most-visible bottleneck.

### Database

Page reads, writes, history queries. Standard database concerns. See [DatabasePerformanceMonitoringHub](DatabasePerformanceMonitoringHub).

### Large pages

Pages with thousands of lines, hundreds of links, embedded content. Each render is expensive.

### Plugins

Each plugin adds overhead. Plugin-heavy wikis are slower than minimalist ones.

### Many pages

Page lists, sidebars showing recent changes, categorical views. As page count grows, these get slow.

## Caching

### Page-level cache

Rendered HTML cached. Re-rendering only when source changes.

For most wikis, this is essential. Without it, every page view re-renders.

### Fragment cache

Parts of a page (sidebar, navigation, transclusions) cached separately. Updates to one part don't invalidate the whole.

### Database query cache

Common queries cached. Avoid hitting the database for every page navigation.

### CDN

For mostly-read public wikis, CDN caches rendered pages. Massive performance win.

For internal wikis, CDN may not apply.

### Cache invalidation

When does cached content become stale? Page edited; plugin reconfigured; user permissions changed.

Cache invalidation strategies:
- Time-based (TTL): expire after N minutes
- Event-based: invalidate when source changes
- Tag-based: invalidate by tag (e.g., "cache for user X")

For wikis, event-based is usually right — cache until something changes.

## Specific patterns

### Lazy loading

For long pages, load only the visible portion initially. Load more as user scrolls.

For wiki sidebars: load related content on demand.

### Pagination

Page lists, search results: paginated. Don't return 10,000 items.

### Defer plugins

Plugins that don't need to render synchronously: defer. Load page; load plugin output asynchronously.

### Async rendering

For very expensive operations, render in background. Show "rendering..." until done.

### Database indexes

Missing indexes on commonly-queried fields slow everything. Profile slow queries; add indexes.

### Connection pooling

Database connections expensive. Pool them. See [DatabaseConnectionSecurity](DatabaseConnectionSecurity).

## Search performance

Search is often the slowest piece.

### Indexing

Index pages incrementally. Don't rebuild from scratch on each change.

### Index size

Indexes get large. Plan for it.

### Query optimization

Common queries optimized. Filters that match many pages need indexes.

### Search backend

For large wikis, dedicated search (Elasticsearch, Solr) outperforms database-backed search dramatically.

See [ElasticsearchFundamentals](ElasticsearchFundamentals) and [WikiSearchOptimization](WikiSearchOptimization).

## Page-specific issues

### Huge pages

Pages with thousands of lines render slowly. Consider:
- Splitting into multiple pages
- Lazy loading sections
- Caching aggressively

### Many transclusions

Pages including content from many other pages. Each transclusion adds rendering time.

### Heavy plugins

A page using 20 plugins. Each plugin runs on render. Combined, very slow.

Audit plugin usage. Remove unused; combine where possible.

### Recursive links

Page A links to B which links back to A. Plugins that follow links can recurse.

Limit recursion depth.

## Capacity planning

### Page count

How many pages can the wiki handle before performance degrades?

For most wikis: a few thousand pages is fine. Tens of thousands need work. Hundreds of thousands need real engineering.

### Concurrent users

Reads scale well; writes scale less. Many concurrent edits compete for locks.

For most internal wikis, concurrent-user count is limited; this isn't an issue.

### Plugin count

More plugins = more risk. Audit; remove dead.

## Operational practices

### Performance monitoring

Page render times; search times; database query times. Monitor.

### Slow-query log

Database slow-query log. Find slow operations; optimize.

### Profiling

For specific slow pages, profile to find the bottleneck.

### Periodic review

Performance degrades over time. Periodic review catches drift.

## Specific tuning examples

### MediaWiki

- ParserCache for rendered HTML
- ObjectCache (Memcached/Redis) for fragments
- Database read replicas
- Job queue for async operations

### Confluence

- Tuning JVM heap for Confluence
- Database tuning
- Cluster nodes for scale

### Self-built wiki

- Standard web app performance practices
- Cache aggressively
- Database indexes
- Async work

### Wikantik

- **Render caches.** EhCache, configured in `ehcache-wikantik.xml` in the `wikantik-cache` module: the rendering and HTML caches hold up to 10K entries with a 1-hour TTL, the page, page-text and page-history caches 10K entries with a 60-second TTL, and the attachment and attachment-collection caches 1K entries. `wikantik.cache.enable` (default `true`) is the master switch. Metrics for every cache, including hit rates, appear on the Prometheus `/metrics` endpoint.
- **Plugins bypass the shared caches.** A page that runs any plugin is marked viewer-sensitive and is kept out of the principal-less render caches, so plugin-heavy pages are re-rendered more often than plain ones. See [WikiPluginDevelopment](WikiPluginDevelopment).
- **Distributed caching.** `wikantik-cache-memcached` is an opt-in Memcached adapter for multi-node deployments. It is not wired into the WAR: override the `CachingManager` mapping in `classmappings.xml`, put the jar on the container classpath, and set `wikantik.cache.memcached.servers`.
- **Search.** BM25 (Lucene) plus dense vectors, fused by reciprocal rank fusion. `wikantik.search.dense.backend` defaults to `lucene-hnsw`, an in-process index; `pgvector` reads through to the database, and `inmemory` is the older brute-force index. See [HybridRetrieval](HybridRetrieval).
- **Overload protection.** `BackpressureFilter` caps in-flight requests at `WIKANTIK_MAX_INFLIGHT_REQUESTS` (default 390, deliberately just under Tomcat's `maxThreads`, which is 400 on the docker1 deployment) and answers the excess with `503` and `Retry-After: 1` instead of queueing; `/api/health` and `/metrics` are exempt. `RateLimitFilter` adds per-client-IP limits: 25 requests per second by default, and a tighter tier (3 per client, 10 global) for the embedding-heavy `/api/bundle`, `/api/search` and `/sparql`. Loopback callers are never limited. The limits are set with the `WIKANTIK_RATELIMIT_*` environment variables.
- **Measuring it.** The k6 harness is `bin/loadtest.sh smoke|load|stress`, and `docs/developer/LoadTesting.md` in the repository covers pairing a run with JFR recordings, Prometheus and thread dumps. `docs/developer/ScalingCharacterization.md` records a sweep that found the knee for a single 16-core host.

## Common failure patterns

- **No caching.** Every page view re-renders.
- **Cache without invalidation.** Stale content forever.
- **Many plugins, all loaded.** Pages slow.
- **No search optimization.** Search = slow database scan.
- **No indexes.** Queries are full scans.
- **No monitoring.** Don't know what's slow.
- **Linear-time operations on growing data.** Eventually unsustainable.

## A reasonable approach

For most wikis:

1. Page-level rendering cache
2. Database connection pooling
3. Indexed queries
4. Search via dedicated backend if pages > 5000
5. Performance monitoring
6. Periodic profiling of slow pages
7. Plugin audit (remove unused)

## Further Reading

- [WikiSearchOptimization](WikiSearchOptimization) — Search-specific
- [WikiPluginDevelopment](WikiPluginDevelopment) — Plugin overhead
- [DatabasePerformanceMonitoringHub](DatabasePerformanceMonitoringHub) — DB layer
