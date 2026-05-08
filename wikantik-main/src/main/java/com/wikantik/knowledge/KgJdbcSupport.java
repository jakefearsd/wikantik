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
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.lang.reflect.Type;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Shared JDBC scaffolding for {@link KgNodeRepository} and {@link KgEdgeRepository}.
 *
 * <p>Extracted in Phase 11.5 static-analysis cleanup to eliminate the CPD duplication block
 * ({@code parseJson}, {@code toInstant}, {@code queryDistinct}, {@code queryCount},
 * {@code executeDelete}) that was identical in both repositories.</p>
 *
 * @since 1.0
 */
abstract class KgJdbcSupport {

    protected static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    protected final DataSource dataSource;

    protected KgJdbcSupport( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    protected abstract Logger log();

    protected Map< String, Object > parseJson( final String json ) {
        if ( json == null || json.isBlank() ) return Map.of();
        final Map< String, Object > result = GSON.fromJson( json, MAP_TYPE );
        return result != null ? result : Map.of();
    }

    protected Instant toInstant( final Timestamp ts ) {
        return ts != null ? ts.toInstant() : null;
    }

    protected List< String > queryDistinct( final String sql ) {
        final List< String > results = new ArrayList<>();
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) results.add( rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            log().warn( "Failed to execute distinct query: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
        return results;
    }

    protected long queryCount( final String sql ) {
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( sql );
              ResultSet rs = ps.executeQuery() ) {
            return rs.next() ? rs.getLong( 1 ) : 0;
        } catch ( final SQLException e ) {
            log().warn( "Failed to execute count query: {}", e.getMessage(), e );
            throw new RuntimeException( e );
        }
    }

    protected int executeDelete( final String sql, final UUID id ) {
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setObject( 1, id );
            return ps.executeUpdate();
        } catch ( final SQLException e ) {
            log().warn( "deleteByProvenance({}) failed: {}", id, e.getMessage(), e );
            throw new RuntimeException( "deleteByProvenance failed: " + e.getMessage(), e );
        }
    }
}
