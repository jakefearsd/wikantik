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
package com.wikantik.audit;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit append: chain-head lookup stays correct across partitions, and per-append catalog probes are bounded. */
@RequiresPostgres
class JdbcAuditRepositoryHeadTest {

    private final List< String > sql = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        PostgresTestDb.truncate( "audit_log" );
        sql.clear();
    }

    /** DataSource whose connections record every SQL string passed to prepareStatement. */
    private DataSource counting( final DataSource delegate ) {
        return ( DataSource ) Proxy.newProxyInstance( getClass().getClassLoader(), new Class< ? >[] { DataSource.class },
            ( proxy, m, args ) -> {
                final Object r = invoke( delegate, m, args );
                if ( !"getConnection".equals( m.getName() ) ) return r;
                final Connection real = ( Connection ) r;
                return Proxy.newProxyInstance( getClass().getClassLoader(), new Class< ? >[] { Connection.class },
                    ( p2, m2, a2 ) -> {
                        if ( "prepareStatement".equals( m2.getName() ) ) sql.add( ( String ) a2[ 0 ] );
                        return invoke( real, m2, a2 );
                    } );
            } );
    }

    private static Object invoke( final Object target, final java.lang.reflect.Method m, final Object[] args ) throws Throwable {
        try {
            return m.invoke( target, args );
        } catch ( final InvocationTargetException e ) {
            throw e.getCause();
        }
    }

    private static AuditEntry entry( final String type ) {
        return AuditEntry.builder().eventTime( Instant.now() ).category( AuditCategory.ADMIN )
                .eventType( type ).actorType( "TEST" ).outcome( AuditOutcome.SUCCESS ).build();
    }

    private static Clock at( final String iso ) {
        return Clock.fixed( Instant.parse( iso ), ZoneOffset.UTC );
    }

    @Test
    void partitionExistenceIsCheckedOncePerMonth() {
        final JdbcAuditRepository repo = new JdbcAuditRepository( counting( PostgresTestDb.createDataSource() ),
                at( "2027-03-10T12:00:00Z" ) );
        for ( int i = 0; i < 5; i++ ) {
            repo.append( List.of( entry( "e" + i ) ) );
        }
        assertEquals( 1, sql.stream().filter( s -> s.contains( "to_regclass" ) ).count(),
                "to_regclass must run once per month, not once per append" );
    }

    @Test
    void headIsFoundWhenOnlyAnOlderPartitionHasRows() {
        final DataSource ds = PostgresTestDb.createDataSource();
        new JdbcAuditRepository( ds, at( "2027-01-15T00:00:00Z" ) ).append( List.of( entry( "a" ), entry( "b" ) ) );
        final JdbcAuditRepository later = new JdbcAuditRepository( ds, at( "2027-03-15T00:00:00Z" ) );

        assertEquals( 2L, later.chainHead().lastSeq() );
        later.append( List.of( entry( "c" ) ) );

        assertEquals( 3L, later.chainHead().lastSeq() );
        assertTrue( later.verifyChain( 1, Long.MAX_VALUE ).isEmpty(), "chain must verify across the gap" );
    }

    @Test
    void chainVerifiesAcrossAMonthBoundary() {
        final DataSource ds = PostgresTestDb.createDataSource();
        new JdbcAuditRepository( ds, at( "2027-04-30T23:59:59Z" ) ).append( List.of( entry( "a" ) ) );
        final JdbcAuditRepository may = new JdbcAuditRepository( ds, at( "2027-05-01T00:00:01Z" ) );
        may.append( List.of( entry( "b" ), entry( "c" ) ) );
        may.append( List.of( entry( "d" ) ) );

        assertEquals( 4L, may.chainHead().lastSeq() );
        assertTrue( may.verifyChain( 1, Long.MAX_VALUE ).isEmpty() );
    }

    @Test
    void headQueryTouchesOnlyTheCurrentMonthPartition() throws Exception {
        try ( Connection c = PostgresTestDb.createDataSource().getConnection(); Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( "EXPLAIN " + JdbcAuditRepository.CHAIN_HEAD_PRUNED_SQL
                      .replaceFirst( "\\?", "'2027-03-01'" ).replaceFirst( "\\?", "'2027-04-01'" ) ) ) {
            final StringBuilder plan = new StringBuilder();
            while ( rs.next() ) plan.append( rs.getString( 1 ) ).append( '\n' );
            assertTrue( plan.toString().contains( "audit_log_2027_03" ), plan.toString() );
            assertFalse( plan.toString().contains( "audit_log_2027_04" ), plan.toString() );
            assertFalse( plan.toString().contains( "audit_log_2028" ), plan.toString() );
        }
    }
}
