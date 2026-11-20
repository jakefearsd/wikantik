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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changes to a product's settings are recorded, one row per changed field, and only a wiki admin reads them. */
@RequiresPostgres
class WorkProductHistoryPostgresTest {

    private GateFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
    }

    private Product current() throws SQLException {
        return f.svc.listProducts( f.admin ).get( 0 );
    }

    private void change( final Autonomy ceiling, final DeployGate gate, final String name ) throws SQLException {
        final Product p = current();
        f.svc.updateProduct( f.admin, new Product( p.id(), p.key(), name, p.repoUrl(), p.defaultBranch(),
            p.wikiCluster(), ceiling, gate, p.archivedAt() ) );
    }

    @Test
    void anUpdateRecordsOneRowPerChangedField() throws SQLException {
        change( Autonomy.SUPERVISED, DeployGate.AGENT, "Wikantik" );
        final List< ProductEvent > events = f.svc.productHistory( f.admin, "wikantik" );
        assertEquals( 2, events.size() );
        final ProductEvent gate = events.stream().filter( e -> e.field().equals( "deploy_gate" ) ).findFirst().orElseThrow();
        assertEquals( "human", gate.fromValue() );
        assertEquals( "agent", gate.toValue() );
        final ProductEvent ceiling = events.stream().filter( e -> e.field().equals( "autonomy_ceiling" ) ).findFirst().orElseThrow();
        assertEquals( "autonomous", ceiling.fromValue() );
        assertEquals( "supervised", ceiling.toValue() );
        for ( final ProductEvent e : events ) {
            assertEquals( "admin", e.actor() );
            assertEquals( Actor.Kind.HUMAN, e.actorKind() );
            assertNull( e.agentId() );
        }
    }

    @Test
    void anUpdateThatChangesNothingRecordsNothing() throws SQLException {
        change( Autonomy.AUTONOMOUS, DeployGate.HUMAN, "Wikantik" );
        assertTrue( f.svc.productHistory( f.admin, "wikantik" ).isEmpty() );
    }

    @Test
    void archivingRecordsArchived() throws SQLException {
        final Instant before = Instant.now().minusSeconds( 5 );
        f.svc.archiveProduct( f.admin, "wikantik" );
        final List< ProductEvent > events = f.svc.productHistory( f.admin, "wikantik" );
        assertEquals( 1, events.size() );
        assertEquals( "archived", events.get( 0 ).field() );
        assertNull( events.get( 0 ).fromValue() );
        assertTrue( Instant.parse( events.get( 0 ).toValue() ).isAfter( before ) );
    }

    @Test
    void historyIsNewestFirst() throws SQLException {
        change( Autonomy.AUTONOMOUS, DeployGate.HUMAN, "One" );
        change( Autonomy.AUTONOMOUS, DeployGate.HUMAN, "Two" );
        change( Autonomy.AUTONOMOUS, DeployGate.HUMAN, "Three" );
        assertEquals( List.of( "Three", "Two", "One" ),
            f.svc.productHistory( f.admin, "wikantik" ).stream().map( ProductEvent::toValue ).toList() );
    }

    @Test
    void onlyAnAdminReadsProductHistory() {
        final WorkDeniedException denied = assertThrows( WorkDeniedException.class,
            () -> f.svc.productHistory( f.mia, "wikantik" ) );
        final WorkDeniedException create = assertThrows( WorkDeniedException.class,
            () -> f.svc.createProduct( f.mia, new Product( 0, "zz", "Z", null, null, null, null, null, null ) ) );
        assertEquals( create.getMessage(), denied.getMessage() );
    }

    @Test
    void anUnknownProductHasNoHistory() {
        assertThrows( WorkNotFoundException.class, () -> f.svc.productHistory( f.admin, "ghost" ) );
    }

    @Test
    void historyNeverKeepsARepositoryUrlsCredentials() throws SQLException {
        WorkAgentFixtures.exec( f.ds, "UPDATE work_product SET repo_url = 'https://bot:s3cret@git.example.com/org/x.git'" );
        final Product p = current();
        f.svc.updateProduct( f.admin, new Product( p.id(), p.key(), p.name(), "git@git.example.com:org/x.git",
            p.defaultBranch(), p.wikiCluster(), p.autonomyCeiling(), p.deployGate(), p.archivedAt() ) );
        final ProductEvent e = f.svc.productHistory( f.admin, "wikantik" ).get( 0 );
        assertEquals( "repo_url", e.field() );
        assertEquals( "https://***@git.example.com/org/x.git", e.fromValue() );
        assertEquals( "git@git.example.com:org/x.git", e.toValue(), "scp-style is not userinfo" );
    }

    @Test
    void maskingHidesUserinfoOnlyInUrls() {
        assertEquals( "ssh://***@host/o/x.git", JdbcWorkCatalog.maskUserinfo( "ssh://git@host/o/x.git" ) );
        assertEquals( "https://***@host/x", JdbcWorkCatalog.maskUserinfo( "https://u:p@host/x" ) );
        assertEquals( "https://host/x", JdbcWorkCatalog.maskUserinfo( "https://host/x" ) );
        assertNull( JdbcWorkCatalog.maskUserinfo( null ) );
    }

    @Test
    void archivingAnArchivedProductChangesNothing() throws SQLException {
        f.svc.archiveProduct( f.admin, "wikantik" );
        final Instant first = f.svc.listProducts( f.admin ).stream().filter( x -> x.key().equals( "wikantik" ) )
            .findFirst().orElseThrow().archivedAt();
        f.svc.archiveProduct( f.admin, "wikantik" );
        assertEquals( 1, f.svc.productHistory( f.admin, "wikantik" ).size() );
        assertEquals( first, f.svc.listProducts( f.admin ).stream().filter( x -> x.key().equals( "wikantik" ) )
            .findFirst().orElseThrow().archivedAt() );
    }

    @Test
    void concurrentEditsRecordFromValuesThatChain() throws SQLException {
        final Product stale = current();
        final JdbcWorkStore store = new JdbcWorkStore( f.ds );
        store.updateProduct( stale, new Product( stale.id(), stale.key(), "One", stale.repoUrl(), stale.defaultBranch(),
            stale.wikiCluster(), stale.autonomyCeiling(), stale.deployGate(), null ), f.admin );
        store.updateProduct( stale, new Product( stale.id(), stale.key(), "Two", stale.repoUrl(), stale.defaultBranch(),
            stale.wikiCluster(), stale.autonomyCeiling(), stale.deployGate(), null ), f.admin );
        final List< ProductEvent > events = f.svc.productHistory( f.admin, "wikantik" );
        assertEquals( "Two", events.get( 0 ).toValue() );
        assertEquals( "One", events.get( 0 ).fromValue(), "diffed against the row as it is, not the caller's copy" );
    }
}
