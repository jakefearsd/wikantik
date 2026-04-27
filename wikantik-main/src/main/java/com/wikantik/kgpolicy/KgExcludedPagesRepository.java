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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ExclusionReason;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC-based data access layer for the {@code kg_excluded_pages} table.
 *
 * <p>Applies reason-precedence upsert semantics: when a page already has an
 * exclusion row the strongest reason wins (
 * {@code system_page} &gt; {@code page_override} &gt; {@code cluster_policy}).
 * The {@link #release} operation only removes a row when its current reason
 * matches the supplied one, so flipping a cluster from exclude→include cannot
 * silently undo an unrelated {@code system_page} exclusion.</p>
 *
 * <p>Follows the same plain-JDBC conventions as
 * {@link KgClusterPolicyRepository}: try-with-resources throughout,
 * {@code LOG.warn()} on every {@link SQLException} before wrapping in a
 * {@link RuntimeException} with a context message.</p>
 *
 * @since 1.0
 */
public class KgExcludedPagesRepository {

    private static final Logger LOG = LogManager.getLogger( KgExcludedPagesRepository.class );

    private final DataSource ds;

    public KgExcludedPagesRepository( final DataSource ds ) {
        this.ds = ds;
    }

    /**
     * Returns the exclusion reason on file for the given page, or empty if the
     * page is not currently excluded.
     *
     * @param pageName wiki page name
     * @return reason wrapped in Optional
     */
    public Optional< ExclusionReason > findReason( final String pageName ) {
        final String sql = "SELECT reason FROM kg_excluded_pages WHERE page_name = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            try( ResultSet rs = ps.executeQuery() ) {
                if( rs.next() ) {
                    return ExclusionReason.fromWire( rs.getString( 1 ) );
                }
                return Optional.empty();
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to find exclusion reason for page '{}': {}", pageName, e.getMessage(), e );
            throw new RuntimeException( "findReason for " + pageName, e );
        }
    }

    /**
     * Inserts or upgrades the exclusion record for the given page.
     * On conflict the existing row is updated only if the incoming reason is
     * stronger; the strongest reason always wins.
     *
     * <p>Strength order: {@code system_page} &gt; {@code page_override} &gt;
     * {@code cluster_policy}.</p>
     *
     * @param pageName wiki page name
     * @param reason   exclusion reason
     */
    public void exclude( final String pageName, final ExclusionReason reason ) {
        final String sql =
            "INSERT INTO kg_excluded_pages (page_name, reason) VALUES (?, ?) " +
            "ON CONFLICT (page_name) DO UPDATE SET reason = " +
            "  CASE " +
            "    WHEN kg_excluded_pages.reason = 'system_page'   THEN kg_excluded_pages.reason " +
            "    WHEN EXCLUDED.reason            = 'system_page'   THEN EXCLUDED.reason " +
            "    WHEN kg_excluded_pages.reason = 'page_override' THEN kg_excluded_pages.reason " +
            "    WHEN EXCLUDED.reason            = 'page_override' THEN EXCLUDED.reason " +
            "    ELSE EXCLUDED.reason " +
            "  END";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            ps.setString( 2, reason.wire() );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to exclude page '{}' with reason '{}': {}", pageName, reason, e.getMessage(), e );
            throw new RuntimeException( "exclude page " + pageName + " reason " + reason, e );
        }
    }

    /**
     * Removes the exclusion record for the given page only when its current
     * stored reason matches {@code reason}. If the page has a stronger reason
     * on file the row is left intact.
     *
     * @param pageName wiki page name
     * @param reason   the reason the caller believes is on file
     */
    public void release( final String pageName, final ExclusionReason reason ) {
        final String sql = "DELETE FROM kg_excluded_pages WHERE page_name = ? AND reason = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, pageName );
            ps.setString( 2, reason.wire() );
            ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to release page '{}' with reason '{}': {}", pageName, reason, e.getMessage(), e );
            throw new RuntimeException( "release page " + pageName + " reason " + reason, e );
        }
    }

    /**
     * Returns the set of page names currently excluded for the given reason.
     *
     * @param reason filter — only pages with this exact reason are returned
     * @return mutable set of page names, never null
     */
    public Set< String > listByReason( final ExclusionReason reason ) {
        final String sql = "SELECT page_name FROM kg_excluded_pages WHERE reason = ?";
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, reason.wire() );
            try( ResultSet rs = ps.executeQuery() ) {
                final Set< String > out = new HashSet<>();
                while( rs.next() ) {
                    out.add( rs.getString( 1 ) );
                }
                return out;
            }
        } catch( final SQLException e ) {
            LOG.warn( "Failed to list excluded pages for reason '{}': {}", reason, e.getMessage(), e );
            throw new RuntimeException( "listByReason " + reason, e );
        }
    }

    /**
     * Deletes all exclusion rows whose page name is in the supplied list,
     * regardless of reason. Returns the number of rows actually deleted.
     *
     * @param pageNames names to remove; ignored if null or empty
     * @return count of deleted rows
     */
    public int removeAll( final List< String > pageNames ) {
        if( pageNames == null || pageNames.isEmpty() ) {
            return 0;
        }
        final StringBuilder sb = new StringBuilder( "DELETE FROM kg_excluded_pages WHERE page_name IN (" );
        for( int i = 0; i < pageNames.size(); i++ ) {
            if( i > 0 ) {
                sb.append( ", " );
            }
            sb.append( '?' );
        }
        sb.append( ')' );
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( sb.toString() ) ) {
            for( int i = 0; i < pageNames.size(); i++ ) {
                ps.setString( i + 1, pageNames.get( i ) );
            }
            return ps.executeUpdate();
        } catch( final SQLException e ) {
            LOG.warn( "Failed to removeAll {} pages: {}", pageNames.size(), e.getMessage(), e );
            throw new RuntimeException( "removeAll " + pageNames.size() + " pages", e );
        }
    }
}
