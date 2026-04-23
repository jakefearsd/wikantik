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

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC-based data access layer for the {@code kg_content_chunks} table.
 *
 * <p>Applies {@link ChunkDiff.Diff} operations for a single page atomically
 * (deletes, updates, inserts all run inside one transaction with
 * {@code autoCommit = false}).</p>
 *
 * <p>Matches the conventions of {@link com.wikantik.knowledge.JdbcKnowledgeRepository}:
 * plain JDBC via {@link DataSource}, {@link PreparedStatement}, error logging
 * at {@code warn} level followed by a wrapping {@link RuntimeException}.</p>
 *
 * @since 1.0
 */
public class ContentChunkRepository {

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

    private final DataSource dataSource;

    public ContentChunkRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            final List< String > names = new ArrayList<>();
            while( rs.next() ) {
                names.add( rs.getString( 1 ) );
            }
            return names;
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            try( ResultSet rs = ps.executeQuery() ) {
                final List< UUID > out = new ArrayList<>();
                while( rs.next() ) out.add( rs.getObject( 1, UUID.class ) );
                return out;
            }
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            try( ResultSet rs = ps.executeQuery() ) {
                final List< ChunkDiff.Stored > out = new ArrayList<>();
                while( rs.next() ) {
                    out.add( new ChunkDiff.Stored(
                        rs.getObject( 1, UUID.class ),
                        rs.getInt( 2 ),
                        rs.getString( 3 ) ) );
                }
                return out;
            }
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
        final String sql = "SELECT id, page_name, chunk_index, heading_path, text "
                         + "FROM kg_content_chunks WHERE id = ANY( ? )";
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            final UUID[] arr = ids.toArray( new UUID[ 0 ] );
            ps.setArray( 1, conn.createArrayOf( "uuid", arr ) );
            try( ResultSet rs = ps.executeQuery() ) {
                final List< MentionableChunk > out = new ArrayList<>();
                while( rs.next() ) {
                    final Array hp = rs.getArray( 4 );
                    final List< String > headingPath;
                    if( hp == null ) {
                        headingPath = List.of();
                    } else {
                        final String[] parts = (String[]) hp.getArray();
                        headingPath = parts == null ? List.of() : List.of( parts );
                    }
                    out.add( new MentionableChunk(
                        rs.getObject( 1, UUID.class ),
                        rs.getString( 2 ),
                        rs.getInt( 3 ),
                        headingPath,
                        rs.getString( 5 ) ) );
                }
                return out;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find chunks by ids (count={}): {}", ids.size(), e.getMessage(), e );
            throw new RuntimeException( "findByIds failed", e );
        }
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
        try( Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            try( ResultSet rs = ps.executeQuery() ) {
                final List< FullChunk > out = new ArrayList<>();
                while( rs.next() ) {
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
                    out.add( new FullChunk(
                        rs.getObject( 1, UUID.class ),
                        rs.getInt( 2 ),
                        headingPath,
                        rs.getString( 4 ),
                        rs.getInt( 5 ),
                        rs.getInt( 6 ),
                        rs.getString( 7 ),
                        createdTs == null ? null : createdTs.toInstant(),
                        modifiedTs == null ? null : modifiedTs.toInstant() ) );
                }
                return out;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find full chunks for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "findFullByPage failed for " + pageName, e );
        }
    }

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
        final List< OutlierEntry > most = new ArrayList<>();
        final List< OutlierEntry > largeSingles = new ArrayList<>();
        final List< OutlierEntry > oversized = new ArrayList<>();

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

        try( Connection conn = dataSource.getConnection() ) {
            readOutlierRows( conn, mostSql, most );
            readOutlierRows( conn, largeSql, largeSingles );
            readOutlierRows( conn, oversizedSql, oversized );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to compute chunk outliers: {}", e.getMessage(), e );
            throw new RuntimeException( "outliers failed", e );
        }
        return new OutlierReport( most, largeSingles, oversized );
    }

    private static void readOutlierRows( final Connection conn, final String sql,
                                         final List< OutlierEntry > sink ) throws SQLException {
        try( PreparedStatement ps = conn.prepareStatement( sql );
             ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                sink.add( new OutlierEntry(
                    rs.getString( 1 ),
                    rs.getInt( 2 ),
                    rs.getInt( 3 ),
                    rs.getInt( 4 ),
                    rs.getInt( 5 ) ) );
            }
        }
    }

    /**
     * Applies a chunk diff for a single page in a single transaction.
     * Deletes, updates, then inserts — all or nothing.
     *
     * @param pageName the wiki page name (for logging / diagnostics only)
     * @param diff     the computed diff to apply
     */
    public void apply( final String pageName, final ChunkDiff.Diff diff ) {
        try( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try {
                for( final UUID id : diff.deletes() ) {
                    deleteById( conn, id );
                }
                for( final ChunkDiff.Update u : diff.updates() ) {
                    update( conn, u );
                }
                for( final Chunk ins : diff.inserts() ) {
                    insert( conn, ins );
                }
                conn.commit();
            } catch( final SQLException e ) {
                conn.rollback();
                LOG.warn( "Failed to apply chunk diff for page '{}': {}", pageName, e.getMessage(), e );
                throw new RuntimeException( "apply failed for " + pageName, e );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to apply chunk diff for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "apply failed for " + pageName, e );
        }
    }

    /**
     * Removes every row from {@code kg_content_chunks}. Used by the rebuild
     * service to wipe the table before a full re-index.
     */
    public void deleteAll() {
        try( Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement() ) {
            st.executeUpdate( "DELETE FROM kg_content_chunks" );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete all chunks: {}", e.getMessage(), e );
            throw new RuntimeException( "deleteAll failed", e );
        }
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
        try( Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery( sql ) ) {
            if( !rs.next() ) {
                return new AggregateStats( 0, 0, 0, 0, 0, 0 );
            }
            return new AggregateStats(
                rs.getInt( 1 ), 0, rs.getInt( 2 ),
                (int) Math.round( rs.getDouble( 3 ) ),
                rs.getInt( 4 ), rs.getInt( 5 ) );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to compute chunk stats: {}", e.getMessage(), e );
            throw new RuntimeException( "stats failed", e );
        }
    }

    // ---- private helpers ----

    private void insert( final Connection conn, final Chunk ch ) throws SQLException {
        final String sql = "INSERT INTO kg_content_chunks "
            + "( page_name, chunk_index, heading_path, text, char_count, "
            + "  token_count_estimate, content_hash ) "
            + "VALUES ( ?, ?, ?, ?, ?, ?, ? )";
        try( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            final Array headingArray = conn.createArrayOf( "text", ch.headingPath().toArray() );
            ps.setString( 1, ch.pageName() );
            ps.setInt( 2, ch.chunkIndex() );
            ps.setArray( 3, headingArray );
            ps.setString( 4, ch.text() );
            ps.setInt( 5, ch.charCount() );
            ps.setInt( 6, ch.tokenCountEstimate() );
            ps.setString( 7, ch.contentHash() );
            ps.executeUpdate();
        }
    }

    private void update( final Connection conn, final ChunkDiff.Update u ) throws SQLException {
        final String sql = "UPDATE kg_content_chunks SET "
            + "heading_path = ?, text = ?, char_count = ?, "
            + "token_count_estimate = ?, content_hash = ?, modified = CURRENT_TIMESTAMP "
            + "WHERE id = ?";
        try( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            final Chunk r = u.replacement();
            final Array headingArray = conn.createArrayOf( "text", r.headingPath().toArray() );
            ps.setArray( 1, headingArray );
            ps.setString( 2, r.text() );
            ps.setInt( 3, r.charCount() );
            ps.setInt( 4, r.tokenCountEstimate() );
            ps.setString( 5, r.contentHash() );
            ps.setObject( 6, u.existingId() );
            ps.executeUpdate();
        }
    }

    private void deleteById( final Connection conn, final UUID id ) throws SQLException {
        try( PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM kg_content_chunks WHERE id = ?" ) ) {
            ps.setObject( 1, id );
            ps.executeUpdate();
        }
    }
}
