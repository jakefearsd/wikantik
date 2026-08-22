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
package com.wikantik.jdbc.testing;

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
 * Wraps a {@link DataSource} and makes the n-th {@code prepareStatement}/{@code createStatement}
 * call — counted across every {@link Connection} this instance hands out — throw a chosen fault.
 * The standard harness for proving a transaction rolls back on an unchecked mid-transaction
 * failure: inject after the first write inside the transaction under test and assert no partial
 * rows are visible afterwards.
 *
 * <p>It also counts {@code commit()}/{@code rollback()} calls across every connection handed out
 * (see {@link #rollbacks()}/{@link #commits()}). This matters because a raw, unpooled JDBC
 * connection (as used against a Testcontainers Postgres instance) silently discards any open
 * transaction the instant it is closed, regardless of whether the code under test called
 * {@code rollback()} first — so "assert no partial rows are visible" cannot by itself distinguish
 * code that rolls back explicitly from code that merely lets try-with-resources close the
 * connection out from under an open transaction. Production connections are pooled (DBCP2 via
 * Tomcat JNDI), where closing a connection without an explicit {@code rollback()}/{@code commit()}
 * on an open transaction is exactly the pool-defined-behaviour risk hand-rolled transaction code
 * must avoid. Counting the calls pins down the code's actual behaviour instead of inferring it
 * from row visibility, which a raw connection cannot expose either way.
 *
 * <p>Everything else delegates unchanged to the wrapped connection via a {@link Proxy}, so this
 * is transparent to code that doesn't trip the fault.
 */
public final class FaultInjectingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statementCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private final AtomicInteger commitCount = new AtomicInteger();
    private volatile int failOnNth = -1;
    private volatile Throwable toThrow;

    public FaultInjectingDataSource( final DataSource delegate ) {
        this.delegate = delegate;
    }

    /**
     * Arms the fault: the {@code nthStatement}-th {@code prepareStatement}/{@code createStatement}
     * call across every connection this instance hands out from now on will throw {@code toThrow}
     * instead of returning a statement.
     */
    public void failOn( final int nthStatement, final RuntimeException toThrow ) {
        failOn( nthStatement, ( Throwable ) toThrow );
    }

    /**
     * Arms the fault with an arbitrary unchecked {@link Throwable} — a {@link RuntimeException}
     * or an {@link Error}. Injecting an {@code Error} (e.g. {@code AssertionError}, simulating an
     * invariant violation or OOM) is what proves a hand-rolled transaction's rollback path is
     * reached even by a failure that a narrow {@code catch (SQLException)} or
     * {@code catch (RuntimeException)} around it cannot see.
     *
     * @throws IllegalArgumentException if {@code toThrow} is a checked exception — this harness
     *     simulates unchecked failures only, since only those can escape a JDBC method signature
     *     undeclared.
     */
    public void failOn( final int nthStatement, final Throwable toThrow ) {
        if ( !( toThrow instanceof RuntimeException ) && !( toThrow instanceof Error ) ) {
            throw new IllegalArgumentException(
                "toThrow must be a RuntimeException or an Error (unchecked) — got "
              + toThrow.getClass().getName() );
        }
        this.failOnNth = nthStatement;
        this.toThrow = toThrow;
    }

    /** Number of {@code rollback()} calls observed across every connection this instance handed out. */
    public int rollbacks() {
        return rollbackCount.get();
    }

    /** Number of {@code commit()} calls observed across every connection this instance handed out. */
    public int commits() {
        return commitCount.get();
    }

    /** Resets the counters and disarms the fault, so the same instance can be reused across tests. */
    public void reset() {
        statementCount.set( 0 );
        rollbackCount.set( 0 );
        commitCount.set( 0 );
        failOnNth = -1;
        toThrow = null;
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
            FaultInjectingDataSource.class.getClassLoader(),
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
                if ( "commit".equals( name ) && ( args == null || args.length == 0 ) ) {
                    commitCount.incrementAndGet();
                }
                try {
                    return method.invoke( real, args );
                } catch ( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
    }

    private static void throwUnchecked( final Throwable t ) {
        if ( t instanceof RuntimeException re ) {
            throw re;
        }
        if ( t instanceof Error err ) {
            throw err;
        }
        // Unreachable: failOn(Throwable) rejects anything else at arm time.
        throw new IllegalStateException( "armed fault is neither a RuntimeException nor an Error", t );
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter( final PrintWriter out ) throws SQLException {
        delegate.setLogWriter( out );
    }

    @Override
    public void setLoginTimeout( final int seconds ) throws SQLException {
        delegate.setLoginTimeout( seconds );
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public < T > T unwrap( final Class< T > iface ) throws SQLException {
        return delegate.unwrap( iface );
    }

    @Override
    public boolean isWrapperFor( final Class< ? > iface ) throws SQLException {
        return delegate.isWrapperFor( iface );
    }
}
