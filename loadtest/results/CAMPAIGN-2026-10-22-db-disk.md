# Profiling run 2026-10-22 (2): database and disk

Question: what does Wikantik do badly against Postgres and the local disk? **Frequency is
deliberately ignored.** A statement that runs once a week counts as much as one that runs on every
request, so the run exercises every route once, not just the hot paths.

## Method

- **Isolated copy, not the dev install.**
  - A throwaway Postgres 18 + pgvector container restored from a dump of the local DB. It had
    `pg_stat_statements` and every statement logged with parameters, connections, lock waits and
    temp files.
  - A copy of `docs/wikantik-pages` and an empty work dir. The build was 2.4.61 (unchanged since).
- **Disk tracing.**
  - `strace -f` on file-system syscalls, with paths and durations: open, stat, getdents, fsync,
    msync, ftruncate, rename and unlink.
  - JFR with zero-threshold `jdk.FileRead`/`FileWrite`/`FileForce`/`Socket*` events and full
    stacks.
  - `/proc/<pid>/io` per phase, and `pg_stat_activity` sampled every 0.5 s.
- **Phases.** In order:
  1. Cold start (empty work dir).
  2. Idle, 4 min.
  3. Coverage sweeps with one request per route: read (209), admin (79), MCP (55), write (86) and
     maintenance (151).
  4. Shutdown.
  5. DB re-restore.
  6. Warm start.
  7. 2-min mixed k6 load (40 read / 10 admin / 4 write VUs).
- **Attribution.** Sweep requests run one at a time with timestamps, so every syscall and every
  logged statement is attributed to the request that caused it.
- **Code reviews.** Three code reviews ran in parallel. Every finding below is either **measured**
  in this run or **code-verified** (read end to end). Reviewer claims that were not re-verified are
  listed separately at the end.
- **Production check.** Read-only commands on docker1: `du` on the work and log volumes.

## Critical

### C1. The ontology store grows without bound and writes ~15 GB per rebuild

| measurement | value |
|---|---|
| fresh rebuild, 8,733 named graphs | 6.5 min, **279,472 fsync + 122,282 msync** (338 s inside sync calls) |
| device writes for that rebuild (`write_bytes`) | **16.6 GB**, leaving a 1.84 GB store |
| second rebuild of identical content | store 1.84 → **3.36 GB**, another 14.6 GB written |
| one page save | ~170 fsync/msync, ~7 MB written |
| **production, today** | `ontology-tdb2` = **79.7 GB**, still `Data-0001`, created 2026-08-19 |

- `OntologyRebuildService.java:52-78` clears the A-Box, then commits **one write transaction per
  graph**.
- TDB2's copy-on-write B+trees never reuse the freed blocks, and each commit syncs ~30 index files.
- The weekly compaction added on 2026-08-19 is `scheduleAtFixedRate(…, 168h, 168h)`
  (`OntologyRebuildScheduler.java:71`), so it first runs one full week after startup. Production
  restarts on every deploy, so it has **never run**, and the store has regrown at ~1.25 GB/day.

**Fix:**
- Rebuild in a few large transactions, or build a fresh dataset and swap it in.
- Compact at startup when the store is over a size or ratio threshold, or when a persisted
  last-compacted time is stale.
- One-off: move prod's store aside. It is a derived projection, and startup rebuilds it.

### C2. "Rebuild indexes" deletes every embedding and every KG mention

`POST /admin/content/rebuild-indexes` runs `chunkRepo.deleteAll()`
(`ContentIndexRebuildService.java:317-318`). Measured:

- `DELETE FROM kg_content_chunks`: 18,635 rows, 621 ms, 149k WAL records.
- The FK cascades ran as **18,635 per-row deletes in each of three tables**:
  `content_chunk_embeddings`, `chunk_entity_mentions` and `experiment_embeddings`.
- Embeddings went from 18,627 to 0, and the DB shrank from 514 MB to 320 MB.
- The chunks were then re-inserted **one INSERT per chunk** (18,947 statements).
- The whole corpus was then re-embedded. On the CPU embedder, 32-chunk batches timed out at 30 s.
- Dense retrieval is empty until that finishes. That takes hours on a CPU embedder.
- `chunk_entity_mentions` is never rebuilt, because mentions come from LLM extraction. On prod, one
  click silently disconnects the Knowledge Graph from the corpus.

**Fix:** drop the `deleteAll()`. `ChunkDiff.apply` already exists, and it keeps unchanged chunk ids,
so their embeddings and mentions survive. Then embed only the stale chunks, as `indexStale` does.

### C3. Page and reference files are rewritten in place, and page write errors vanish

- **In-place rewrites.** The trace shows `openat(…, O_WRONLY|O_CREAT|O_TRUNC)` with no temp file,
  rename or fsync. This applies to page files (`AbstractFileProvider.java:445-449`) and to
  `refmgr.ser` (`DefaultReferenceManager.java:477-498`). A crash or kill mid-write leaves a
  truncated page.
- **Write errors are lost.**
  - The page is written through a `PrintWriter`, which never throws on write. A disk-full
    truncation is not even logged: the `catch (IOException)` there only sees open failures.
  - `FileSystemProvider.java:68-75` then swallows `IOException` again, with no exception detail,
    and returns. So the save reports success.

**Fix:**
- Write to a sibling temp file, then fsync, then `ATOMIC_MOVE`.
- Use a `Writer` that throws, not `PrintWriter`.
- Propagate a `ProviderException`.

## Important

### I1. Every save commits several ontology transactions on the request thread

Per save, measured: **4 TDB2 write transactions** and **130–224 ms of fsync/msync on the HTTP
thread, out of a 180–277 ms save**.

`OntologyPageSync.onPageSaved` commits the page graph and then each of its concept graphs (cluster,
tags) as separate transactions, synchronously inside the save request.

**Correction (2026-10-22, during the fix work):** an earlier version of this section said each save
was handled twice. That came from a ±500 ms attribution window that overlapped the next request.
With strict per-request windows, each save runs exactly one canonical-id update, one verification
update and one ontology sync.
- `PageEventFilter` is not wired in production, so the `POST_SAVE` branches in the three listeners
  are dead code.
- The listeners now handle `POST_SAVE_END` only, as a clean-up.
- The same check found a real gap: `AuditEventListener` audited only `POST_SAVE`, so **no
  `page.save` audit row was ever written**. The local audit log holds only `page.delete` rows.

**Fix:**
- Write the page and concept graphs in one transaction.
- Move ontology page sync to a coalesced async worker, as `OntologyEntitySync` already does for
  entities.

### I2. Every 5 minutes, the page cache re-reads ~2,600 property files and flushes all cached page text

- **Trigger.** JFR stacks at 12:36, 12:41, 12:46 and 12:51 show the call chain
  `DefaultLuceneIndexer.indexMissingPages` → `CachingProvider.getAllPages` (TTL 300 s expired) →
  `refreshAllPagesCache`.
- **Wasted reads.** `VersioningFileProvider.getAllPages` (`:714-728`) calls `super.getAllPages()`,
  which already builds each page's info, then calls `getPageInfo` for every page **again**. That is
  two `OLD/<page>/page.properties` reads per page per sweep, even on an idle wiki.
- **Cache flush.** `refreshAllPagesCache` (`CachingProvider.java:372-385`) then removes **every
  `CACHE_PAGES_TEXT` entry**, changed or not. The next render of every page reads from disk again.

**Fix:**
- Refresh by diffing the page list, evicting only vanished or changed pages.
- Drop the duplicate `getPageInfo` pass.

### I3. Public change feeds scan the page store on every request

Property-file opens per request, measured:

| route | opens per request |
|---|---|
| `/api/changes` | 450 |
| `/api/recent-changes` | 455 |
| `/api/me/pages` | 453 |
| `/feed.xml` | 307 |
| `/sitemap.xml` (cold) | 1,284 |

Each also lists the directory. These routes are unauthenticated.

`CachingProvider.getAllChangedSince` bypasses the cache (`CachingProvider.java:396-397`), and the
file provider then reads every versioned page's properties (`AbstractFileProvider.java:510+`).

**Fix:** serve recent changes from the cached page list, or from a change log ordered by
modification time.

### I4. Lucene opens a new reader per query, and opens and commits two writers per page update

- **Under the 2-min load:** ~80,000 segment-file opens and 7,032 directory listings. Every search
  re-opens the index (`DefaultLuceneSearcher.java:248-249`, `:422-423`).
- **Separate writers per update.** Each update opens separate `IndexWriter`s for the delete and the
  add (`DefaultLuceneIndexer.java:242/264/548`), each committing on close.
- **Rebuild cost.** "Rebuild indexes" made **1,213 commits and 47,799 fsyncs**, plus 9,726
  directory fsyncs, and took 64 s. The same 1,183 pages index in 1.8 s with one commit at startup.

**Fix:**
- Keep a `SearcherManager`, refreshed after commits.
- Keep one long-lived `IndexWriter` and use `updateDocument`, committing per batch or on a timer.

### I5. Verification lookups are N+1, including on a public endpoint

`DefaultStructuralIndexService.verificationOf` (`:240-245`) is an uncached single-row query. Callers
loop it per page:

| caller | queries per request |
|---|---|
| `/admin/agent-grade-audit` (`AgentGradeAuditResource.java:171`) | **1,182, even with `limit=20`** |
| `/admin/verification` | 200 |
| `/admin/overview` (`StructuralIndexService.java:105`) | 100 |
| **anonymous** `/api/pages/for-agent/{id}` (`AgentHintsDeriver.java:182`) | 91 |

It was called **46,186 times in the 2-minute load**.

**Fix:** keep `page_verification` (1,265 rows) in the in-memory structural snapshot, or batch with
`= ANY(?)`.

### I6. Other per-item query loops on retrieval and admin paths, measured per request

- **Search.** A cold `/api/search` ran **197** related-pages queries.
  `MentionIndex.findRelatedPagesBatch` (`MentionIndex.java:276-290`) is not a batch: it runs one
  query per page on a shared connection. A cache hides this once warm.
- **Bundle and briefing.** `/api/bundle`, `/api/briefing` and MCP `assemble_bundle`/`get_briefing`
  each run 12 `page_canonical_ids` lookups by slug (`BundleServiceWiring.java:130-131`), each with
  its own pool borrow. The structural index already holds slug → canonical_id in memory.
- **Page graph.** `/api/page-graph/snapshot` runs a per-node and per-edge traversal (13 + 13
  queries).
- **Admin.** `/admin/knowledge-graph/proposals` runs 42 node-by-name lookups plus one
  `kg_rejections` count per proposal. `/admin/drift/trend` runs one count query per sweep.

### I7. Basic-auth requests write to the database

Every Basic-auth request triggers four statements: `UPDATE users SET last_login`, a `login.ok` audit
row, the audit chain-head read (I8) and a partition check. That was 66 of each for 66 requests.

- Under load, concurrent requests by one account queue on that row: Lock/`transactionid` and
  `tuple` waits were sampled, and the mean update time was **2.7 ms vs 0.05 ms** uncontended.
- It also fills `audit_log` with one row per API call.
- Path: `BasicAuthFilter.java:135` → `LastLoginEventListener` and `AuditEventListener`.

**Fix:**
- Throttle `last_login` updates.
- Audit `login.ok` only when a session is created.

### I8. Every audit append reads the chain head across 31 partitions

- `CHAIN_HEAD_SQL` (`JdbcAuditRepository.java:43`) is
  `ORDER BY seq DESC LIMIT 1` over `audit_log`. It plans as a Merge Append of **31 index scans**,
  and 26 of those partitions are empty future months that V059 pre-created through 2028-12.
- Planning touches ~3,500 buffers when cold.
- `to_regclass` also runs on every append.

**Fix:**
- The single writer thread can keep the chain head in memory, or in a one-row head table.
- Check partition existence once per month.

### I9. Transactions stay open across embedding HTTP calls

- The full re-embed in C2 left a connection **idle in transaction for 388 s**
  (`EmbeddingIndexService.indexRows`). That pins a pool connection and holds back vacuum.
- The per-save path `indexChunks` (`EmbeddingIndexService.java:391-412`) opens the transaction and
  then calls the embedder inside it, with up to 3 attempts at a 30 s timeout.

**Fix:** read the rows, release the connection, embed, then write in a short transaction.

### I10. Startup does avoidable full-table work on every boot

- **Two full vector loads.** All 18,627 vectors are loaded **twice**, plus an HNSW rebuild, 0.6–2 s
  each. The bootstrap stale-reconcile reloads the dense index even when it upserted 0 rows
  (`BootstrapEmbeddingIndexer.java:335-337` → `LuceneHnswChunkVectorIndex.reload`).
- **Unconditional row rewrites.** The structural rebuild rewrites **all 1,163
  `page_canonical_ids` and 1,163 `page_verification` rows** unconditionally. That is 4,652
  statements and 2,346 transactions per boot (`PageCanonicalIdsDao.java:150-157`,
  `PageVerificationDao.java:62-74`), and it makes `updated_at` mean "last boot".

**Fix:**
- Skip the post-run reload when nothing changed.
- Update only on a real difference, and use `INSERT … ON CONFLICT`.

### I11. Code-verified DB robustness gaps (not triggered in this run)

- **Empty grants on error.** `DatabasePolicy.refresh` (`DatabasePolicy.java:121-129`) logs a
  `SQLException`, then **publishes an empty grant snapshot**. A DB blip during an admin edit, or a
  startup before the DB is ready, drops every role grant.
- **No timeouts.** The prod pool sets no socket timeout, statement timeout or abandoned-connection
  removal (`docker/entrypoint.sh:466`, plus the Context resource), and `Jdbc` never sets a query
  timeout. One hung query pins a connection until restart.
- **No streaming.** `Jdbc.forEachRow(String,…)` (`Jdbc.java:213-219`) runs in autocommit, where
  pgjdbc ignores `fetchSize`, so results are fully buffered. The two startup vector loads (~76 MB
  each) go through this path.
- **Full vector reload per call.** `NodeMentionSimilarity.similarTo` (`:206-258`) reloads and
  decodes every mention vector on each call, and it is reachable from public
  `GET /api/pages/{p}/similar` (`PageResource.java:933`). This was not measurable locally, because
  `chunk_entity_mentions` had 2 rows.
- **Lost audit batches.** `AuditWriterThread` drops the whole batch when persistence fails.

## Minor (measured)

- **Judge tick.** Seq-scans all 15,002 `kg_proposals` to find 0 rows. Needs a partial index on
  `(created) WHERE status='pending' AND machine_status IS NULL`.
- **Orphan count.** 4,818 index-probe pairs, 22.8k buffers, per admin overview call
  (`KgNodeRepository.java:499`, a correlated `OR` in `NOT EXISTS`).
- **Admin dashboards.** These run full-table aggregates on every request:
  - chunk stats: 1,355 blocks, 14 ms (`ContentChunkRepository.java:593`)
  - embeddings status, a seq scan (`EmbeddingIndexService.java:453`)
  - proposal counts
  - insights pulling 15k `retrieval_query_log` rows into Java
  - audit verify loading all 40k rows
- **Missing small indexes.**
  - `users(uid)`, seq-scanned on every Basic-auth request
  - `drift_snapshot_counts(sweep_id)`, an FK column
  - `retrieval_runs(mode, started_at)`
- **Validation query per borrow (dev only).** The dev context template runs `SELECT 1` on every
  pool borrow: 63,541 in the 2-minute load. Prod uses `testWhileIdle`; align the template.
- **Attachment directories.** An upload lists its attachment directory 6 times. Page delete leaves
  an empty `<page>-att` directory behind.
- **No retention.** These tables only grow: `retrieval_query_log` (116k rows locally),
  `briefing_log`, `search_visibility_snapshot`, `drift_*` and `audit_log`. Log files also have no
  `<Delete>` rule; prod has 271 files since 2026-05-16, which is small so far.

## Checked and not a problem

- **No long lock waits, deadlocks or spills.** There were no lock waits ≥ 200 ms, no deadlocks
  and no temp-file spills in any phase. The only temp files were index builds during the restore.
- **No fd leaks.** The open-fd count rose while jars loaded during the sweeps, then returned to
  ~125.
- **SPA shell.** `SpaRoutingFilter` re-reading `index.html` is not a disk cost: the trace shows one
  `access()` per SPA request, and Tomcat's resource cache serves the bytes.
- **Shutdown.** The JVM exited 1.4 s after `shutdown.sh` (the 2.4.61 fix holds).

## Reported by code review, not re-verified

The reviewers cited file and line for each of these; they were not exercised in this run.
- `HubProposalService`: 3 queries per candidate, plus 100k-row scans filtered in Java.
- Non-transactional multi-statement KG writes: `JudgeRunner.recordVerdict`, `mergeNodes`,
  `clearAll`, and `setClusterPolicy` plus its audit row.
- Read-then-insert upserts that race: `PageOwnerService`, `MentionService.insertIfAbsent`,
  `TrustedAuthorsDao`, and connector sync state.
- Connector credential writes swallow `SQLException`.
- N+1 directory reads: `MentionableUsersResource` and SCIM `listAll`.
- `JDBCGroupDatabase` borrows a second connection inside a row mapper.
- `getProposalsForJudging`'s `FOR UPDATE SKIP LOCKED` releases its locks before judging.
- `discoverSchema` loads every node.
- Citation regrade rewrites every row.
- Index-defeating `LIKE '%…%'` on `properties::text`.
- `VersioningFileProvider` keeps every version forever.
- `Engine.findConfigFile` creates a temp file per call.

## Run 2: the fixed build, same harness

Measured on the merged fixes with the same isolated-copy method. The full story is on the wiki page
`WikantikDatabaseAndDiskProfiling`.

| measurement | before | after |
|---|---|---|
| cold ontology rebuild | 6.5 min, 279,472 fsync, 16.6 GB written, 1.84 GB store | ~3 s, one transaction, 16 MB store |
| second rebuild of identical content | +1.53 GB store, 14.6 GB written | no growth, nothing committed |
| page save (request thread) | 168–180 syncs, 180–277 ms | 2–4 syncs, 17–42 ms |
| admin "rebuild indexes" | all embeddings + mentions deleted; 1,213 Lucene commits | embeddings kept; 7 Lucene commits in the whole run |
| idle refresh every 5 min | ~2,600 property reads + text cache evicted | ~490 property reads |
| change feeds, `/feed.xml` | 300–455 file opens per request | 0 |
| `/api/pages/for-agent` | 182 statements | 5 |
| `/admin/agent-grade-audit` | 2,377 statements | 5 |
| cold `/api/search` | 204 statements | 4 |

Other corrections to this report, found during the repair work:
- `drift_snapshot_counts(sweep_id)` already has an index: the primary key leads with that column.
- The SPA shell is served from Tomcat's resource cache; there is only one `access()` call per request.
- The page-text cache expires entries after 60 s anyway, so I2's main defect was the *listing*: it dropped pages
  whose cache entries had expired. That is now fixed.
