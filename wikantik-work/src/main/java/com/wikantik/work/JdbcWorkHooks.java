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
import com.wikantik.jdbc.RowMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.wikantik.jdbc.NullableColumns.getInstant;
import static com.wikantik.work.SqlValues.timestamp;

/** Hook and identity rows of the code-host store (webhook secrets and learned usernames). */
final class JdbcWorkHooks {

    private static final Logger LOG = LogManager.getLogger( JdbcWorkHooks.class );

    private static final String SELECT = "SELECT product_id, secret, created_by, created_at, last_received_at, "
        + "disabled_at FROM work_product_hook";

    private static final RowMapper< WorkCodeHostStore.Hook > ROW = rs -> new WorkCodeHostStore.Hook(
        rs.getLong( "product_id" ), rs.getString( "secret" ), rs.getString( "created_by" ),
        getInstant( rs, "created_at" ), getInstant( rs, "last_received_at" ), getInstant( rs, "disabled_at" ) );

    private final Jdbc jdbc;
    private final JdbcWorkCatalog catalog;

    JdbcWorkHooks( final Jdbc jdbc, final JdbcWorkCatalog catalog ) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    Optional< WorkCodeHostStore.Hook > hook( final long productId ) throws SQLException {
        return jdbc.queryOne( SELECT + " WHERE product_id = ?", ps -> ps.setLong( 1, productId ), ROW );
    }

    Map< Long, WorkCodeHostStore.Hook > hooks() throws SQLException {
        final Map< Long, WorkCodeHostStore.Hook > out = new LinkedHashMap<>();
        for ( final WorkCodeHostStore.Hook h : jdbc.query( SELECT + " ORDER BY product_id", ps -> { }, ROW ) ) {
            out.put( h.productId(), h );
        }
        return out;
    }

    boolean putHook( final Product product, final String secret, final Instant at, final Actor by )
            throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final Optional< WorkCodeHostStore.Hook > old = jdbc.queryOne( conn, SELECT + " WHERE product_id = ? FOR UPDATE",
                ps -> ps.setLong( 1, product.id() ), ROW );
            final boolean rotated = old.isPresent() && old.get().live();
            jdbc.update( conn, "INSERT INTO work_product_hook (product_id, secret, created_by, created_at) "
                    + "VALUES (?,?,?,?) ON CONFLICT (product_id) DO UPDATE SET secret = EXCLUDED.secret, "
                    + "created_by = EXCLUDED.created_by, created_at = EXCLUDED.created_at, disabled_at = NULL",
                ps -> {
                    ps.setLong( 1, product.id() );
                    ps.setString( 2, secret );
                    ps.setString( 3, by.login() );
                    ps.setTimestamp( 4, timestamp( at ) );
                } );
            final String before = old.isEmpty() ? null : old.get().live() ? "enabled" : "disabled";
            catalog.appendProductEvent( conn, product.id(), by, "hook", before, rotated ? "rotated" : "enabled" );
            return rotated;
        } );
    }

    boolean disableHook( final Product product, final Instant at, final Actor by ) throws SQLException {
        JdbcWorkCatalog.requireAttributed( by.kind(), by.agentId() );
        return jdbc.inTransaction( conn -> {
            final int changed = jdbc.update( conn, "UPDATE work_product_hook SET disabled_at = ? "
                    + "WHERE product_id = ? AND disabled_at IS NULL",
                ps -> {
                    ps.setTimestamp( 1, timestamp( at ) );
                    ps.setLong( 2, product.id() );
                } );
            if ( changed > 0 ) {
                catalog.appendProductEvent( conn, product.id(), by, "hook", "enabled", "disabled" );
            }
            return changed > 0;
        } );
    }

    void received( final long productId, final Instant at ) throws SQLException {
        jdbc.update( "UPDATE work_product_hook SET last_received_at = ? WHERE product_id = ?", ps -> {
            ps.setTimestamp( 1, timestamp( at ) );
            ps.setLong( 2, productId );
        } );
    }

    Optional< String > learned( final String host, final String username ) throws SQLException {
        return jdbc.queryOne( "SELECT login FROM work_codehost_identity WHERE host = ? AND username = ?", ps -> {
            ps.setString( 1, host );
            ps.setString( 2, username );
        }, rs -> rs.getString( 1 ) );
    }

    /** First writer wins; a different mapping that loses the race is logged at WARN, never applied. */
    void learn( final String host, final String username, final String login ) throws SQLException {
        final int made = jdbc.update( "INSERT INTO work_codehost_identity (host, username, login) VALUES (?,?,?) "
            + "ON CONFLICT (host, username) DO NOTHING", ps -> {
                ps.setString( 1, host );
                ps.setString( 2, username );
                ps.setString( 3, login );
            } );
        if ( made == 0 && learned( host, username ).filter( login::equals ).isEmpty() ) {
            LOG.warn( "Code-host username on host {} is already matched to another wiki account; not changed", host );
        }
    }
}
