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
 * call — counted across every {@link Connection} this instance hands out — throw a chosen
 * {@link RuntimeException}. The standard harness for proving a transaction rolls back on an
 * unchecked mid-transaction failure: inject after the first write inside the transaction under
 * test and assert no partial rows are visible afterwards.
 *
 * <p>Everything else delegates unchanged to the wrapped connection via a {@link Proxy}, so this
 * is transparent to code that doesn't trip the fault.
 */
public final class FaultInjectingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statementCount = new AtomicInteger();
    private volatile int failOnNth = -1;
    private volatile RuntimeException toThrow;

    public FaultInjectingDataSource( final DataSource delegate ) {
        this.delegate = delegate;
    }

    /**
     * Arms the fault: the {@code nthStatement}-th {@code prepareStatement}/{@code createStatement}
     * call across every connection this instance hands out from now on will throw {@code toThrow}
     * instead of returning a statement.
     */
    public void failOn( final int nthStatement, final RuntimeException toThrow ) {
        this.failOnNth = nthStatement;
        this.toThrow = toThrow;
    }

    /** Resets the count and disarms the fault, so the same instance can be reused across tests. */
    public void reset() {
        statementCount.set( 0 );
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
                        throw toThrow;
                    }
                }
                try {
                    return method.invoke( real, args );
                } catch ( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
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
