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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A data source that counts the statements prepared on the connections it hands out and, when built with a marker,
 * parks the first statement whose SQL contains it, before it runs, until {@link #open()}.
 */
final class StatementProbe implements DataSource {

    private final DataSource delegate;
    private final AtomicInteger statements = new AtomicInteger();
    private final String parkOn;
    private final AtomicBoolean armed = new AtomicBoolean( true );
    final CountDownLatch reached = new CountDownLatch( 1 );
    private final CountDownLatch open = new CountDownLatch( 1 );

    StatementProbe( final DataSource delegate ) {
        this( delegate, null );
    }

    StatementProbe( final DataSource delegate, final String parkOn ) {
        this.delegate = delegate;
        this.parkOn = parkOn;
    }

    void open() {
        open.countDown();
    }

    int statements() {
        return statements.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        final Connection c = delegate.getConnection();
        return ( Connection ) Proxy.newProxyInstance( Connection.class.getClassLoader(),
            new Class< ? >[] { Connection.class }, ( proxy, method, args ) -> {
                if ( "prepareStatement".equals( method.getName() ) || "createStatement".equals( method.getName() ) ) {
                    statements.incrementAndGet();
                    final Object sql = args != null && args.length > 0 ? args[ 0 ] : null;
                    if ( parkOn != null && sql instanceof String text && text.contains( parkOn )
                            && armed.compareAndSet( true, false ) ) {
                        reached.countDown();
                        if ( !open.await( 30, TimeUnit.SECONDS ) ) {
                            throw new SQLException( "probe never opened" );
                        }
                    }
                }
                try {
                    return method.invoke( c, args );
                } catch ( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
    }

    @Override
    public Connection getConnection( final String user, final String password ) throws SQLException {
        return getConnection();
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
    public Logger getParentLogger() {
        return Logger.getLogger( StatementProbe.class.getName() );
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
