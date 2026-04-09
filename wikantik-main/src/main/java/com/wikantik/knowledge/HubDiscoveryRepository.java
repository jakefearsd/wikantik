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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for cluster-discovery proposals. Accept and dismiss both
 * {@link #delete(int)} the row — there is no status column — so a row's mere
 * existence means "pending review".
 */
public class HubDiscoveryRepository {

    private static final Logger LOG = LogManager.getLogger( HubDiscoveryRepository.class );
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken< List< String > >() {}.getType();

    public record HubDiscoveryProposal( int id, String suggestedName, String exemplarPage,
                                         List< String > memberPages, double coherenceScore,
                                         Instant created ) {}

    private final DataSource dataSource;

    public HubDiscoveryRepository( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public int insert( final String suggestedName, final String exemplarPage,
                        final List< String > memberPages, final double coherenceScore ) {
        final String sql = "INSERT INTO hub_discovery_proposals "
            + "(suggested_name, exemplar_page, member_pages, coherence_score) "
            + "VALUES (?, ?, ?::jsonb, ?)";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            ps.setString( 1, suggestedName );
            ps.setString( 2, exemplarPage );
            ps.setString( 3, GSON.toJson( memberPages ) );
            ps.setDouble( 4, coherenceScore );
            ps.executeUpdate();
            try ( final ResultSet keys = ps.getGeneratedKeys() ) {
                if ( keys.next() ) return keys.getInt( 1 );
            }
            throw new SQLException( "No generated key returned" );
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to insert hub discovery proposal '{}': {}", suggestedName, e.getMessage() );
            throw new RuntimeException( "Failed to insert hub discovery proposal '" + suggestedName + "'", e );
        }
    }

    public HubDiscoveryProposal findById( final int id ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            try ( final ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? mapRow( rs ) : null;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to load hub discovery proposal {}: {}", id, e.getMessage() );
            return null;
        }
    }

    public List< HubDiscoveryProposal > list( final int limit, final int offset ) {
        final String sql = "SELECT id, suggested_name, exemplar_page, member_pages::text, "
            + "coherence_score, created FROM hub_discovery_proposals "
            + "ORDER BY created DESC LIMIT ? OFFSET ?";
        final List< HubDiscoveryProposal > out = new ArrayList<>();
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, limit );
            ps.setInt( 2, offset );
            try ( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) out.add( mapRow( rs ) );
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to list hub discovery proposals: {}", e.getMessage() );
        }
        return out;
    }

    public int count() {
        final String sql = "SELECT COUNT(*) FROM hub_discovery_proposals";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getInt( 1 ) : 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to count hub discovery proposals: {}", e.getMessage() );
            return 0;
        }
    }

    public boolean delete( final int id ) {
        final String sql = "DELETE FROM hub_discovery_proposals WHERE id = ?";
        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setInt( 1, id );
            return ps.executeUpdate() > 0;
        } catch ( final SQLException e ) {
            LOG.warn( "Failed to delete hub discovery proposal {}: {}", id, e.getMessage() );
            return false;
        }
    }

    private static HubDiscoveryProposal mapRow( final ResultSet rs ) throws SQLException {
        final List< String > members = GSON.fromJson( rs.getString( "member_pages" ), STRING_LIST );
        return new HubDiscoveryProposal(
            rs.getInt( "id" ),
            rs.getString( "suggested_name" ),
            rs.getString( "exemplar_page" ),
            members,
            rs.getDouble( "coherence_score" ),
            rs.getTimestamp( "created" ).toInstant()
        );
    }
}
