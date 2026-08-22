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
package com.wikantik.search;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Test-only {@link DataSource} wrapper for the T1.6 rollback regression tests
 * (search/embedding, search/hybrid). Two things {@code
 * com.wikantik.jdbc.testing.FaultInjectingDataSource} cannot do:
 *
 * <ul>
 *   <li>Inject an arbitrary {@link Throwable}, including an {@link Error} — the
 *       defect {@code Jdbc.inTransaction} exists to fix ({@code AssertionError},
 *       {@code OutOfMemoryError}, …) is exactly the branch a bare {@code catch
 *       (SQLException)} or {@code catch (RuntimeException)} cannot see, and
 *       {@code FaultInjectingDataSource.failOn} only accepts a
 *       {@code RuntimeException}.</li>
 *   <li>Count {@code rollback()} calls directly. A raw (non-pooled) PostgreSQL
 *       connection aborts any open transaction the instant it is closed,
 *       regardless of whether the Java code called {@code rollback()} first —
 *       so "assert no partial rows" cannot distinguish code that rolls back
 *       explicitly from code that merely lets {@code try}-with-resources close
 *       the connection. Production connections ARE pooled (DBCP2 via Tomcat
 *       JNDI), where {@code close()} without an explicit {@code rollback()} on
 *       an open transaction is exactly the pool-defined-behaviour risk {@code
 *       Jdbc.inTransaction}'s Javadoc describes. Counting the calls pins the
 *       code's actual behaviour down instead of inferring it from row
 *       visibility, which this harness cannot observe either way.</li>
 * </ul>
 *
 * <p>Everything else — every other {@link Connection} method, and the SQL that
 * runs before the armed statement — delegates unchanged to a real, live
 * connection (typically from {@code PostgresTestDb}), so the rest of the
 * method under test still executes for real.</p>
 */
public final class RollbackTrackingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statementCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private volatile int failOnNth = -1;
    private volatile Throwable toThrow;

    public RollbackTrackingDataSource( final DataSource delegate ) {
        this.delegate = delegate;
    }

    /**
     * Arms the fault: the {@code nthStatement}-th {@code prepareStatement}/{@code createStatement}
     * call — counted across every {@link Connection} this instance hands out — throws {@code
     * toThrow}. {@code toThrow} may be an {@link Error}, unlike {@code FaultInjectingDataSource}.
     */
    public void failOn( final int nthStatement, final Throwable toThrow ) {
        this.failOnNth = nthStatement;
        this.toThrow = toThrow;
    }

    /** Number of {@code rollback()} calls observed across every connection this instance handed out. */
    public int rollbackCount() {
        return rollbackCount.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap( delegate.getConnection() );
    }

    @Override
    public Connection getConnection( final String username, final String password ) throws SQLException {
        return wrap( delegate.getConnection( username, password ) );
    }

    private Connection wrap( final Connection real ) {
        return ( Connection ) Proxy.newProxyInstance(
            RollbackTrackingDataSource.class.getClassLoader(),
            new Class< ? >[] { Connection.class },
            ( proxy, method, args ) -> {
                final String name = method.getName();
                if ( "prepareStatement".equals( name ) || "createStatement".equals( name ) ) {
                    final int n = statementCount.incrementAndGet();
                    if ( n == failOnNth ) {
                        throwUnchecked( toThrow );
                    }
                }
                if ( "rollback".equals( name ) && ( args == null || args.length == 0 ) ) {
                    rollbackCount.incrementAndGet();
                }
                try {
                    return method.invoke( real, args );
                } catch ( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
    }

    private static void throwUnchecked( final Throwable t ) {
        if ( t instanceof RuntimeException re ) throw re;
        if ( t instanceof Error err ) throw err;
        throw new RuntimeException( t );
    }

    // ---- boilerplate DataSource delegation ----

    @Override
    public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }

    @Override
    public void setLogWriter( final PrintWriter out ) throws SQLException { delegate.setLogWriter( out ); }

    @Override
    public void setLoginTimeout( final int seconds ) throws SQLException { delegate.setLoginTimeout( seconds ); }

    @Override
    public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }

    @Override
    public < T > T unwrap( final Class< T > iface ) throws SQLException { return delegate.unwrap( iface ); }

    @Override
    public boolean isWrapperFor( final Class< ? > iface ) throws SQLException { return delegate.isWrapperFor( iface ); }
}
