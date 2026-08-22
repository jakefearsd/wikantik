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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one primitive production code uses to talk to the database: connect, bind, execute, map —
 * plus the single transaction wrapper ({@link #inTransaction}) that every hand-rolled
 * {@code setAutoCommit}/{@code commit}/{@code rollback} block in this codebase should be replaced
 * with. Composition, not inheritance ({@code new Jdbc(dataSource)}), so it works equally for a
 * class that already extends something else (use {@link #inTransaction} directly) and for one
 * that doesn't (extend {@link JdbcSupport}, a thin convenience wrapper around an instance of this
 * class).
 *
 * <h2>Why {@link #inTransaction} rolls back on <em>any</em> {@link Throwable}</h2>
 *
 * <p>JDBC's contract is unforgiving here: a connection with an open transaction that has its
 * auto-commit re-enabled without an explicit {@code rollback()} first gets that transaction
 * <em>committed</em>, not discarded — whatever partial writes happened before the failure become
 * permanent. The bug this primitive exists to make structurally impossible is
 * {@code catch (SQLException e) { rollback } finally { setAutoCommit(true) }}: it looks complete,
 * but an unchecked failure — a {@code RuntimeException} from a validation check, an
 * {@code AssertionError} from an invariant, an {@code OutOfMemoryError} — skips the {@code catch}
 * entirely, falls straight to {@code finally}, and silently commits whatever writes happened
 * before the failure. That exact shape has shipped as a real defect more than once in this
 * codebase (see the plan this class implements). {@link #inTransaction} catches
 * {@code SQLException}, {@code RuntimeException}, <em>and</em> {@code Error} in one branch, rolls
 * back unconditionally, and only then restores auto-commit — so the restore can never run before
 * the transaction has been resolved one way or the other.</p>
 *
 * @since 1.0
 */
public final class Jdbc {

    private static final Logger LOG = LogManager.getLogger( Jdbc.class );

    private final DataSource dataSource;

    public Jdbc( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    /** The {@link DataSource} this instance was built with, for the rare caller that must hand one onward. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Runs {@code sql}, binds params via {@code binder}, and maps every row with {@code mapper}. */
    public < T > List< T > query( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return query( conn, sql, binder, mapper );
        }
    }

    /** {@link #query(String, SqlBinder, RowMapper)} on an already-open connection (e.g. inside a transaction). */
    public < T > List< T > query( final Connection conn, final String sql, final SqlBinder binder,
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
    public < T > Optional< T > queryOne( final String sql, final SqlBinder binder, final RowMapper< T > mapper )
            throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return queryOne( conn, sql, binder, mapper );
        }
    }

    /** {@link #queryOne(String, SqlBinder, RowMapper)} on an already-open connection. */
    public < T > Optional< T > queryOne( final Connection conn, final String sql, final SqlBinder binder,
                                          final RowMapper< T > mapper ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            try ( ResultSet rs = ps.executeQuery() ) {
                return rs.next() ? Optional.of( mapper.map( rs ) ) : Optional.empty();
            }
        }
    }

    /** Runs an INSERT/UPDATE/DELETE, returning the affected-row count. */
    public int update( final String sql, final SqlBinder binder ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return update( conn, sql, binder );
        }
    }

    /** {@link #update(String, SqlBinder)} on an already-open connection. */
    public int update( final Connection conn, final String sql, final SqlBinder binder ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            binder.bind( ps );
            return ps.executeUpdate();
        }
    }

    /**
     * Runs an INSERT with {@link Statement#RETURN_GENERATED_KEYS} and maps the first generated-key
     * row with {@code keyMapper} (e.g. {@code rs -> rs.getLong(1)}). Empty when the driver returns
     * no key. Use this instead of rewriting the SQL to {@code RETURNING} — the SQL stays verbatim.
     */
    public < K > Optional< K > insertReturningKey( final String sql, final SqlBinder binder,
                                                    final RowMapper< K > keyMapper ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            return insertReturningKey( conn, sql, binder, keyMapper );
        }
    }

    /** {@link #insertReturningKey(String, SqlBinder, RowMapper)} on an already-open connection. */
    public < K > Optional< K > insertReturningKey( final Connection conn, final String sql, final SqlBinder binder,
                                                    final RowMapper< K > keyMapper ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql, Statement.RETURN_GENERATED_KEYS ) ) {
            binder.bind( ps );
            ps.executeUpdate();
            try ( ResultSet keys = ps.getGeneratedKeys() ) {
                return keys.next() ? Optional.of( keyMapper.map( keys ) ) : Optional.empty();
            }
        }
    }

    /**
     * Runs one {@code sql} statement as a JDBC batch, one {@link SqlBinder} per row, on an
     * already-open connection — for the {@code addBatch}/{@code executeBatch} bulk-write call
     * sites. Always run inside {@link #inTransaction}: a batch is not itself a transaction.
     */
    public int[] batch( final Connection conn, final String sql, final List< SqlBinder > binders )
            throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            for ( final SqlBinder binder : binders ) {
                binder.bind( ps );
                ps.addBatch();
            }
            return ps.executeBatch();
        }
    }

    /**
     * Streams {@code sql}'s result set to {@code consumer} one row at a time instead of
     * collecting it into a {@link List}, for backfills/indexers over tables too large to hold in
     * memory at once. {@code fetchSize} is passed straight to {@link Statement#setFetchSize(int)}
     * — the JDBC driver decides how literally it honours it.
     */
    public void forEachRow( final String sql, final SqlBinder binder, final int fetchSize,
                             final RowConsumer consumer ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            forEachRow( conn, sql, binder, fetchSize, consumer );
        }
    }

    /** {@link #forEachRow(String, SqlBinder, int, RowConsumer)} on an already-open connection. */
    public void forEachRow( final Connection conn, final String sql, final SqlBinder binder, final int fetchSize,
                             final RowConsumer consumer ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setFetchSize( fetchSize );
            binder.bind( ps );
            try ( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) consumer.accept( rs );
            }
        }
    }

    /** Runs a no-bind statement — DDL, {@code TRUNCATE}, audit-partition creation. */
    public void execute( final String sql ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            execute( conn, sql );
        }
    }

    /** {@link #execute(String)} on an already-open connection. */
    public void execute( final Connection conn, final String sql ) throws SQLException {
        try ( Statement st = conn.createStatement() ) {
            st.execute( sql );
        }
    }

    /**
     * Cheap connectivity probe ({@link Connection#isValid}). Never throws — a failure to even
     * obtain a connection is exactly the "not reachable" answer this method exists to give, so it
     * is logged and folded into a {@code false} return rather than propagated.
     */
    public boolean ping() {
        try ( Connection conn = dataSource.getConnection() ) {
            return conn.isValid( 5 );
        } catch ( final SQLException e ) {
            LOG.warn( "ping: failed to obtain/validate a connection: {}", e.getMessage(), e );
            return false;
        }
    }

    /**
     * Runs {@code body} inside a single transaction: auto-commit off, commit on normal return,
     * rollback on <em>any</em> failure — checked ({@link SQLException}), unchecked
     * ({@link RuntimeException}), or {@link Error} — and the connection's previous auto-commit
     * state is restored only in {@code finally}, after commit or rollback has already run, so the
     * restore itself can never be what commits an open transaction. See the class Javadoc for why
     * the {@link Error} branch matters. Both branches rethrow the exact {@link Throwable} caught,
     * unwrapped — this primitive never decides the exception contract, only the rollback.
     */
    public < T > T inTransaction( final TransactionBody< T > body ) throws SQLException {
        try ( Connection conn = dataSource.getConnection() ) {
            final boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit( false );
            try {
                final T result = body.run( conn );
                conn.commit();
                return result;
            } catch ( final SQLException | RuntimeException | Error e ) {
                Transactions.rollbackQuietly( conn, e, LOG, "inTransaction" );
                throw e;
            } finally {
                conn.setAutoCommit( prevAutoCommit );
            }
        }
    }
}
