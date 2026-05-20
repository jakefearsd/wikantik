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
package com.wikantik.search.embedding;

import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * One-shot backfill — decodes BYTEA from {@code content_chunk_embeddings.vec}
 * and writes the same vector to {@code embedding vector(1024)} via the pgvector
 * string-literal codec. Idempotent: rows where {@code embedding IS NOT NULL}
 * are skipped unless {@code force} is true.
 *
 * <p>Corrupted rows (BYTEA length != {@code dim * 4}) are logged and skipped —
 * the backfill must not be capped at a single bad row.</p>
 *
 * <p>Invoked via {@code bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh}.</p>
 */
public final class PgVectorBackfillCli {

    private static final Logger LOG = LogManager.getLogger( PgVectorBackfillCli.class );

    private final DataSource dataSource;

    public PgVectorBackfillCli( final DataSource dataSource ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        this.dataSource = dataSource;
    }

    /**
     * Backfill all rows for {@code modelCode}. Returns the number of rows written.
     * When {@code force} is true, overwrites existing {@code embedding} values;
     * otherwise skips rows where {@code embedding IS NOT NULL}.
     */
    public int run( final String modelCode, final boolean force ) {
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }

        final String selectSql = force
            ? "SELECT chunk_id, dim, vec FROM content_chunk_embeddings WHERE model_code = ?"
            : "SELECT chunk_id, dim, vec FROM content_chunk_embeddings "
            + "WHERE model_code = ? AND embedding IS NULL";

        final String updateSql =
            "UPDATE content_chunk_embeddings SET embedding = ?::vector "
          + "WHERE chunk_id = ? AND model_code = ?";

        int written = 0;
        int skipped = 0;
        try ( Connection c = dataSource.getConnection() ) {
            final boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit( false );
            try {
                try ( PreparedStatement sel = c.prepareStatement( selectSql );
                      PreparedStatement upd = c.prepareStatement( updateSql ) ) {
                    sel.setString( 1, modelCode );
                    sel.setFetchSize( 500 );
                    try ( ResultSet rs = sel.executeQuery() ) {
                        while ( rs.next() ) {
                            final UUID id = rs.getObject( 1, UUID.class );
                            final int dim = rs.getInt( 2 );
                            final byte[] raw = rs.getBytes( 3 );
                            final float[] decoded = decode( id, raw, dim );
                            if ( decoded == null ) {
                                skipped++;
                                continue;
                            }
                            upd.setString( 1, PgVectorChunkVectorIndex.formatVector( decoded ) );
                            upd.setObject( 2, id );
                            upd.setString( 3, modelCode );
                            upd.executeUpdate();
                            written++;
                        }
                    }
                }
                c.commit();
            } catch ( final SQLException inner ) {
                try {
                    c.rollback();
                } catch ( final SQLException rb ) {
                    LOG.warn( "PgVectorBackfillCli rollback failed (model={}): {}",
                        modelCode, rb.getMessage(), rb );
                }
                throw inner;
            } finally {
                try {
                    c.setAutoCommit( prevAutoCommit );
                } catch ( final SQLException acRestore ) {
                    LOG.warn( "PgVectorBackfillCli autoCommit restore failed (model={}): {}",
                        modelCode, acRestore.getMessage(), acRestore );
                }
            }
        } catch ( final SQLException e ) {
            LOG.warn( "PgVectorBackfillCli failed (model={}, force={}, written-so-far={}): {}",
                modelCode, force, written, e.getMessage(), e );
            throw new RuntimeException( "Backfill failed for model " + modelCode, e );
        }
        LOG.info( "PgVectorBackfillCli: model={} force={} wrote={} skipped={}",
            modelCode, force, written, skipped );
        return written;
    }

    private static float[] decode( final UUID id, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "Backfill: chunk {} has null vec, skipping", id );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "Backfill: chunk {} vec bytes={} expected {} (dim={}), skipping",
                id, raw.length, dim * Float.BYTES, dim );
            return null;
        }
        final float[] out = new float[ dim ];
        final FloatBuffer fb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer();
        fb.get( out );
        return out;
    }

    /**
     * CLI entrypoint used by {@code bin/db/one-shot/2026-05-20-backfill-chunk-embeddings.sh}.
     * Reads JDBC connection settings from {@code PGHOST}, {@code PGPORT},
     * {@code PGUSER}, {@code PGPASSWORD}, {@code DB_NAME} environment variables
     * (same convention as {@code bin/db/migrate.sh}).
     * Exits with code 0 on success, or 1 on any failure, 2 on usage error.
     *
     * @param args {@code <modelCode> [--force]}
     */
    public static void main( final String[] args ) {
        if ( args.length < 1 ) {
            System.err.println( "Usage: PgVectorBackfillCli <modelCode> [--force]" );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final boolean force = args.length > 1 && "--force".equals( args[ 1 ] );

        try {
            final DataSource ds = resolveDataSourceFromEnv();
            final int written = new PgVectorBackfillCli( ds ).run( modelCode, force );
            System.out.println( "backfill wrote " + written + " rows" );
        } catch ( final Throwable t ) {
            LOG.warn( "PgVectorBackfillCli main failed: {}", t.getMessage(), t );
            System.err.println( "backfill failed: " + t.getMessage() );
            System.exit( 1 );
        }
    }

    /**
     * Build a {@link DataSource} from PG env vars. Uses {@code org.postgresql.ds.PGSimpleDataSource}
     * directly to avoid pulling DBCP into the CLI path — the shell wrapper invokes the
     * jar once per backfill run, then exits, so connection pooling is overkill.
     *
     * <p>Env-var names match {@code bin/db/migrate.sh}: {@code PGHOST}, {@code PGPORT},
     * {@code PGUSER}, {@code PGPASSWORD}, {@code DB_NAME} (fallback: {@code PGDATABASE},
     * then {@code "wikantik"}).</p>
     */
    private static DataSource resolveDataSourceFromEnv() {
        final String host = envOr( "PGHOST", "localhost" );
        final int port = Integer.parseInt( envOr( "PGPORT", "5432" ) );
        final String db = envOr( "DB_NAME", envOr( "PGDATABASE", "wikantik" ) );
        final String user = envOr( "PGUSER", "jspwiki" );
        final String password = envOr( "PGPASSWORD", "" );

        final org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setServerNames( new String[] { host } );
        ds.setPortNumbers( new int[] { port } );
        ds.setDatabaseName( db );
        ds.setUser( user );
        ds.setPassword( password );
        return ds;
    }

    private static String envOr( final String key, final String fallback ) {
        final String v = System.getenv( key );
        return v == null || v.isEmpty() ? fallback : v;
    }
}
