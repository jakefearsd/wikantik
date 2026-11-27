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

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's default product: persistence, its one settings event, and clearing when the product is archived. */
@RequiresPostgres
class WorkProjectDefaultProductPostgresTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final Actor ADMIN = Actor.human( "root", Set.of(), true );

    private DataSource ds;
    private WorkService svc;
    private JdbcWorkStore store;
    private Project plat;
    private Product web;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
        web = store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.empty(); }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        svc.putMember( ADMIN, "PLAT", "lena", "user", WorkRole.LEAD );
        svc.putMember( ADMIN, "PLAT", "mia", "user", WorkRole.MEMBER );
    }

    private Product api() throws SQLException {
        return store.createProduct( new Product( 0, "api", "Api", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
    }

    private Item file( final WorkLevel level, final String productKey ) throws SQLException {
        return svc.createItem( LENA, new ItemDraft( "PLAT", level, null, productKey, WorkKind.SOFTWARE, "t", "",
            null, null, null, null, null, null ) ).item();
    }

    @Test
    void leadSetsDefaultMemberIsDenied() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        assertEquals( web.id(), reload( plat ).defaultProductId() );
        assertThrows( WorkDeniedException.class,
            () -> svc.features().setDefaultProduct( Actor.human( "mia", Set.of(), false ), "PLAT", "api" ) );
        assertThrows( WorkNotFoundException.class,
            () -> svc.features().setDefaultProduct( Actor.human( "zed", Set.of(), false ), "PLAT", "web" ) );
        svc.features().setDefaultProduct( LENA, "PLAT", " " );
        assertNull( reload( plat ).defaultProductId() );
    }

    @Test
    void unknownOrArchivedProductIsRefusedNamingDefaultProductKey() throws SQLException {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> svc.features().setDefaultProduct( LENA, "PLAT", "nope" ) );
        assertEquals( "defaultProductKey", e.field() );
        assertEquals( "No such product: nope", e.getMessage() );
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        final WorkValidationException e2 = assertThrows( WorkValidationException.class,
            () -> svc.features().setDefaultProduct( LENA, "PLAT", "web" ) );
        assertEquals( "defaultProductKey", e2.field() );
    }

    @Test
    void archivedProjectIsRefusedAsArchived() throws SQLException {
        svc.archiveProject( ADMIN, "PLAT" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> svc.features().setDefaultProduct( LENA, "PLAT", "web" ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
    }

    @Test
    void storyWithoutProductKeyInheritsTheDefault() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        assertEquals( web.id(), file( WorkLevel.STORY, null ).productId() );
    }

    @Test
    void blankProductKeyMeansNoProduct() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        assertNull( file( WorkLevel.STORY, "" ).productId() );
    }

    @Test
    void explicitProductKeyWins() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        final Product api = api();
        assertEquals( api.id(), file( WorkLevel.TASK, "api" ).productId() );
    }

    @Test
    void epicNeverInherits() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        assertNull( file( WorkLevel.EPIC, null ).productId() );
    }

    @Test
    void archivedDefaultIsNotInherited() throws SQLException {
        svc.features().setDefaultProduct( LENA, "PLAT", "web" );
        try ( var c = ds.getConnection(); var st = c.prepareStatement( "UPDATE work_product SET archived_at = now() WHERE id = ?" ) ) {
            st.setLong( 1, web.id() );
            st.executeUpdate();
        }
        assertNull( file( WorkLevel.STORY, null ).productId() );
    }

    @Test
    void staleProductObjectCannotBecomeTheDefaultAfterArchive() throws SQLException {
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> store.setDefaultProduct( plat.id(), web, LENA ) );
        assertEquals( "defaultProductKey", e.field() );
        assertEquals( "No such product: web", e.getMessage() );
        assertNull( reload( plat ).defaultProductId() );
    }

    @Test
    void archivingAnAlreadyArchivedProductWritesNoDefaultEvents() throws SQLException {
        store.setDefaultProduct( plat.id(), web, LENA );
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        final int before = defaultEvents( plat ).size();
        store.archiveProduct( web, java.time.Instant.now(), ADMIN );
        assertEquals( before, defaultEvents( plat ).size() );
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
        assertNotNull( store.product( web.id() ).orElseThrow().archivedAt() );
        assertTrue( store.product( web.id() + 1000 ).isEmpty() );
    }
}
