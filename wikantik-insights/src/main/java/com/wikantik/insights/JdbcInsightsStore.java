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
package com.wikantik.insights;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/**
 * JDBC-backed implementation of InsightsStore that upserts search visibility snapshots
 * using PostgreSQL ON CONFLICT syntax.
 */
public class JdbcInsightsStore implements InsightsStore {

    private static final Logger LOG = LogManager.getLogger( JdbcInsightsStore.class );

    private static final String INSERT_SQL = """
        INSERT INTO search_visibility_snapshot
            (snapshot_date, window_days, engine, site_host, page_path, query_text,
             impressions, clicks, position)
        VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT (snapshot_date, engine, site_host, page_path, query_text)
        DO UPDATE SET impressions = EXCLUDED.impressions,
                      clicks      = EXCLUDED.clicks,
                      position    = EXCLUDED.position,
                      window_days = EXCLUDED.window_days,
                      ingested_at = NOW()
        """;

    private final DataSource dataSource;

    /**
     * Creates a new JdbcInsightsStore backed by the given DataSource.
     *
     * @param dataSource the data source to use for connections
     */
    public JdbcInsightsStore( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    @Override
    public int upsert( final List<VisibilityRow> rows ) {
        if ( rows.isEmpty() ) {
            return 0;
        }

        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {

            for ( final VisibilityRow row : rows ) {
                ps.setDate( 1, Date.valueOf( row.snapshotDate() ) );
                ps.setInt( 2, row.windowDays() );
                ps.setString( 3, row.engine() );
                ps.setString( 4, row.siteHost() );
                ps.setString( 5, row.pagePath() );
                ps.setString( 6, row.queryText() );
                ps.setInt( 7, row.impressions() );
                ps.setInt( 8, row.clicks() );

                if ( row.position() == null ) {
                    ps.setNull( 9, Types.NUMERIC );
                } else {
                    ps.setDouble( 9, row.position() );
                }

                ps.addBatch();
            }

            final int[] results = ps.executeBatch();
            int total = 0;
            for ( final int result : results ) {
                total += Math.max( result, 0 );
            }

            return total;

        } catch ( final SQLException e ) {
            LOG.warn( "Failed to upsert {} visibility rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }
}
