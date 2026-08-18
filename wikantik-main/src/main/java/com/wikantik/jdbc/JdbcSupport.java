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
package com.wikantik.jdbc;

import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC scaffolding for the Knowledge Graph and Page Graph repositories/DAOs
 * ({@code com.wikantik.knowledge.KgJdbcSupport} and its subclasses; the
 * {@code com.wikantik.pagegraph.spine} structural-spine DAOs), factored out during
 * the GoF Template Method boilerplate consolidation into a neutral package so both
 * subsystems can share it without either depending on the other.
 *
 * <p>Extended in the JDBC-boilerplate Template Method pass with generic
 * connect&rarr;prepare&rarr;bind&rarr;execute&rarr;map primitives ({@link #query},
 * {@link #queryOne}, {@link #update}, {@link #inTransaction}). These deliberately
 * do NOT decide the exception policy (log level, wrap-vs-swallow, message text) —
 * that varies per call site across the repositories (some log-and-rethrow, some
 * log-and-return-a-default, some include the stack trace and some don't). Each
 * primitive declares {@code throws SQLException} and leaves the catch block, log
 * call, and exception contract entirely to the caller, so a migrated method still
 * reads as {@code try { return query(sql, binder, mapper); } catch (SQLException e) { ...caller's own policy... }}
 * — SQL + binding + mapping in the try, nothing else.</p>
 *
 * @since 1.0
 */
public abstract class JdbcSupport {

    protected final DataSource dataSource;

    protected JdbcSupport( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    protected abstract Logger log();

    /** Binds parameters onto a freshly-prepared statement. Stateless — no SQL, no mapping. */
    @FunctionalInterface
    protected interface SqlBinder {
        void bind( PreparedStatement ps ) throws SQLException;

        /** Shared no-op binder for parameterless statements. */
        SqlBinder NONE = ps -> { };

        /**
         * Binds {@code params} to {@code ?} placeholders 1..N in order. Factors out the
         * {@code for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i))}
         * loop repeated at almost every dynamic-filter call site across the KG repositories.
         */
        static SqlBinder positional( final List< Object > params ) {
            return ps -> {
                for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
            };
        }
    }

    /** Maps the current row of a positioned {@link ResultSet} to a {@code T}. */
    @FunctionalInterface
    protected interface RowMapper< T > {
        T map( ResultSet rs ) throws SQLException;
    }

    /** A unit of work run inside {@link #inTransaction}, given the transaction's connection. */
    @FunctionalInterface
    protected interface TransactionBody< T > {
        T run( Connection conn ) throws SQLException;
    }

    /** Runs {@code sql}, binds params via {@code binder}, and maps every row with {@code mapper}. */
    protected < T > List< T > query( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return query( conn, sql, binder, mapper );
        }
    }

    /** {@link #query(String, SqlBinder, RowMapper)} on an already-open connection (e.g. inside a transaction). */
    protected < T > List< T > query( final Connection conn, final String sql, final SqlBinder binder,
                                      final RowMapper< T > mapper ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            try ( ResultSet rs = ps.executeQuery() ) {
                final List< T > out = new ArrayList<>();
                while ( rs.next() ) out.add( mapper.map( rs ) );
                return out;
            }
        }
    }

    /** Runs {@code sql} expecting at most one row; empty {@link Optional} if none matched. */
    protected < T > Optional< T > queryOne( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return queryOne( conn, sql, binder, mapper );
        }
    }

    /** {@link #queryOne(String, SqlBinder, RowMapper)} on an already-open connection. */
    protected < T > Optional< T > queryOne( final Connection conn, final String sql, final SqlBinder binder,
                                             final RowMapper< T > mapper ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( mapper.map( rs ) ) : Optional.empty();
            }
        }
    }

    /** Runs an INSERT/UPDATE/DELETE, returning the affected-row count. */
    protected int update( final String sql, final SqlBinder binder ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return update( conn, sql, binder );
        }
    }

    /** {@link #update(String, SqlBinder)} on an already-open connection. */
    protected int update( final Connection conn, final String sql, final SqlBinder binder ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            return ps.executeUpdate();
        }
    }

    /**
     * Runs {@code body} inside a single transaction: autocommit off, commit on
     * normal return, rollback on any failure, autocommit always restored before
     * the connection is released back to the pool. Mirrors the hand-rolled
     * {@code setAutoCommit(false)/commit/rollback/restore} wrapper previously
     * duplicated across the KG repositories, chunk repository, and the pagegraph
     * spine DAOs.
     *
     * <p>Rolls back on <em>both</em> {@link SQLException} and unchecked
     * {@link RuntimeException} — some call sites throw a validation
     * {@link IllegalArgumentException} mid-transaction (e.g. "row not found")
     * and their original hand-rolled versions explicitly rolled back before
     * propagating it. Both branches rethrow the exact exception caught, unwrapped
     * — this primitive never decides the exception contract, only the rollback.</p>
     */
    protected < T > T inTransaction( final TransactionBody< T > body ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            conn.setAutoCommit( false );
            try {
                final T result = body.run( conn );
                conn.commit();
                return result;
            } catch ( final SQLException e ) {
                conn.rollback();
                throw e;
            } catch ( final RuntimeException e ) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit( true );
            }
        }
    }
}
