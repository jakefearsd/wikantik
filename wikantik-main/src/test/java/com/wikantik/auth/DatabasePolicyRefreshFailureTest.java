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
package com.wikantik.auth;

import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed grant reload must not wipe the grants already in force. */
@RequiresPostgres
class DatabasePolicyRefreshFailureTest {

    private static final PagePermission VIEW = new PagePermission( "*:*", "view" );

    private static DataSource failable( final DataSource delegate, final AtomicBoolean down ) {
        return ( DataSource ) Proxy.newProxyInstance( DatabasePolicyRefreshFailureTest.class.getClassLoader(),
            new Class< ? >[] { DataSource.class }, ( proxy, m, args ) -> {
                if ( down.get() && "getConnection".equals( m.getName() ) ) {
                    throw new SQLException( "simulated database outage" );
                }
                try {
                    return m.invoke( delegate, args );
                } catch ( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
    }

    @Test
    void failedRefreshKeepsThePreviousGrants() throws Exception {
        final DataSource real = PostgresTestDb.createDataSource();
        try ( Connection c = real.getConnection(); Statement st = c.createStatement() ) {
            st.executeUpdate( "DELETE FROM policy_grants" );
            st.executeUpdate( "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('role', 'All', 'page', '*', 'view')" );
        }
        final AtomicBoolean down = new AtomicBoolean();
        final DatabasePolicy policy = new DatabasePolicy( failable( real, down ), "policy_grants" );
        assertTrue( policy.implies( Role.ALL, VIEW ), "grant loaded at construction" );

        down.set( true );
        policy.refresh();

        assertTrue( policy.implies( Role.ALL, VIEW ), "a failed refresh must not drop the grants already in force" );
    }

    @Test
    void failedInitialLoadIsFailClosed() {
        final AtomicBoolean down = new AtomicBoolean( true );
        final DatabasePolicy policy = new DatabasePolicy( failable( PostgresTestDb.createDataSource(), down ), "policy_grants" );
        assertFalse( policy.implies( Role.ALL, VIEW ), "with no snapshot at all, nothing is granted" );
    }
}
