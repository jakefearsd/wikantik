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
package com.wikantik.knowledge;

import com.pgvector.PGvector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * JDBC persistence for TF-IDF content embeddings stored in the
 * {@code kg_content_embeddings} table. Uses pgvector for vector storage
 * in PostgreSQL.
 *
 * @since 1.0
 */
public class ContentEmbeddingRepository {

    private static final Logger LOG = LogManager.getLogger( ContentEmbeddingRepository.class );

    private final DataSource dataSource;

    public ContentEmbeddingRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /**
     * Persists all entity content embeddings from a trained TF-IDF model.
     */
    public void saveEmbeddings( final int modelVersion, final TfidfModel model,
                                final Map< String, UUID > entityUuids ) {
        final String sql = "INSERT INTO kg_content_embeddings (entity_id, entity_name, " +
            "embedding, model_version) VALUES (?, ?, ?, ?)";

        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {

            conn.setAutoCommit( false );

            // Remove any partial/stale rows for this version before inserting
            try( final PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM kg_content_embeddings WHERE model_version = ?" ) ) {
                del.setInt( 1, modelVersion );
                del.executeUpdate();
            }

            for( int i = 0; i < model.getEntityCount(); i++ ) {
                final String name = model.getEntityNames().get( i );
                final UUID uuid = entityUuids.get( name );
                ps.setObject( 1, uuid );
                ps.setString( 2, name );
                setVector( ps, 3, model.getVector( i ) );
                ps.setInt( 4, modelVersion );
                ps.addBatch();
            }

            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit( true );
            LOG.info( "Saved {} content embeddings (version {})", model.getEntityCount(), modelVersion );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to save content embeddings", e );
            throw new RuntimeException( "Failed to save content embeddings", e );
        }
    }

    /**
     * Loads the latest content embedding model.
     *
     * @return the loaded model, or {@code null} if no embeddings exist
     */
    public TfidfModel loadLatestModel() {
        final int version = getLatestModelVersion();
        if( version < 0 ) return null;

        final List< String > names = new ArrayList<>();
        final List< float[] > vectors = new ArrayList<>();

        final String sql = "SELECT entity_name, embedding " +
            "FROM kg_content_embeddings WHERE model_version = ? ORDER BY entity_name";

        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, version );
            try( final ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    names.add( rs.getString( 1 ) );
                    vectors.add( getVector( rs, 2 ) );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to load content embeddings", e );
            return null;
        }

        if( names.isEmpty() ) return null;

        final TfidfModel model = new TfidfModel();
        model.restore( names, vectors );
        LOG.info( "Loaded content embedding model version {} ({} entities)", version, names.size() );
        return model;
    }

    /** Returns the highest model_version in the table, or -1 if none. */
    public int getLatestModelVersion() {
        final String sql = "SELECT MAX(model_version) FROM kg_content_embeddings";
        try( final Connection conn = dataSource.getConnection();
             final Statement st = conn.createStatement();
             final ResultSet rs = st.executeQuery( sql ) ) {
            if( rs.next() ) {
                final int v = rs.getInt( 1 );
                return rs.wasNull() ? -1 : v;
            }
            return -1;
        } catch( final SQLException e ) {
            LOG.warn( "Failed to query content model version", e );
            return -1;
        }
    }

    /** Deletes all embedding rows for versions older than {@code keepVersion}. */
    public void deleteOldVersions( final int keepVersion ) {
        final String sql = "DELETE FROM kg_content_embeddings WHERE model_version < ?";
        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, keepVersion );
            final int deleted = ps.executeUpdate();
            if( deleted > 0 ) {
                LOG.info( "Deleted {} old content embedding rows (versions < {})", deleted, keepVersion );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete old content embeddings", e );
        }
    }

    // ---- Vector helpers ----

    private void setVector( final PreparedStatement ps, final int idx, final float[] vec )
            throws SQLException {
        ps.setObject( idx, new PGvector( vec ).toString(), Types.OTHER );
    }

    private float[] getVector( final ResultSet rs, final int idx ) throws SQLException {
        final Object obj = rs.getObject( idx );
        if( obj instanceof PGvector pgvec ) {
            return pgvec.toArray();
        } else if( obj instanceof org.postgresql.util.PGobject pgo ) {
            final String raw = pgo.getValue();
            if( raw == null ) {
                throw new SQLException( "PGobject returned null vector value at column " + idx );
            }
            return EmbeddingRepository.parseVectorString( raw );
        }
        throw new SQLException( "Unexpected vector type: " + ( obj == null ? "null" : obj.getClass() ) );
    }
}
