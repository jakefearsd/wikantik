---
canonical_id: 01M5K177EHVRQMY5WV135AXZ7D
title: 'Database and Disk Profiling: Findings and Repairs (October 2026)'
type: article
cluster: wikantik-development
status: active
date: '2026-10-22'
tags:
- wikantik
- performance
- profiling
- postgresql
- storage
- wikantik-development
summary: How a 2026-10-22 profiling run traced every database statement and disk syscall in Wikantik, what it found, and how each defect was repaired and verified.
related:
- WikiPerformanceTuning
- HybridRetrieval
- WikantikArchitecture
- WikantikKnowledgeGraph
---
# Database and Disk Profiling: Findings and Repairs (October 2026)

On 2026-10-22 we profiled Wikantik with one question: **what does it do badly against PostgreSQL and the local disk?** Unlike earlier CPU campaigns, frequency was deliberately ignored. A query that runs once a week counted as much as one that runs on every request. So the run exercised every route and every MCP tool once, plus startup, idle time, shutdown, maintenance actions and a short load test.

The run found two destructive bugs, one production storage problem and a long tail of wasted queries and disk work. All the critical and important defects were repaired test-first: each repair has a regression test in the regular unit suite that was seen to fail before the fix. The fixed build was then profiled again with the same harness.

## Results at a glance

| Measurement | Before | After |
|---|---|---|
| Ontology rebuild, 8,733 graphs (cold start) | 6.5 min; 279,472 fsync + 122,282 msync; **16.6 GB written**; 1.84 GB store | about 3 s; 1 write transaction; 16 MB store |
| Second rebuild of identical content | store +1.53 GB, 14.6 GB written | no growth; nothing committed |
| Production ontology store | 79.7 GB, never compacted | compacts ~5 min after the next deploy, then weekly |
| Page save, request-thread syncs | 168–180 fsync/msync; 180–277 ms | 2–4 fsyncs (the new durable page write); 17–42 ms |
| "Rebuild indexes" (admin) | deleted all 18,627 embeddings and every KG mention; 1,213 Lucene commits; 64 s | keeps unchanged chunks, embeddings and mentions; 7 Lucene commits in the whole run |
| Idle wiki, every 5 minutes | ~2,600 property-file reads + whole page-text cache evicted | ~490 property-file reads |
| `/api/changes`, `/api/recent-changes`, `/api/me/pages` | 450–455 file opens per request | 0 |
| `/feed.xml` | 307 file opens per request | 0 |
| Search, 2-minute load | ~80,000 index-file opens, 7,032 directory listings | index reader shared and reused |
| `/api/pages/for-agent/{id}` (anonymous) | 182 statements | 5 |
| `/api/search` (cold cache) | 204 statements | 4 |
| `/api/bundle` | 28 statements | 2 |
| `/admin/agent-grade-audit?limit=100` | 2,377 statements | 5 |
| `/admin/verification?limit=200` | 413 statements | 5 |
| `/admin/overview` | 245 statements | 19 |
| `/admin/knowledge-graph/proposals?limit=50` | 117 statements | 9 |
| Startup | all vectors loaded twice; 2,326 unchanged rows rewritten | loaded once; no canonical-id rows rewritten |

## How the run worked

- **An isolated copy, not the dev install.** A throwaway PostgreSQL 18 + pgvector container held a restore of the local database, with `pg_stat_statements` and every statement logged with its parameters. The wiki ran against a copy of the pages and an empty work directory, so a cold start rebuilt every derived store.
- **Disk tracing.**
  - `strace -f` recorded every file-system syscall with its path and duration: open, stat, directory listing, fsync, msync, truncate, rename and unlink.
  - Java Flight Recorder ran with zero-threshold file and socket events and full stacks, to name the Java code behind background I/O.
  - `/proc/<pid>/io` gave real device writes per phase.
  - A sampler read `pg_stat_activity` every 0.5 s to catch long and idle-in-transaction sessions.
- **Phases.** In order:
  1. Cold start.
  2. Idle.
  3. Coverage sweeps, one request per route: read (209), admin (79), MCP (55), write (86) and maintenance.
  4. Shutdown.
  5. Warm start.
  6. A 2-minute mixed k6 load.
- **Attribution.** Sweep requests ran one at a time with timestamps, so every syscall and every logged statement could be tied to the request that caused it. That turns "the database did 46,000 verification lookups" into "this endpoint does 91 per call".
- **Code review.** Three parallel code reviews of the DB and disk code covered the paths no request reaches.

The raw measurements are in `loadtest/results/CAMPAIGN-2026-10-22-db-disk.md` in the repository.

## Findings and repairs

### 1. The ontology store grew without bound and wrote ~15 GB per rebuild

**Problem.** The Jena TDB2 ontology store held 79.7 GB in production, still on its first generation since it was recreated on 2026-08-19. Locally, a full rebuild of 8,733 named graphs took 6.5 minutes, made 279,472 fsync and 122,282 msync calls (338 s inside sync calls), and wrote 16.6 GB to disk to produce a 1.84 GB store. Rebuilding identical content again added another 1.5 GB.

**Cause.**
- The rebuild cleared the A-Box in one commit, then wrote each graph in its own write transaction. TDB2's B+trees are copy-on-write, so every commit strands blocks and syncs about 30 index files.
- The weekly compaction added in August was scheduled one full interval after JVM start. Production restarts on every deploy, so it never ran.
- The public `/sparql`, `/id` and `/export` surfaces saw an empty A-Box between the clear and the refill.

**Repair.**
- The rebuild is one write transaction (`OntologyModelManager.replaceAbox`). Graphs stream into it, readers keep the old A-Box until the commit, and a graph already identical in the store is not rewritten, so rebuilding unchanged content commits nothing.
- Compaction is scheduled from a persisted last-compaction marker. A store with no marker counts as overdue and compacts 5 minutes after startup.
- The nightly reconciliation rebuild had the same reset-on-restart problem. It now first runs 10 minutes after startup, then every 24 h.
- Ontology components are closed in order on shutdown.

**Tests.**
- Commit counts before → after: in-memory rebuild of 70 graphs, 71 → 1; TDB2 rebuild that prunes 10 entities, 36 → 1; rebuild of identical content, 46 → 0.
- A reader running mid-rebuild still sees the old A-Box.
- Compaction delays: a marker 8 days old schedules after the grace delay; 1 day old, about 6 days; no marker, the grace delay.

**Verified.** A cold rebuild now takes about 3 seconds and leaves a 16 MB store. A second rebuild of the same content left the store size unchanged.

### 2. "Rebuild indexes" deleted every embedding and Knowledge Graph mention

**Problem.** `POST /admin/content/rebuild-indexes` ran `DELETE FROM kg_content_chunks`. The foreign keys cascade, so it also removed every row in `content_chunk_embeddings`, `chunk_entity_mentions` and `experiment_embeddings`: 18,635 row-by-row cascade deletes per table. It then re-inserted every chunk one statement at a time and re-embedded the whole corpus, which takes hours on a CPU embedder, with dense retrieval empty in the meantime. Entity mentions come from LLM extraction, so they never came back.

**Repair.**
- The rebuild diff-applies each page's chunks. Unchanged chunks keep their ids, so their embeddings and mentions survive.
- Chunks of pages that no longer exist are removed by one set-based statement.
- The sweep is skipped when the page listing is empty (a provider fault), and it spares any page that exists but was created or renamed after the rebuild read its listing.
- Only missing or stale chunks are re-embedded. The admin progress counter still streams per batch.
- `/admin/content/reindex-embeddings` remains the explicit full re-embed.

**Tests.** Real-database tests check that an unchanged page keeps its chunk ids, its embedding and its KG mention; that a page with one edited section re-embeds exactly one chunk; that a deleted page's chunks are swept; that an empty listing deletes nothing; and that a page created mid-rebuild keeps its chunks.

**Verified.** After a rebuild, 18,544 of 18,635 embeddings remained. The rest belonged to chunks the current chunker splits differently, and are re-embedded by the stale pass. The two mention rows that went were on pages that no longer exist.

### 3. Page files were rewritten in place, and write errors were lost

**Problem.**
- Page text, page properties and `refmgr.ser` were overwritten in place (`O_TRUNC`, no temp file, no fsync), so a crash mid-write left a truncated file.
- Page text went through a `PrintWriter`, which never throws on a write error, so a disk-full truncation was not even logged.
- `FileSystemProvider` then swallowed the `IOException` and the save reported success.

**Repair.**
- A new `AtomicFileWriter` (wikantik-util) writes a hidden temp file in the same directory, fsyncs it, moves it over the target with `ATOMIC_MOVE`, and fsyncs the directory.
- Page text, every provider properties file, attachment data, `refmgr.ser` and the reference-manager attribute cache all go through it.
- Failures surface as `ProviderException`.
- The attachment version scan skips in-flight temp files, so an upload in progress can't masquerade as the latest version.

**Tests.** A write into a read-only page directory throws and leaves the old content intact, where it used to succeed silently. A failed write never truncates `refmgr.ser` or an attribute cache file. A temp file is not an attachment version.

### 4. Every save committed ontology transactions on the request thread

**Problem.** Each page save committed the page graph and then each of its concept graphs (cluster, tags) as separate TDB2 transactions, synchronously in the HTTP request. That was about 170 fsync/msync calls per save, or 130–224 ms of a 180–277 ms save.

**Repair.** Page sync moved to a single background worker (`OntologyPageSyncQueue`):
- Rapid saves of one page are coalesced.
- A delete or rename is never undone by a stale queued save.
- The page and its concept graphs are written in one transaction.

**Verified.** A page save now takes 17–42 ms with 2–4 syncs, which are the new durable page write itself.

### 5. The page listing dropped pages, and refreshed by rescanning

**Problem.**
- `CachingProvider.getAllPages()` built the page list by iterating the per-page cache. Entries in that cache expire 60 s after they are written, but the listing is only refreshed every `wikantik.cache.allPagesTTL` (300 s by default). So between 60 s and 300 s after a refresh, `getAllPages()` silently left pages out, and a renamed page vanished from the list until the next refresh. Production masked this by setting the TTL to 60 s.
- The refresh itself read every page's version properties twice, and evicted every cached page text. The Lucene missing-page sweep triggered it every 5 minutes, even on an idle wiki.

**Repair.**
- The listing is now an explicit snapshot. The TTL refresh diffs it against the store, and this provider's own saves, deletes and moves keep it current.
- The refresh reads each page's properties once and evicts cached text only for pages that actually changed.

**Tests.** A page whose per-page cache entry expired is still listed. A save, a delete and a rename are reflected immediately, and a rename lists the page under its new name. An unchanged page's text survives a refresh.

### 6. The public change feeds scanned the page store on every request

**Problem.** `/api/changes`, `/api/recent-changes`, `/api/me/pages` and `/feed.xml` read the version properties of hundreds of pages on every request (300–455 file opens each), unauthenticated, because `getAllChangedSince` bypassed the cache.

**Repair.** Changed-since queries are served from the page listing (finding 5). **Verified:** zero page-store file opens for these routes.

### 7. Lucene opened a reader per query and two writers per update

**Problem.**
- Every search opened a new `Directory` and `DirectoryReader`: about 80,000 index-file opens and 7,032 directory listings in a 2-minute load.
- Every page update opened, committed and closed one `IndexWriter` for the delete and another for the add. A full reindex therefore made 1,213 commits and 47,799 fsyncs and took 64 s, where startup builds the same index with one commit in 1.8 s.
- A crash between the two commits left the page out of the index.

**Repair.**
- A shared `LuceneIndexStore` holds one long-lived `IndexWriter` and a reference-counted `ReaderManager`.
- Updates use `updateDocument`, which deletes and adds atomically.
- The drain commits once per batch and retries a failed page before dropping it.
- The store closes with the thread's interrupt flag cleared, because shutdown runs on the already-interrupted indexer thread and Lucene would otherwise abort the final commit.

**Tests.** Draining 25 queued pages makes 1 commit, not 25. Searches reuse one reader until a commit. A failed re-index keeps the page's previous document. Closing from an interrupted thread still commits and releases the index lock.

### 8. N+1 queries on reader, agent and admin paths

| Path | Cause | Repair |
|---|---|---|
| `/api/pages/for-agent`, `/admin/agent-grade-audit`, `/admin/verification`, `/admin/overview` | `verificationOf` was an uncached one-row query, called once per page (46,186 times in 2 minutes) | Verification rows are held in memory with the structural index (`VerificationStore`), loaded in one query at rebuild and kept current by its writes |
| `/api/search`, `retrieve_context` | the "batched" related-pages lookup ran one query per page | One windowed statement over `page_name = ANY( ? )` |
| `/api/bundle`, `/api/briefing`, `assemble_bundle`, `get_briefing` | one `page_canonical_ids` query per cited section | Resolved from the structural index. A page with no authored `canonical_id` (an in-memory id that changes per restart) is never cited by its unstable id |
| `/admin/knowledge-graph/proposals`, MCP `list_proposals` | node-by-name and rejection lookups per proposal | Two batched lookups per page of proposals (`KgBatchLookups`) |
| Page knowledge slice | per-entity node and edge queries | Batched with `= ANY( ? )` |
| `/admin/drift/trend` | one count query per sweep | One query for all sweeps |

### 9. Authentication and audit

- **Basic-auth writes.** Every Basic-auth request updated `users.last_login`. Concurrent requests by one account queued on that row's lock: mean 2.7 ms against 0.05 ms uncontended. The update is now throttled to once per 5 minutes per login, and the cached profile is updated atomically rather than evicted. The `login.ok` audit entry per authentication is kept on purpose.
- **Audit append.** Each append found the chain head with `ORDER BY seq DESC LIMIT 1` across all 31 monthly partitions, 26 of them empty future months, and checked partition existence every time. The head lookup now prunes older months, and partition checks run once per month. It deliberately has no upper time bound, so a clock that steps back across a month boundary cannot fork the hash chain; a test covers that case. A failed audit batch is retried with backoff before being dropped.
- **Page saves were never audited.** `AuditEventListener` audited only a save event that production never fires. The local audit log held 118 `page.delete` rows and no `page.save` rows. Saves are now audited once each, and the row names the authenticated user who saved.

### 10. Transactions held across remote calls, and reads that did not stream

- **Embedding transactions.** Embedding writes held a database transaction open across HTTP calls to the embedder. The sampler caught one connection idle in transaction for 388 s during a re-embed. Reads now use short keyset-paged queries, embedding runs with no connection held, and each batch is written in its own short transaction.
- **Non-streaming reads.** `Jdbc.forEachRow` claimed to stream but ran in auto-commit mode, where PgJDBC ignores the fetch size and buffers the whole result. Startup loaded about 76 MB of vectors this way, twice. It now wraps the scan in a read transaction when a fetch size is given.
- **Similar pages.** `NodeMentionSimilarity` reloaded every mention vector on each call. It is reachable from the public `/api/pages/{p}/similar`. Centroids are now cached for 60 s and computed with running sums.

### 11. Startup waste and robustness

- **Startup.**
  - The vector index loaded all 18,627 vectors twice, because a reconcile that changed nothing still triggered a full reload. That reload now runs only when something changed or the index is empty, so a failed boot load still self-heals.
  - Every boot rewrote all 1,163 `page_canonical_ids` and `page_verification` rows. Unchanged rows are now left alone, and `updated_at` again means "last real change".
- **Policy refresh.** A database error during a policy refresh used to publish an empty grant set. The previous grants are now kept.
- **Connection pool.** The pool gained connect and socket timeouts and TCP keepalive (`connectTimeout=10`, `socketTimeout=300`, `tcpKeepAlive=true`). The dev template no longer runs `SELECT 1` on every borrow; there were 63,541 of those in a 2-minute local load.
- **Indexes.** V062 adds indexes on `users(uid)` and `retrieval_runs(mode, started_at)`, and a partial index for the KG judge claim. That claim used to seq-scan 15,002 proposals to find none.
- **Log retention.** Rotated logs are now deleted after 30 days (90 days for the security log). Production held 271 rotated files dating back to May.

## Corrections

The first report got three things wrong, and the repair work caught them:
- **Saves were not handled twice.** A ±500 ms attribution window had overlapped the next request. With strict per-request windows, each save runs one update of each kind. The event branch that looked duplicated was dead code. Checking it surfaced the real gap: page saves were never audited (finding 9).
- **No `drift_snapshot_counts(sweep_id)` index was needed.** The primary key already leads with that column, and the sequential scan was just the planner's choice for a 220-row table. The index was dropped from V062 before it shipped anywhere.
- **The SPA shell is not re-read from disk per request.** The trace shows one `access()` call per request. Tomcat's resource cache serves the bytes.

Also note: the per-page text cache expires entries after 60 s regardless of refreshes, so finding 5's text-cache benefit is smaller than first estimated. The listing fix is the important part.

## Found while reviewing the repairs

An independent review of the 19-commit change set found problems in the new code, all fixed with tests before release:
- The first page-cache change made the listing gaps of finding 5 worse; this led to the snapshot design.
- Two sweep races: an empty listing, and a page created mid-rebuild.
- Temp files counted as attachment versions.
- `page.save` audit rows had no actor.
- The audit chain-head upper time bound.
- A dense index that no longer self-healed.
- A non-atomic cached-profile update.
- The nightly rebuild first-run schedule.

The CI complexity ratchet had also been red since 2.4.60. The cause was `BundleServiceWiring`, which tripped the GodClass rule. It now passes across every module, after splitting out `BundleRerankers`, `VerificationStore`, `PageRevisions`, `KgBatchLookups` and `EventActors`.

## Not repaired yet

- **Append-only tables have no retention.** `retrieval_query_log`, `briefing_log`, `search_visibility_snapshot`, `drift_*` and `audit_log` only grow. A retention policy is a product decision.
- **Some admin dashboards aggregate whole tables per request.** Chunk statistics take 14 ms, and the embedding status query is a sequential scan.
- **`similarTo` can be up to 60 s stale.** No Knowledge Graph write path calls `NodeMentionSimilarity.invalidate()` yet.
- **A rebuild does not refresh embedding context.** The embedding text includes the page's title, cluster and summary, but chunk staleness only tracks heading and text. A frontmatter-only change (for example a cluster rename) therefore needs `/admin/content/reindex-embeddings`.
- **Smaller code-review items** remain, with file and line in the campaign report: `HubProposalService` lookup loops, non-transactional multi-statement Knowledge Graph writes, read-then-insert upserts, and connector credential writes that swallow errors.

## Notes for the next deploy

- **Ontology store.** Production's 79.7 GB store compacts about 5 minutes after startup: the live data is copied into `Data-0002` and `Data-0001` is deleted. Expect a few minutes of disk activity. A reconciliation rebuild follows 10 minutes after startup, and commits nothing if the store is current.
- **Audit log.** `page.save` audit rows will start appearing, with the saving user's login.
- **Database.** Migration V062 adds three indexes. The pool's `socketTimeout=300` ends any query that waits more than 5 minutes for the server.
- **Logs.** The first daily rollover deletes rotated logs older than the retention window.

## Re-running the profile

The method is reproducible:
1. Restore the database into an instrumented container.
2. Point the wiki at copies of the pages and work directory.
3. Start Tomcat under `strace` with the JFR settings.
4. Run the coverage sweep phases, taking a statement snapshot between each.

The scripts and the before/after measurements are summarised in the campaign report. The most useful single technique was **per-request attribution**: a sequential sweep with timestamps, joined against the statement log and the syscall trace.
