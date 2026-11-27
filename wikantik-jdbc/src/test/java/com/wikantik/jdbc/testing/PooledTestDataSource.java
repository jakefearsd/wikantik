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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test-only connection pool in front of the shared Testcontainer. Opening a PostgreSQL connection costs a TCP
 * handshake, a backend fork and SCRAM authentication (about 6 ms); a statement on an open connection costs about
 * 0.1 ms. Fixture-heavy tests open dozens of connections each, so without a pool most of a database test's time was
 * spent connecting.
 *
 * <p>A returned connection is made indistinguishable from a fresh one before it is lent again: an open transaction
 * is rolled back, auto-commit and read-only are restored, and {@code DISCARD ALL} drops temporary tables, session
 * settings, prepared statements, advisory locks and any {@code SET ROLE}. A connection that fails any of that is
 * closed for real instead of pooled. Callers see a proxy whose {@code close()} is idempotent and after which every
 * other call fails, as on a real connection.
 */
public final class PooledTestDataSource implements DataSource {

    private static final Logger LOG = LogManager.getLogger( PooledTestDataSource.class );
    private static final int MAX_IDLE = 16;

    private final DataSource physical;
    private final ConcurrentLinkedDeque< Connection > idle = new ConcurrentLinkedDeque<>();
    private final AtomicInteger opened = new AtomicInteger();

    public PooledTestDataSource( final DataSource physical ) {
        this.physical = physical;
    }

    /** Physical connections opened so far (for tests of the pool itself). */
    public int physicalConnectionsOpened() {
        return opened.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real;
        while ( ( real = idle.pollFirst() ) != null ) {
            if ( !real.isClosed() ) {
                return lend( real );
            }
        }
        opened.incrementAndGet();
        return lend( physical.getConnection() );
    }

    @Override
    public Connection getConnection( final String username, final String password ) throws SQLException {
        // A different login is not pooled: hand out a real connection.
        return physical.getConnection( username, password );
    }

    private Connection lend( final Connection real ) {
        return ( Connection ) Proxy.newProxyInstance( Connection.class.getClassLoader(),
            new Class< ? >[] { Connection.class }, new Lent( real ) );
    }

    private void giveBack( final Connection real ) {
        try {
            if ( !real.getAutoCommit() ) {
                real.rollback();
                real.setAutoCommit( true );
            }
            if ( real.isReadOnly() ) {
                real.setReadOnly( false );
            }
            try ( Statement st = real.createStatement() ) {
                st.execute( "DISCARD ALL" );
            }
            if ( idle.size() < MAX_IDLE ) {
                idle.offerFirst( real );
                return;
            }
        } catch ( final SQLException e ) {
            LOG.warn( "Test pool: discarding a connection that could not be reset: {}", e.getMessage(), e );
        }
        closeQuietly( real );
    }

    private static void closeQuietly( final Connection real ) {
        try {
            real.close();
        } catch ( final SQLException e ) {
            LOG.warn( "Test pool: closing a discarded connection failed: {}", e.getMessage(), e );
        }
    }

    private final class Lent implements InvocationHandler {
        private final Connection real;
        private boolean closed;

        Lent( final Connection real ) {
            this.real = real;
        }

        @Override
        public Object invoke( final Object proxy, final Method method, final Object[] args ) throws Throwable {
            switch ( method.getName() ) {
                case "close":
                    if ( !closed ) {
                        closed = true;
                        giveBack( real );
                    }
                    return null;
                case "isClosed":
                    return closed || real.isClosed();
                case "equals":
                    return proxy == args[ 0 ];
                case "hashCode":
                    return System.identityHashCode( proxy );
                case "toString":
                    return "PooledTestConnection[" + real + ( closed ? ", closed]" : "]" );
                default:
                    break;
            }
            if ( closed ) {
                throw new SQLException( "This connection has been closed." );
            }
            try {
                return method.invoke( real, args );
            } catch ( final InvocationTargetException e ) {
                throw e.getCause();
            }
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return physical.getLogWriter();
    }

    @Override
    public void setLogWriter( final PrintWriter out ) throws SQLException {
        physical.setLogWriter( out );
    }

    @Override
    public void setLoginTimeout( final int seconds ) throws SQLException {
        physical.setLoginTimeout( seconds );
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return physical.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return physical.getParentLogger();
    }

    @Override
    public < T > T unwrap( final Class< T > iface ) throws SQLException {
        if ( iface.isInstance( this ) ) {
            return iface.cast( this );
        }
        return physical.unwrap( iface );
    }

    @Override
    public boolean isWrapperFor( final Class< ? > iface ) throws SQLException {
        return iface.isInstance( this ) || physical.isWrapperFor( iface );
    }
}
