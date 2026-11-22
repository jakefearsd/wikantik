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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Installing or disabling a hook is one transaction with its product event: a fault leaves no half-write. */
@RequiresPostgres
class JdbcWorkHooksRollbackTest {

    private static final Instant AT = Instant.parse( "2026-03-01T12:00:00Z" );

    private CodeHostFixture f;
    private FaultInjectingDataSource faulty;
    private Product product;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        faulty = new FaultInjectingDataSource( f.ds );
        product = f.product;
        f.exec( "DELETE FROM work_product_event WHERE field = 'hook'" );
        f.exec( "DELETE FROM work_product_hook" );
    }

    @Test
    void aFaultOnTheProductEventLeavesNoHook() throws SQLException {
        faulty.reset();
        faulty.failOn( 3, new RuntimeException( "boom" ) );
        final WorkCodeHostStore store = new JdbcWorkStore( faulty ).notifyStores().codeHost();
        assertThrows( RuntimeException.class, () -> store.putHook( product, "s".repeat( 64 ), AT, f.admin ) );
        assertEquals( 0, count( "work_product_hook" ) );
        assertEquals( 0, count( "work_product_event WHERE field = 'hook'" ) );
    }

    @Test
    void everyFailurePointOfInstallingLeavesBothOrNeither() throws SQLException {
        final WorkCodeHostStore store = new JdbcWorkStore( faulty ).notifyStores().codeHost();
        int exercised = 0;
        for ( int n = 1; n <= 20; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                store.putHook( product, "s".repeat( 64 ), AT, f.admin );
            } catch ( final RuntimeException expected ) {
                exercised++;
                assertEquals( 0, count( "work_product_hook" ), "hook rows after failing at " + n );
                assertEquals( 0, count( "work_product_event WHERE field = 'hook'" ), "events after failing at " + n );
                continue;
            }
            assertEquals( 1, count( "work_product_hook" ) );
            assertEquals( 1, count( "work_product_event WHERE field = 'hook'" ) );
            assertEquals( true, exercised >= 2, "failure points exercised: " + exercised );
            return;
        }
        throw new AssertionError( "still failing after 20 statements" );
    }

    @Test
    void aFaultOnTheDisableEventLeavesTheHookLive() throws SQLException {
        new JdbcWorkStore( f.ds ).notifyStores().codeHost().putHook( product, "s".repeat( 64 ), AT, f.admin );
        final WorkCodeHostStore store = new JdbcWorkStore( faulty ).notifyStores().codeHost();
        for ( int n = 1; n <= 10; n++ ) {
            faulty.reset();
            faulty.failOn( n, new RuntimeException( "boom " + n ) );
            try {
                store.disableHook( product, AT, f.admin );
            } catch ( final RuntimeException expected ) {
                assertEquals( 1, count( "work_product_hook WHERE disabled_at IS NULL" ), "live after failing at " + n );
                assertEquals( 1, count( "work_product_event WHERE field = 'hook'" ), "events after failing at " + n );
                continue;
            }
            assertEquals( 0, count( "work_product_hook WHERE disabled_at IS NULL" ) );
            return;
        }
        throw new AssertionError( "still failing after 10 statements" );
    }

    private long count( final String from ) throws SQLException {
        return f.count( "SELECT count(*) FROM " + from );
    }
}
