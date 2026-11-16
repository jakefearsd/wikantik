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
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * A data source whose first {@code commit()} parks until {@link #open()}: the transaction has run every statement
 * (and holds every lock) but is not yet visible, which lets a test start a competing transaction deterministically.
 * {@link #pid} is the backend of the parked transaction, for {@code pg_blocking_pids} checks.
 */
final class CommitGate implements DataSource {

    final CountDownLatch reached = new CountDownLatch( 1 );
    private final CountDownLatch open = new CountDownLatch( 1 );
    private final AtomicBoolean armed = new AtomicBoolean( true );
    private final DataSource delegate;
    volatile long pid;

    CommitGate( final DataSource delegate ) {
        this.delegate = delegate;
    }

    void open() {
        open.countDown();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap( delegate.getConnection() );
    }

    @Override
    public Connection getConnection( final String user, final String password ) throws SQLException {
        return wrap( delegate.getConnection( user, password ) );
    }

    private Connection wrap( final Connection c ) {
        return ( Connection ) Proxy.newProxyInstance( Connection.class.getClassLoader(),
            new Class< ? >[] { Connection.class }, ( proxy, method, args ) -> {
                if ( "commit".equals( method.getName() ) && armed.compareAndSet( true, false ) ) {
                    try ( Statement s = c.createStatement(); var rs = s.executeQuery( "SELECT pg_backend_pid()" ) ) {
                        rs.next();
                        pid = rs.getLong( 1 );
                    }
                    reached.countDown();
                    if ( !open.await( 30, TimeUnit.SECONDS ) ) {
                        throw new SQLException( "commit gate never opened" );
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
        return Logger.getLogger( CommitGate.class.getName() );
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
