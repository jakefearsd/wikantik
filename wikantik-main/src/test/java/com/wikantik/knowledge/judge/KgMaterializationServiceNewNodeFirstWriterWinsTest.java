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
package com.wikantik.knowledge.judge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-integrity guard: approving a {@code new-node} proposal is <b>first-writer-wins</b>. A node
 * that does not exist is created from the proposal; a node that already exists is left completely
 * unchanged.
 *
 * <p>The node path used to call {@code upsertNodeWithProvenance}, whose {@code ON CONFLICT ( name )
 * DO UPDATE} overwrote the existing row's {@code node_type}, {@code provenance}, {@code properties}
 * and {@code source_page} with the proposal's values. Approving a proposal for a name a curator had
 * already typed and annotated therefore silently destroyed that curation. The {@code new-edge} path
 * was fixed the same way in 9edc32c0be (see {@link KgMaterializationServiceExistingEndpointTest}).</p>
 */
@RequiresPostgres
class KgMaterializationServiceNewNodeFirstWriterWinsTest {

    private KgNodeRepository kgNodes;
    private KgProposalRepository kgProposals;
    private KgMaterializationService svc;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_edges", "kg_proposals", "kg_nodes" );
        final DataSource ds = PostgresTestDb.createDataSource();
        kgNodes     = new KgNodeRepository( ds );
        kgProposals = new KgProposalRepository( ds );
        svc = new KgMaterializationService( kgNodes, new KgEdgeRepository( ds ), kgProposals,
            new KgRejectionRepository( ds ) );
    }

    private KgProposal newNode( final String sourcePage, final String name, final String nodeType,
                                final Map< String, Object > properties ) {
        return kgProposals.insertProposal( "new-node", sourcePage,
            Map.< String, Object >of( "name", name, "nodeType", nodeType, "properties", properties ),
            0.8, "" );
    }

    /** Reads a node regardless of tier / inclusion policy so assertions cannot be hidden by a filter. */
    private KgNode read( final String name ) {
        return kgNodes.getNodeByName( name, true );
    }

    private KgNode seedHumanCuratedPostgres() {
        kgNodes.upsertNode( "PostgreSQL", "technology", "DatabasesPage", Provenance.HUMAN_AUTHORED,
            Map.< String, Object >of( "aliases", "pg", "curatedBy", "jake" ) );
        final KgNode seeded = read( "PostgreSQL" );
        assertEquals( "technology", seeded.nodeType(), "fixture sanity" );
        assertEquals( Provenance.HUMAN_AUTHORED, seeded.provenance(), "fixture sanity" );
        assertEquals( "human", seeded.tier(), "fixture sanity" );
        return seeded;
    }

    private static void assertUnchanged( final KgNode before, final KgNode after ) {
        assertNotNull( after, "existing node must still be there" );
        assertEquals( before.id(), after.id() );
        assertEquals( before.nodeType(), after.nodeType(), "node_type must be preserved" );
        assertEquals( before.provenance(), after.provenance(), "provenance must be preserved" );
        assertEquals( before.properties(), after.properties(), "properties must be preserved" );
        assertEquals( before.tier(), after.tier(), "tier must be preserved" );
        assertEquals( before.sourcePage(), after.sourcePage(), "source_page must be preserved" );
        assertEquals( before.provenanceProposalId(), after.provenanceProposalId(),
            "provenance_proposal_id must be preserved" );
        assertEquals( before.modified(), after.modified(), "the row must not even be touched" );
    }

    @Test
    void machine_approval_leaves_a_human_curated_node_unchanged() {
        final KgNode before = seedHumanCuratedPostgres();

        svc.materializeMachine( newNode( "SomeOtherPage", "PostgreSQL", "concept",
            Map.< String, Object >of( "aliases", "wrong", "extractedBy", "llm" ) ) );

        assertUnchanged( before, read( "PostgreSQL" ) );
    }

    @Test
    void human_approval_leaves_a_human_curated_node_unchanged() {
        final KgNode before = seedHumanCuratedPostgres();

        svc.promoteToHuman( newNode( "SomeOtherPage", "PostgreSQL", "product", Map.of() ) );

        assertUnchanged( before, read( "PostgreSQL" ) );
    }

    @Test
    void a_missing_node_is_created_from_the_proposal() {
        final KgProposal p = newNode( "ToolsPage", "Terraform", "technology",
            Map.< String, Object >of( "aliases", "tf" ) );

        svc.materializeMachine( p );

        final KgNode created = read( "Terraform" );
        assertNotNull( created, "the missing node must be created" );
        assertEquals( "technology", created.nodeType() );
        assertEquals( Provenance.AI_INFERRED, created.provenance() );
        assertEquals( Map.< String, Object >of( "aliases", "tf" ), created.properties() );
        assertEquals( "ToolsPage", created.sourcePage() );
        assertEquals( "machine", created.tier() );
        assertEquals( p.id(), created.provenanceProposalId() );
    }

    @Test
    void replaying_the_same_approval_twice_changes_nothing() {
        final KgProposal p = newNode( "ToolsPage", "Istio", "technology", Map.of() );

        svc.materializeMachine( p );
        final KgNode afterFirst = read( "Istio" );
        svc.materializeMachine( p );

        assertUnchanged( afterFirst, read( "Istio" ) );
        assertEquals( 1L, kgNodes.getAllNodes().stream().filter( n -> "Istio".equals( n.name() ) ).count() );
    }

    @Test
    void replaying_over_a_node_curated_after_the_first_materialisation_keeps_the_curation() {
        final KgProposal p = newNode( "ToolsPage", "Consul", "concept", Map.of() );
        svc.materializeMachine( p );
        // A curator re-types and annotates the machine-created node...
        kgNodes.upsertNode( "Consul", "technology", "ToolsPage", Provenance.HUMAN_CURATED,
            Map.< String, Object >of( "note", "curated" ) );
        final KgNode curated = read( "Consul" );

        // ...and a later replay of the same approved proposal must not undo that.
        svc.materializeMachine( p );

        assertUnchanged( curated, read( "Consul" ) );
    }

    @Test
    void approving_a_proposal_for_an_existing_node_is_logged_at_info_with_name_and_proposal_id() {
        seedHumanCuratedPostgres();
        final KgProposal p = newNode( "SomeOtherPage", "PostgreSQL", "concept", Map.of() );

        final List< LogEvent > captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final String appenderName = "NewNodeFirstWriterWins-" + Thread.currentThread().threadId();
        final AbstractAppender appender = new AbstractAppender( appenderName, null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String loggerName = KgMaterializationService.class.getName();
        LoggerConfig loggerConfig = config.getLoggerConfig( loggerName );
        if ( !loggerConfig.getName().equals( loggerName ) ) {
            loggerConfig = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.INFO )
                .setLoggerName( loggerName ).setIncludeLocation( "true" )
                .setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
            config.addLogger( loggerName, loggerConfig );
        }
        loggerConfig.addAppender( appender, Level.INFO, null );
        ctx.updateLoggers();

        try {
            svc.materializeMachine( p );

            final List< LogEvent > relevant = captured.stream()
                .filter( e -> e.getMessage().getFormattedMessage().contains( p.id().toString() ) )
                .toList();
            assertEquals( 1, relevant.size(), "exactly one audit line for the replayed proposal: " + captured );
            final LogEvent event = relevant.get( 0 );
            assertEquals( Level.INFO, event.getLevel(), "an existing node is expected, not a warning" );
            assertTrue( event.getMessage().getFormattedMessage().contains( "PostgreSQL" ),
                "the audit line must name the node" );
        } finally {
            loggerConfig.removeAppender( appenderName );
            ctx.updateLoggers();
            appender.stop();
        }
    }
}
