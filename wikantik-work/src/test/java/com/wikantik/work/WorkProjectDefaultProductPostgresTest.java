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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's default product: persistence, its one settings event, and clearing when the product is archived. */
@RequiresPostgres
class WorkProjectDefaultProductPostgresTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final Actor ADMIN = Actor.human( "root", Set.of(), true );

    private JdbcWorkStore store;
    private Project plat;
    private Product web;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        plat = store.createProject( "PLAT", "Platform", "" );
        web = store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
    }

    private Project reload( final Project p ) throws SQLException {
        return store.project( p.key() ).orElseThrow();
    }

    private List< WorkEvent > defaultEvents( final Project p ) throws SQLException {
        return store.projectEvents( p.id(), null, null, null, Set.of( WorkEvent.SETTINGS ), false, 50 ).stream()
            .filter( e -> "default_product".equals( e.note() ) ).toList();
    }

    @Test
    void setDefaultProductPersistsAndWritesOneSettingsEventWithKeys() throws SQLException {
        store.setDefaultProduct( plat.id(), web, LENA );
        assertEquals( web.id(), reload( plat ).defaultProductId() );
        final List< WorkEvent > events = defaultEvents( plat );
        assertEquals( 1, events.size() );
        assertNull( events.get( 0 ).fromValue() );
        assertEquals( "web", events.get( 0 ).toValue() );
    }

    @Test
    void settingTheSameDefaultAgainWritesNoEvent() throws SQLException {
        store.setDefaultProduct( plat.id(), web, LENA );
        store.setDefaultProduct( plat.id(), web, LENA );
        assertEquals( 1, defaultEvents( plat ).size() );
    }

    @Test
    void clearingWritesFromKeyToNull() throws SQLException {
        store.setDefaultProduct( plat.id(), web, LENA );
        store.setDefaultProduct( plat.id(), null, LENA );
        assertNull( reload( plat ).defaultProductId() );
        final List< WorkEvent > events = defaultEvents( plat );
        assertEquals( 2, events.size() );
        assertTrue( events.stream().anyMatch( e -> "web".equals( e.fromValue() ) && e.toValue() == null ) );
    }

    @Test
    void archivingAProductClearsItAsEveryProjectsDefault() throws SQLException {
        final Project ops = store.createProject( "OPS", "Ops", "" );
        store.setDefaultProduct( plat.id(), web, LENA );
        store.setDefaultProduct( ops.id(), web, LENA );
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        for ( final Project p : List.of( plat, ops ) ) {
            assertNull( reload( p ).defaultProductId() );
            final WorkEvent cleared = defaultEvents( p ).stream().filter( e -> e.toValue() == null ).findFirst()
                .orElseThrow();
            assertEquals( "web", cleared.fromValue() );
            assertEquals( "root", cleared.actor() );
        }
    }

    @Test
    void productByIdFindsArchivedProductsToo() throws SQLException {
        assertEquals( "web", store.product( web.id() ).orElseThrow().key() );
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        assertTrue( store.product( web.id() ).orElseThrow().archivedAt() != null );
        assertTrue( store.product( web.id() + 1000 ).isEmpty() );
    }
}
