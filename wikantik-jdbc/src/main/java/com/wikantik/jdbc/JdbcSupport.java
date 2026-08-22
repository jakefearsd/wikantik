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
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC scaffolding for the Knowledge Graph and Page Graph repositories/DAOs
 * ({@code com.wikantik.knowledge.KgJdbcSupport} and its subclasses; the
 * {@code com.wikantik.pagegraph.spine} structural-spine DAOs), factored out during
 * the GoF Template Method boilerplate consolidation into a neutral package so both
 * subsystems can share it without either depending on the other.
 *
 * <p>A thin {@code extends}-convenience wrapper: every protected method here is a one-line
 * delegate to a private {@link Jdbc} instance, kept only so the existing subclasses go on
 * compiling unchanged. New code should prefer holding a {@link Jdbc} field directly over
 * extending this class. These deliberately do NOT decide the exception policy (log level,
 * wrap-vs-swallow, message text) — that varies per call site across the repositories (some
 * log-and-rethrow, some log-and-return-a-default, some include the stack trace and some don't).
 * Each primitive declares {@code throws SQLException} and leaves the catch block, log call, and
 * exception contract entirely to the caller, so a migrated method still reads as
 * {@code try { return query(sql, binder, mapper); } catch (SQLException e) { ...caller's own policy... }}
 * — SQL + binding + mapping in the try, nothing else.</p>
 *
 * @since 1.0
 */
public abstract class JdbcSupport {

    protected final DataSource dataSource;
    private final Jdbc jdbc;

    protected JdbcSupport( final DataSource dataSource ) {
        this.dataSource = dataSource;
        this.jdbc = new Jdbc( dataSource );
    }

    protected abstract Logger log();

    /** Runs {@code sql}, binds params via {@code binder}, and maps every row with {@code mapper}. */
    protected < T > List< T > query( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        return jdbc.query( sql, binder, mapper );
    }

    /** {@link #query(String, SqlBinder, RowMapper)} on an already-open connection (e.g. inside a transaction). */
    protected < T > List< T > query( final Connection conn, final String sql, final SqlBinder binder,
                                      final RowMapper< T > mapper ) throws SQLException {
        return jdbc.query( conn, sql, binder, mapper );
    }

    /** Runs {@code sql} expecting at most one row; empty {@link Optional} if none matched. */
    protected < T > Optional< T > queryOne( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        return jdbc.queryOne( sql, binder, mapper );
    }

    /** {@link #queryOne(String, SqlBinder, RowMapper)} on an already-open connection. */
    protected < T > Optional< T > queryOne( final Connection conn, final String sql, final SqlBinder binder,
                                             final RowMapper< T > mapper ) throws SQLException {
        return jdbc.queryOne( conn, sql, binder, mapper );
    }

    /** Runs an INSERT/UPDATE/DELETE, returning the affected-row count. */
    protected int update( final String sql, final SqlBinder binder ) throws SQLException {
        return jdbc.update( sql, binder );
    }

    /** {@link #update(String, SqlBinder)} on an already-open connection. */
    protected < K > Optional< K > insertReturningKey( final String sql, final SqlBinder binder,
                                                       final RowMapper< K > keyMapper ) throws SQLException {
        return jdbc.insertReturningKey( sql, binder, keyMapper );
    }

    protected < K > Optional< K > insertReturningKey( final Connection conn, final String sql,
                                                       final SqlBinder binder, final RowMapper< K > keyMapper )
            throws SQLException {
        return jdbc.insertReturningKey( conn, sql, binder, keyMapper );
    }

    protected int update( final Connection conn, final String sql, final SqlBinder binder ) throws SQLException {
        return jdbc.update( conn, sql, binder );
    }

    protected int[] batch( final Connection conn, final String sql, final List< SqlBinder > binders )
            throws SQLException {
        return jdbc.batch( conn, sql, binders );
    }

    protected void forEachRow( final String sql, final SqlBinder binder, final int fetchSize,
                               final RowConsumer consumer ) throws SQLException {
        jdbc.forEachRow( sql, binder, fetchSize, consumer );
    }

    protected void forEachRow( final Connection conn, final String sql, final SqlBinder binder,
                               final int fetchSize, final RowConsumer consumer ) throws SQLException {
        jdbc.forEachRow( conn, sql, binder, fetchSize, consumer );
    }

    protected void execute( final String sql ) throws SQLException {
        jdbc.execute( sql );
    }

    protected void execute( final Connection conn, final String sql ) throws SQLException {
        jdbc.execute( conn, sql );
    }

    /** See {@link Jdbc#withConnection}: one lent connection, no transaction. */
    protected < T > T withConnection( final TransactionBody< T > body ) throws SQLException {
        return jdbc.withConnection( body );
    }

    /**
     * Runs {@code body} inside a single transaction. Delegates to {@link Jdbc#inTransaction} —
     * see its Javadoc for the full contract (rollback on any {@link Throwable}, auto-commit
     * restored only after commit/rollback has run).
     */
    protected < T > T inTransaction( final TransactionBody< T > body ) throws SQLException {
        return jdbc.inTransaction( body );
    }
}
