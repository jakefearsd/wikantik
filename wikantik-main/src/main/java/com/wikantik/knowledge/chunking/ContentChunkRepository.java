/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.knowledge.chunking;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.jdbc.SqlBinder;
import com.wikantik.knowledge.KgJdbcSupport;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC-based data access layer for the {@code kg_content_chunks} table.
 *
 * <p>Applies {@link ChunkDiff.Diff} operations for a single page atomically
 * (deletes, updates, inserts all run inside one transaction).</p>
 *
 * <p>Extends {@link KgJdbcSupport} — the shared JDBC Template Method base also
 * used by the narrow KG repositories (e.g. {@link com.wikantik.knowledge.KgNodeRepository}):
 * plain JDBC via {@link DataSource}, {@link PreparedStatement}, error logging
 * at {@code warn} level followed by a wrapping {@link RuntimeException}. This class
 * previously reimplemented that skeleton independently (the base class was
 * package-private to {@code com.wikantik.knowledge}); the GoF Template Method
 * consolidation widened {@link KgJdbcSupport} to {@code public} so this
 * subpackage of the same Knowledge Graph domain can extend it directly instead
 * of duplicating the connect/prepare/bind/execute/map ceremony.</p>
 *
 * @since 1.0
 */
public class ContentChunkRepository extends KgJdbcSupport {

    private static final Logger LOG = LogManager.getLogger( ContentChunkRepository.class );

    /**
     * Aggregate statistics over {@code kg_content_chunks}.
     *
     * <p>The {@code pagesMissingChunks} field is always returned as {@code 0}
     * because this repository cannot know the set of indexable pages on its
     * own — callers must compute that difference against an external page
     * count.</p>
     */
    public record AggregateStats(
        int pagesWithChunks, int pagesMissingChunks,
        int totalChunks, int avgTokens, int minTokens, int maxTokens ) {}

    /**
     * Full row from {@code kg_content_chunks} including the chunk text and
     * timestamps. Returned by {@link #findFullByPage(String)} so operator
     * tooling can inspect what the chunker actually produced.
     */
    public record FullChunk(
        UUID id,
        int chunkIndex,
        List< String > headingPath,
        String text,
        int charCount,
        int tokenCountEstimate,
        String contentHash,
        Instant created,
        Instant modified ) {}

    /** Corpus-wide chunking outliers surfaced by {@link #outliers()}. */
    public record OutlierReport(
        List< OutlierEntry > mostChunks,
        List< OutlierEntry > largeSingleChunks,
        List< OutlierEntry > oversizedChunks ) {}

    /** Row in an {@link OutlierReport} list. */
    public record OutlierEntry(
        String pageName,
        int chunkCount,
        int maxTokens,
        int totalTokens,
        int charCount ) {}

    /**
     * In-process LRU cache for {@link #findByIds}. Keyed by chunk_id (UUID),
     * value is the {@link MentionableChunk} as returned from the DB. Bounded
     * by {@code maximumSize} so memory cannot run away; TTL is a belt-and-
     * suspenders backstop in case an explicit invalidation is ever missed.
     *
     * <p><strong>Invalidation contract:</strong> {@link #apply} calls
     * {@link #invalidatePage} after a successful chunk write, and
     * {@link #deleteAll} calls {@link #invalidateAll}. Cache misses always
     * fall through to the same SQL query as before — no new failure mode is
     * introduced.</p>
     *
     * <p>Maximum size of 50,000 entries × ~5 KB per chunk text ≈ 250 MB
     * worst case; in practice the corpus has ~12 K chunks today (so the
     * cache plateaus at ~60 MB) but the headroom accommodates 4× corpus
     * growth without re-tuning.</p>
     */
    private final Cache< UUID, MentionableChunk > chunkCache;

    public ContentChunkRepository( final DataSource dataSource ) {
        super( dataSource );
        this.chunkCache = Caffeine.newBuilder()
            .maximumSize( 50_000 )
            .expireAfterWrite( Duration.ofMinutes( 10 ) )
            .recordStats()
            .build();
    }

    @Override
    protected Logger log() { return LOG; }

    /**
     * Enumerates every distinct page name that currently has at least one
     * chunk row. Used by the admin batch-extraction job to walk the corpus
     * without loading all chunk bodies up-front.
     *
     * @return alphabetically-sorted list of page names
     */
    public List< String > listDistinctPageNames() {
        final String sql = "SELECT DISTINCT page_name FROM kg_content_chunks "
                         + "WHERE page_name IS NOT NULL ORDER BY page_name";
        try {
            return query( sql, SqlBinder.NONE, rs -> rs.getString( 1 ) );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list distinct page names: {}", e.getMessage(), e );
            throw new RuntimeException( "listDistinctPageNames failed", e );
        }
    }

    /**
     * Returns every chunk id on the given page, ordered by {@code chunk_index}.
     * A thin form of {@link #findByPage} used by callers that will re-hydrate
     * through {@link #findByIds} anyway (e.g. the batch extractor).
     */
    public List< UUID > listChunkIdsForPage( final String pageName ) {
        final String sql = "SELECT id FROM kg_content_chunks "
                         + "WHERE page_name = ? ORDER BY chunk_index";
        try {
            return query( sql, ps -> ps.setString( 1, pageName ), rs -> rs.getObject( 1, UUID.class ) );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list chunk ids for '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "listChunkIdsForPage failed for " + pageName, e );
        }
    }

    /**
     * Returns all stored chunks for a page ordered by {@code chunk_index}.
     *
     * @param pageName the wiki page name
     * @return list of stored chunks (id, index, content hash)
     */
    public List< ChunkDiff.Stored > findByPage( final String pageName ) {
        final String sql = "SELECT id, chunk_index, content_hash FROM kg_content_chunks "
                         + "WHERE page_name = ? ORDER BY chunk_index";
        try {
            return query( sql, ps -> ps.setString( 1, pageName ), rs -> new ChunkDiff.Stored(
                rs.getObject( 1, UUID.class ),
                rs.getInt( 2 ),
                rs.getString( 3 ) ) );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find chunks for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "findByPage failed for " + pageName, e );
        }
    }

    /**
     * Loads chunks keyed by id (as emitted by the post-chunk sink). Missing
     * ids are silently skipped. Result order is unspecified.
     *
     * @param ids chunk ids to fetch; empty list returns empty list
     * @return a row per id that exists in {@code kg_content_chunks}, with the
     *     page name included so downstream consumers don't need a second query
     */
    public List< MentionableChunk > findByIds( final List< UUID > ids ) {
        if( ids == null || ids.isEmpty() ) {
            return List.of();
        }
        // Cache lookup: split into hits served from {@link #chunkCache} vs misses
        // that need a DB round-trip. Misses get written back so subsequent calls
        // for the same chunk id avoid the SQL. Eviction is explicit via
        // {@link #invalidatePage} (called from {@link #apply}) — no risk of
        // stale text being returned after a page rewrite.
        final List< MentionableChunk > hits = new ArrayList<>( ids.size() );
        final List< UUID > misses = new ArrayList<>( ids.size() );
        for( final UUID id : ids ) {
            final MentionableChunk cached = id == null ? null : chunkCache.getIfPresent( id );
            if( cached != null ) {
                hits.add( cached );
            } else if( id != null ) {
                misses.add( id );
            }
        }
        if( misses.isEmpty() ) {
            return hits;
        }
        final List< MentionableChunk > fetched = fetchByIdsFromDb( misses );
        for( final MentionableChunk mc : fetched ) {
            chunkCache.put( mc.id(), mc );
        }
        final List< MentionableChunk > out = new ArrayList<>( hits.size() + fetched.size() );
        out.addAll( hits );
        out.addAll( fetched );
        return out;
    }

    /**
     * Raw DB fetch — the pre-cache code path. Package-private for unit tests
     * that want to exercise the SQL without the cache short-circuit.
     */
    List< MentionableChunk > fetchByIdsFromDb( final List< UUID > ids ) {
        final String sql = "SELECT id, page_name, chunk_index, heading_path, text "
                         + "FROM kg_content_chunks WHERE id = ANY( ? )";
        try {
            return query( sql, ps -> {
                final UUID[] arr = ids.toArray( new UUID[ 0 ] );
                ps.setArray( 1, ps.getConnection().createArrayOf( "uuid", arr ) );
            }, rs -> {
                final Array hp = rs.getArray( 4 );
                final List< String > headingPath;
                if( hp == null ) {
                    headingPath = List.of();
                } else {
                    final String[] parts = (String[]) hp.getArray();
                    headingPath = parts == null ? List.of() : List.of( parts );
                }
                return new MentionableChunk(
                    rs.getObject( 1, UUID.class ),
                    rs.getString( 2 ),
                    rs.getInt( 3 ),
                    headingPath,
                    rs.getString( 5 ) );
            } );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find chunks by ids (count={}): {}", ids.size(), e.getMessage(), e );
            throw new RuntimeException( "findByIds failed", e );
        }
    }

    /**
     * Evicts every cache entry whose {@link MentionableChunk#pageName()}
     * matches {@code pageName}. Called from {@link #apply} after a successful
     * chunk write so the next {@link #findByIds} for any affected chunk hits
     * the DB and re-caches the fresh row.
     *
     * <p>Iterates the cache's backing {@code asMap} once; for the corpus
     * sizes this repo handles (~12 K chunks today, 50 K cap), the walk is
     * microseconds. Trades a per-write O(N) scan for the per-read O(1)
     * lookup that dominates the working set.</p>
     */
    public void invalidatePage( final String pageName ) {
        if( pageName == null ) return;
        final Map< UUID, MentionableChunk > snap = chunkCache.asMap();
        snap.entrySet().removeIf( e -> pageName.equals( e.getValue().pageName() ) );
    }

    /** Evicts the entire cache. Called by {@link #deleteAll}. */
    public void invalidateAll() {
        chunkCache.invalidateAll();
    }

    /** Cache stats — useful for {@code /metrics} and admin diagnostics. */
    public com.github.benmanes.caffeine.cache.stats.CacheStats cacheStats() {
        return chunkCache.stats();
    }

    /**
     * The underlying Caffeine cache — exposed for metric registration via
     * {@code CaffeineCacheMetricsBridge}. Not intended for callers outside
     * the metrics path.
     */
    public Cache< UUID, MentionableChunk > cache() {
        return chunkCache;
    }

    /**
     * Chunk projection returned by {@link #findByIds(List)}: id + page + text,
     * which is exactly what the extractor pipeline needs.
     */
    public record MentionableChunk(
        UUID id,
        String pageName,
        int chunkIndex,
        List< String > headingPath,
        String text
    ) {}

    /**
     * Returns every chunk for a page with full contents (text, timestamps,
     * token/char counts), ordered by {@code chunk_index}. Used by the admin
     * Chunk Inspector tab to surface exactly what the chunker wrote.
     *
     * @param pageName the wiki page name
     * @return list of full chunks, empty if the page has no rows
     */
    public List< FullChunk > findFullByPage( final String pageName ) {
        final String sql = "SELECT id, chunk_index, heading_path, text, char_count, "
                         + "token_count_estimate, content_hash, created, modified "
                         + "FROM kg_content_chunks WHERE page_name = ? ORDER BY chunk_index";
        try {
            return query( sql, ps -> ps.setString( 1, pageName ), rs -> {
                final Array hp = rs.getArray( 3 );
                final List< String > headingPath;
                if( hp == null ) {
                    headingPath = List.of();
                } else {
                    final String[] arr = (String[]) hp.getArray();
                    headingPath = arr == null ? List.of() : List.of( arr );
                }
                final Timestamp createdTs = rs.getTimestamp( 8 );
                final Timestamp modifiedTs = rs.getTimestamp( 9 );
                return new FullChunk(
                    rs.getObject( 1, UUID.class ),
                    rs.getInt( 2 ),
                    headingPath,
                    rs.getString( 4 ),
                    rs.getInt( 5 ),
                    rs.getInt( 6 ),
                    rs.getString( 7 ),
                    createdTs == null ? null : createdTs.toInstant(),
                    modifiedTs == null ? null : modifiedTs.toInstant() );
            } );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find full chunks for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "findFullByPage failed for " + pageName, e );
        }
    }

    /**
     * Top mentions of a KG node — chunks the node appears in, ranked by
     * extractor confidence DESC then chunk_index ASC. Joins
     * {@code chunk_entity_mentions} to {@code kg_content_chunks}. Used by the
     * admin Edge Explorer to render the surrounding passage so a curator can
     * disambiguate the node (acronyms / initialisms / homonyms).
     *
     * @param nodeId target node id
     * @param limit  hard cap on returned rows (clamped to {@code [1, 50]})
     * @return ordered mentions; empty if the node has no rows in
     *         {@code chunk_entity_mentions}
     */
    public List< NodeMentionRow > findMentionsForNode( final UUID nodeId, final int limit ) {
        if ( nodeId == null ) return List.of();
        final int clamped = Math.max( 1, Math.min( 50, limit ) );
        final String sql =
              "SELECT c.id, c.page_name, c.chunk_index, c.heading_path, c.text, "
            + "       m.confidence, m.extractor "
            + "  FROM chunk_entity_mentions m "
            + "  JOIN kg_content_chunks c ON m.chunk_id = c.id "
            + " WHERE m.node_id = ? "
            + " ORDER BY m.confidence DESC, c.chunk_index ASC "
            + " LIMIT ?";
        try {
            return query( sql, ps -> {
                ps.setObject( 1, nodeId );
                ps.setInt( 2, clamped );
            }, rs -> {
                final Array hp = rs.getArray( 4 );
                final List< String > headingPath;
                if ( hp == null ) {
                    headingPath = List.of();
                } else {
                    final String[] arr = (String[]) hp.getArray();
                    headingPath = arr == null ? List.of() : List.of( arr );
                }
                return new NodeMentionRow(
                    rs.getObject( 1, UUID.class ),
                    rs.getString( 2 ),
                    rs.getInt( 3 ),
                    headingPath,
                    rs.getString( 5 ),
                    rs.getDouble( 6 ),
                    rs.getString( 7 ) );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "findMentionsForNode failed for node {}: {}", nodeId, e.getMessage(), e );
            throw new RuntimeException( "findMentionsForNode failed", e );
        }
    }

    /**
     * Row returned by {@link #findMentionsForNode(UUID, int)} — chunk fields
     * joined with the bridge row's confidence and extractor. Internal to the
     * repository; the service layer maps this to
     * {@link com.wikantik.api.knowledge.NodeMention}.
     */
    public record NodeMentionRow(
        UUID chunkId,
        String pageName,
        int chunkIndex,
        List< String > headingPath,
        String text,
        double confidence,
        String extractor
    ) {}

    /**
     * Case-insensitive substring search across chunks on a single page. Used
     * by the admin Edge Explorer's mention-panel fallback path when a concept
     * node was auto-created as an edge endpoint and has no
     * {@code chunk_entity_mentions} rows: we look up the originating
     * proposal's source page and surface chunks on that page that literally
     * contain the entity name, so a curator at least sees the page-level
     * context the LLM was reading. Ordered by {@code chunk_index}.
     *
     * @param pageName page to search
     * @param needle   substring to match (case-insensitive); blank or null returns empty
     * @param limit    hard cap on returned rows (clamped to {@code [1, 50]})
     * @return matching chunks, empty if the page or needle has no rows
     */
    public List< ChunkOnPage > findChunksOnPageContaining( final String pageName,
                                                           final String needle, final int limit ) {
        if ( pageName == null || pageName.isBlank() ) return List.of();
        if ( needle == null || needle.isBlank() ) return List.of();
        final int clamped = Math.max( 1, Math.min( 50, limit ) );
        final String sql =
              "SELECT id, page_name, chunk_index, heading_path, text "
            + "  FROM kg_content_chunks "
            + " WHERE page_name = ? "
            + "   AND POSITION( LOWER( ? ) IN LOWER( text ) ) > 0 "
            + " ORDER BY chunk_index ASC "
            + " LIMIT ?";
        try {
            return query( sql, ps -> {
                ps.setString( 1, pageName );
                ps.setString( 2, needle );
                ps.setInt( 3, clamped );
            }, rs -> {
                final Array hp = rs.getArray( 4 );
                final List< String > headingPath;
                if ( hp == null ) {
                    headingPath = List.of();
                } else {
                    final String[] arr = (String[]) hp.getArray();
                    headingPath = arr == null ? List.of() : List.of( arr );
                }
                return new ChunkOnPage(
                    rs.getObject( 1, UUID.class ),
                    rs.getString( 2 ),
                    rs.getInt( 3 ),
                    headingPath,
                    rs.getString( 5 ) );
            } );
        } catch ( final SQLException e ) {
            LOG.warn( "findChunksOnPageContaining failed for page '{}' / needle '{}': {}",
                pageName, needle, e.getMessage(), e );
            throw new RuntimeException( "findChunksOnPageContaining failed", e );
        }
    }

    /** Lightweight chunk projection used by the mention-panel fallback path. */
    public record ChunkOnPage(
        UUID chunkId,
        String pageName,
        int chunkIndex,
        List< String > headingPath,
        String text
    ) {}

    /**
     * Computes three small outlier lists (top 10 each) over the chunks table:
     * pages with the most chunks, single-chunk pages whose sole chunk has
     * &gt; 400 chars, and chunks whose estimated token count exceeds 512
     * (the chunker's {@code maxTokens} target). All three are lightweight
     * aggregates — no caching.
     *
     * @return populated outlier report (empty lists if nothing matches)
     */
    public OutlierReport outliers() {
        final String mostSql =
            "SELECT page_name, COUNT(*), MAX(token_count_estimate), "
          + "SUM(token_count_estimate), MAX(char_count) "
          + "FROM kg_content_chunks GROUP BY page_name "
          + "ORDER BY COUNT(*) DESC LIMIT 10";

        final String largeSql =
            "SELECT page_name, 1, token_count_estimate, token_count_estimate, char_count "
          + "FROM kg_content_chunks "
          + "WHERE page_name IN ( SELECT page_name FROM kg_content_chunks "
          + "                     GROUP BY page_name HAVING COUNT(*) = 1 ) "
          + "  AND char_count > 400 "
          + "ORDER BY char_count DESC LIMIT 10";

        final String oversizedSql =
            "SELECT page_name, 1, token_count_estimate, token_count_estimate, char_count "
          + "FROM kg_content_chunks "
          + "WHERE token_count_estimate > 512 "
          + "ORDER BY token_count_estimate DESC LIMIT 10";

        try {
            final List< OutlierEntry > most = query( mostSql, SqlBinder.NONE, ContentChunkRepository::mapOutlierEntry );
            final List< OutlierEntry > largeSingles = query( largeSql, SqlBinder.NONE, ContentChunkRepository::mapOutlierEntry );
            final List< OutlierEntry > oversized = query( oversizedSql, SqlBinder.NONE, ContentChunkRepository::mapOutlierEntry );
            return new OutlierReport( most, largeSingles, oversized );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to compute chunk outliers: {}", e.getMessage(), e );
            throw new RuntimeException( "outliers failed", e );
        }
    }

    private static OutlierEntry mapOutlierEntry( final ResultSet rs ) throws SQLException {
        return new OutlierEntry(
            rs.getString( 1 ),
            rs.getInt( 2 ),
            rs.getInt( 3 ),
            rs.getInt( 4 ),
            rs.getInt( 5 ) );
    }

    /**
     * Applies a chunk diff for a single page in a single transaction.
     * Deletes, updates, then inserts — all or nothing.
     *
     * @param pageName the wiki page name (for logging / diagnostics only)
     * @param diff     the computed diff to apply
     */
    public void apply( final String pageName, final ChunkDiff.Diff diff ) {
        // Both the original inner (per-statement failure, post-rollback) and outer
        // (connection-acquisition failure) catch blocks logged the identical message
        // and wrapped the identical exception, so a single try/catch around the whole
        // inTransaction() call is a faithful merge — not a behavior collapse.
        try {
            inTransaction( conn -> {
                for( final UUID id : diff.deletes() ) {
                    deleteById( conn, id );
                }
                for( final ChunkDiff.Update u : diff.updates() ) {
                    applyUpdate( conn, u );
                }
                for( final Chunk ins : diff.inserts() ) {
                    insert( conn, ins );
                }
                return null;
            } );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to apply chunk diff for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "apply failed for " + pageName, e );
        }
        // Post-commit cache invalidation: drop any cached MentionableChunk for
        // this page so a subsequent findByIds re-fetches the new text. Safe to
        // call even when the diff was a no-op (will just walk the cache once).
        invalidatePage( pageName );
    }

    /**
     * Removes every row from {@code kg_content_chunks}. Used by the rebuild
     * service to wipe the table before a full re-index.
     */
    public void deleteAll() {
        try {
            update( "DELETE FROM kg_content_chunks", SqlBinder.NONE );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete all chunks: {}", e.getMessage(), e );
            throw new RuntimeException( "deleteAll failed", e );
        }
        invalidateAll();
    }

    /**
     * Returns aggregate statistics over the chunks table.
     *
     * <p>{@link AggregateStats#pagesMissingChunks()} is always {@code 0} —
     * see the javadoc on {@link AggregateStats} for why.</p>
     *
     * @return aggregate stats
     */
    public AggregateStats stats() {
        final String sql = "SELECT "
                         + "COUNT( DISTINCT page_name ), COUNT(*), "
                         + "COALESCE( AVG( token_count_estimate ), 0 ), "
                         + "COALESCE( MIN( token_count_estimate ), 0 ), "
                         + "COALESCE( MAX( token_count_estimate ), 0 ) "
                         + "FROM kg_content_chunks";
        try {
            return queryOne( sql, SqlBinder.NONE, rs -> new AggregateStats(
                    rs.getInt( 1 ), 0, rs.getInt( 2 ),
                    (int) Math.round( rs.getDouble( 3 ) ),
                    rs.getInt( 4 ), rs.getInt( 5 ) ) )
                .orElse( new AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to compute chunk stats: {}", e.getMessage(), e );
            throw new RuntimeException( "stats failed", e );
        }
    }

    // ---- private helpers ----

    private void insert( final Connection conn, final Chunk ch ) throws SQLException {
        // D4: Concurrent PUTs to the same page produced a unique-constraint violation on
        // (page_name, chunk_index) because two threads computed independent diffs and
        // each tried to insert chunk 0 while the other was already mid-transaction. Use
        // an idempotent upsert keyed on the existing UNIQUE constraint so a parallel
        // re-chunking of identical text is a no-op and a parallel re-chunking with new
        // text simply refreshes the row's body, hash, and timestamps.
        final String sql = "INSERT INTO kg_content_chunks "
            + "( page_name, chunk_index, heading_path, text, char_count, "
            + "  token_count_estimate, content_hash ) "
            + "VALUES ( ?, ?, ?, ?, ?, ?, ? ) "
            + "ON CONFLICT ON CONSTRAINT kg_content_chunks_page_index_uniq DO UPDATE SET "
            + "  heading_path = EXCLUDED.heading_path, "
            + "  text = EXCLUDED.text, "
            + "  char_count = EXCLUDED.char_count, "
            + "  token_count_estimate = EXCLUDED.token_count_estimate, "
            + "  content_hash = EXCLUDED.content_hash, "
            + "  modified = CURRENT_TIMESTAMP";
        update( conn, sql, ps -> {
            final Array headingArray = conn.createArrayOf( "text", ch.headingPath().toArray() );
            ps.setString( 1, ch.pageName() );
            ps.setInt( 2, ch.chunkIndex() );
            ps.setArray( 3, headingArray );
            ps.setString( 4, ch.text() );
            ps.setInt( 5, ch.charCount() );
            ps.setInt( 6, ch.tokenCountEstimate() );
            ps.setString( 7, ch.contentHash() );
        } );
    }

    private void applyUpdate( final Connection conn, final ChunkDiff.Update u ) throws SQLException {
        final String sql = "UPDATE kg_content_chunks SET "
            + "heading_path = ?, text = ?, char_count = ?, "
            + "token_count_estimate = ?, content_hash = ?, modified = CURRENT_TIMESTAMP "
            + "WHERE id = ?";
        update( conn, sql, ps -> {
            final Chunk r = u.replacement();
            final Array headingArray = conn.createArrayOf( "text", r.headingPath().toArray() );
            ps.setArray( 1, headingArray );
            ps.setString( 2, r.text() );
            ps.setInt( 3, r.charCount() );
            ps.setInt( 4, r.tokenCountEstimate() );
            ps.setString( 5, r.contentHash() );
            ps.setObject( 6, u.existingId() );
        } );
    }

    private void deleteById( final Connection conn, final UUID id ) throws SQLException {
        update( conn, "DELETE FROM kg_content_chunks WHERE id = ?", ps -> ps.setObject( 1, id ) );
    }
}
