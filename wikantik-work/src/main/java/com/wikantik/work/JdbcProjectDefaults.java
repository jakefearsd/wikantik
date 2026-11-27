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
package com.wikantik.work;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.NullableColumns;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The project's default product: the guarded write and the clearing done when a product is archived. */
final class JdbcProjectDefaults {

    private static final String NOTE = "default_product";

    private JdbcProjectDefaults() {
    }

    /** Sets or clears a project's default; writes one settings event, and only when the value changed. */
    static void set( final JdbcWorkCatalog catalog, final Connection conn, final long projectId, final Product productOrNull,
                     final Actor by ) throws SQLException {
        final Jdbc jdbc = catalog.jdbc;
        // the mapper yields an Optional because queryOne reads a null row value as "no row"
        final Long current = jdbc.queryOne( conn, "SELECT default_product_id FROM work_project WHERE id = ? FOR UPDATE",
                ps -> ps.setLong( 1, projectId ),
                rs -> Optional.ofNullable( NullableColumns.getNullableLong( rs, "default_product_id" ) ) )
            .orElseThrow( () -> new SQLException( "work_project " + projectId + " not found" ) )
            .orElse( null );
        final Long next = productOrNull == null ? null : productOrNull.id();
        if ( Objects.equals( current, next ) ) {
            return;
        }
        final String fromKey = current == null ? null : jdbc.queryOne( conn,
            "SELECT key FROM work_product WHERE id = ?", ps -> ps.setLong( 1, current ),
            rs -> rs.getString( "key" ) ).orElse( null );
        if ( productOrNull != null ) {
            // FOR SHARE conflicts with archiveProduct's row lock, so an archive in flight serializes with this write
            final boolean archived = jdbc.queryOne( conn, "SELECT archived_at FROM work_product WHERE id = ? FOR SHARE",
                    ps -> ps.setLong( 1, productOrNull.id() ), rs -> rs.getTimestamp( "archived_at" ) != null )
                .orElse( true );
            if ( archived ) {
                throw new WorkValidationException( "defaultProductKey", "No such product: " + productOrNull.key() );
            }
        }
        jdbc.update( conn, "UPDATE work_project SET default_product_id = ? WHERE id = ?", ps -> {
            NullableColumns.setNullableLong( ps, 1, next );
            ps.setLong( 2, projectId );
        } );
        catalog.appendEvent( conn, JdbcWorkCatalog.projectEvent( projectId, by, WorkEvent.SETTINGS,
            fromKey, productOrNull == null ? null : productOrNull.key(), NOTE ) );
    }

    /** Clears an archived product from every project that defaults to it, one settings event per project. */
    static void clearFor( final JdbcWorkCatalog catalog, final Connection conn, final Product archived, final Actor by )
            throws SQLException {
        final List< Long > projects = catalog.jdbc.query( conn, "UPDATE work_project SET default_product_id = NULL "
                + "WHERE default_product_id = ? RETURNING id", ps -> ps.setLong( 1, archived.id() ),
            rs -> rs.getLong( "id" ) );
        for ( final Long projectId : projects ) {
            catalog.appendEvent( conn, JdbcWorkCatalog.projectEvent( projectId, by,
                WorkEvent.SETTINGS, archived.key(), null, NOTE ) );
        }
    }
}
