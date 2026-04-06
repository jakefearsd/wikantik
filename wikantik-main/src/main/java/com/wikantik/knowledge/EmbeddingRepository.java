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
 * JDBC persistence for ComplEx embeddings stored in the {@code kg_embeddings} table.
 * Uses pgvector for vector storage in PostgreSQL.
 *
 * @since 1.0
 */
public class EmbeddingRepository {

    private static final Logger LOG = LogManager.getLogger( EmbeddingRepository.class );

    private final DataSource dataSource;

    public EmbeddingRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /**
     * Persists all entity and relation embeddings from a trained model.
     * Real and imaginary components are concatenated into a single vector.
     *
     * @param modelVersion  version number for this training run
     * @param model         the trained ComplEx model
     * @param entityUuids   map of entity name to its kg_nodes UUID (relations have null UUID)
     */
    public void saveEmbeddings( final int modelVersion, final ComplExModel model,
                                final Map< String, UUID > entityUuids ) {
        final String sql = "INSERT INTO kg_embeddings (entity_id, entity_type, entity_name, " +
            "embedding, model_version) VALUES (?, ?, ?, ?, ?)";

        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {

            conn.setAutoCommit( false );

            // Remove any partial/stale rows for this version before inserting
            try( final PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM kg_embeddings WHERE model_version = ?" ) ) {
                del.setInt( 1, modelVersion );
                del.executeUpdate();
            }

            // Save entity embeddings (real + imag concatenated)
            for( int i = 0; i < model.getEntityCount(); i++ ) {
                final String name = model.getEntityNames().get( i );
                final UUID uuid = entityUuids.get( name );
                ps.setObject( 1, uuid );
                ps.setString( 2, "node" );
                ps.setString( 3, name );
                setVector( ps, 4, concatenate( model.getEntityReal( i ), model.getEntityImag( i ) ) );
                ps.setInt( 5, modelVersion );
                ps.addBatch();
            }

            // Save relation embeddings (real + imag concatenated)
            for( int i = 0; i < model.getRelationCount(); i++ ) {
                ps.setObject( 1, null );
                ps.setString( 2, "relation" );
                ps.setString( 3, model.getRelationNames().get( i ) );
                setVector( ps, 4, concatenate( model.getRelationReal( i ), model.getRelationImag( i ) ) );
                ps.setInt( 5, modelVersion );
                ps.addBatch();
            }

            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit( true );
            LOG.info( "Saved {} entity + {} relation embeddings (version {})",
                model.getEntityCount(), model.getRelationCount(), modelVersion );
        } catch( final SQLException e ) {
            LOG.warn( "Failed to save embeddings", e );
            throw new RuntimeException( "Failed to save embeddings", e );
        }
    }

    /**
     * Loads the latest model version's embeddings into a fresh ComplExModel.
     * The stored vector is split back into real and imaginary halves.
     *
     * @return the loaded model, or {@code null} if no embeddings exist
     */
    public ComplExModel loadLatestModel() {
        final int version = getLatestModelVersion();
        if( version < 0 ) return null;

        final List< String > entityNames = new ArrayList<>();
        final List< float[] > entityReals = new ArrayList<>();
        final List< float[] > entityImags = new ArrayList<>();
        final List< String > relationNames = new ArrayList<>();
        final List< float[] > relationReals = new ArrayList<>();
        final List< float[] > relationImags = new ArrayList<>();

        final String sql = "SELECT entity_type, entity_name, embedding " +
            "FROM kg_embeddings WHERE model_version = ? ORDER BY entity_type, entity_name";

        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, version );
            try( final ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final String type = rs.getString( 1 );
                    final String name = rs.getString( 2 );
                    final float[] vec = getVector( rs, 3 );
                    final int half = vec.length / 2;
                    final float[] real = Arrays.copyOfRange( vec, 0, half );
                    final float[] imag = Arrays.copyOfRange( vec, half, vec.length );
                    if( "node".equals( type ) ) {
                        entityNames.add( name );
                        entityReals.add( real );
                        entityImags.add( imag );
                    } else {
                        relationNames.add( name );
                        relationReals.add( real );
                        relationImags.add( imag );
                    }
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to load embeddings", e );
            return null;
        }

        if( entityNames.isEmpty() ) return null;

        // Reconstruct model by training with 0 epochs (just sets up the index and arrays)
        final int dim = entityReals.get( 0 ).length;
        final ComplExModel model = new ComplExModel();
        model.train( entityNames, relationNames, List.of(), dim, 0, 0, 0, 0 );

        // Overwrite the embeddings with loaded values
        for( int i = 0; i < entityNames.size(); i++ ) {
            System.arraycopy( entityReals.get( i ), 0, model.getEntityReal( i ), 0, dim );
            System.arraycopy( entityImags.get( i ), 0, model.getEntityImag( i ), 0, dim );
        }
        for( int i = 0; i < relationNames.size(); i++ ) {
            System.arraycopy( relationReals.get( i ), 0, model.getRelationReal( i ), 0, dim );
            System.arraycopy( relationImags.get( i ), 0, model.getRelationImag( i ), 0, dim );
        }

        LOG.info( "Loaded ComplEx model version {} ({} entities, {} relations, dim={})",
            version, entityNames.size(), relationNames.size(), dim );
        return model;
    }

    /** Returns the highest model_version in the table, or -1 if none. */
    public int getLatestModelVersion() {
        final String sql = "SELECT MAX(model_version) FROM kg_embeddings";
        try( final Connection conn = dataSource.getConnection();
             final Statement st = conn.createStatement();
             final ResultSet rs = st.executeQuery( sql ) ) {
            if( rs.next() ) {
                final int v = rs.getInt( 1 );
                return rs.wasNull() ? -1 : v;
            }
            return -1;
        } catch( final SQLException e ) {
            LOG.warn( "Failed to query model version", e );
            return -1;
        }
    }

    /** Deletes all embedding rows for versions older than {@code keepVersion}. */
    public void deleteOldVersions( final int keepVersion ) {
        final String sql = "DELETE FROM kg_embeddings WHERE model_version < ?";
        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, keepVersion );
            final int deleted = ps.executeUpdate();
            if( deleted > 0 ) {
                LOG.info( "Deleted {} old embedding rows (versions < {})", deleted, keepVersion );
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to delete old embeddings", e );
        }
    }

    // ---- Vector helpers ----

    /** Concatenates two float arrays into one (real + imag → single vector). */
    static float[] concatenate( final float[] a, final float[] b ) {
        final float[] result = new float[ a.length + b.length ];
        System.arraycopy( a, 0, result, 0, a.length );
        System.arraycopy( b, 0, result, a.length, b.length );
        return result;
    }

    void setVector( final PreparedStatement ps, final int idx, final float[] vec )
            throws SQLException {
        ps.setObject( idx, new PGvector( vec ).toString(), Types.OTHER );
    }

    float[] getVector( final ResultSet rs, final int idx ) throws SQLException {
        final Object obj = rs.getObject( idx );
        if( obj instanceof PGvector pgvec ) {
            return pgvec.toArray();
        } else if( obj instanceof org.postgresql.util.PGobject pgo ) {
            return parseVectorString( pgo.getValue() );
        }
        throw new SQLException( "Unexpected vector type: " + ( obj == null ? "null" : obj.getClass() ) );
    }

    static float[] parseVectorString( final String s ) {
        final String trimmed = s.trim();
        final String inner = trimmed.substring( trimmed.indexOf( '[' ) + 1, trimmed.lastIndexOf( ']' ) );
        final String[] parts = inner.split( "," );
        final float[] result = new float[ parts.length ];
        for( int i = 0; i < parts.length; i++ ) {
            result[ i ] = Float.parseFloat( parts[ i ].trim() );
        }
        return result;
    }
}
