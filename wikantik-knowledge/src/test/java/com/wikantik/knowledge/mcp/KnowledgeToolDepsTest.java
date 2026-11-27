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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.citation.CitationRepository;
import com.wikantik.ontology.OntologyModelManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** {@link KnowledgeToolDeps} is a plain builder/data holder — a round trip through every
 *  setter proves the builder actually wires each field to its accessor, and that the two
 *  {@link java.util.function.Supplier} fields default to a null-returning supplier rather
 *  than a null field (so callers never need a null check on the supplier itself). */
class KnowledgeToolDepsTest {

    @Test
    void unsetSupplierFieldsDefaultToNullReturningSuppliers() {
        final KnowledgeToolDeps deps = KnowledgeToolDeps.builder().build();

        assertNotNull( deps.queryLog(), "queryLog supplier itself should never be null" );
        assertNull( deps.queryLog().get() );
        assertNotNull( deps.briefingLog(), "briefingLog supplier itself should never be null" );
        assertNull( deps.briefingLog().get() );
        assertEquals( PageViewGate.ALLOW_ALL, deps.viewGate(), "unset viewGate defaults to ALLOW_ALL" );
    }

    @Test
    void builderWiresEveryOptionalCollaboratorToItsAccessor() {
        final BundleAssemblyService bundle = mock( BundleAssemblyService.class );
        final BriefingAssemblyService briefing = mock( BriefingAssemblyService.class );
        final OntologyModelManager onto = OntologyModelManager.inMemory();
        final CitationRepository citations = mock( CitationRepository.class );
        final QueryLogService queryLogService = mock( QueryLogService.class );
        final BriefingLogService briefingLogService = mock( BriefingLogService.class );

        final KnowledgeToolDeps deps = KnowledgeToolDeps.builder()
                .bundleService( bundle )
                .briefingService( briefing )
                .ontoMgr( onto )
                .citationRepo( citations )
                .queryLog( () -> queryLogService )
                .briefingLog( () -> briefingLogService )
                .build();

        assertSame( bundle, deps.bundleService() );
        assertSame( briefing, deps.briefingService() );
        assertSame( onto, deps.ontoMgr() );
        assertSame( citations, deps.citationRepo() );
        assertSame( queryLogService, deps.queryLog().get() );
        assertSame( briefingLogService, deps.briefingLog().get() );
    }

    @Test
    void builderWiresTheRemainingCollaboratorsAndSuppliersToTheirAccessors() {
        final com.wikantik.api.knowledge.ContextRetrievalService ctx = mock( com.wikantik.api.knowledge.ContextRetrievalService.class );
        final com.wikantik.api.managers.PageManager pm = mock( com.wikantik.api.managers.PageManager.class );
        final com.wikantik.api.pagegraph.StructuralIndexService index = mock( com.wikantik.api.pagegraph.StructuralIndexService.class );
        final com.wikantik.api.agent.ForAgentProjectionService forAgent = mock( com.wikantik.api.agent.ForAgentProjectionService.class );
        final com.wikantik.api.managers.ReferenceManager refs = mock( com.wikantik.api.managers.ReferenceManager.class );
        final com.wikantik.api.livevalues.LiveValues live = mock( com.wikantik.api.livevalues.LiveValues.class );
        final com.wikantik.api.identity.PublicIdentity identity = mock( com.wikantik.api.identity.PublicIdentity.class );
        final com.wikantik.agentreports.AgentReportService reports = mock( com.wikantik.agentreports.AgentReportService.class );
        final PageViewGate gate = slug -> false;

        final KnowledgeToolDeps deps = KnowledgeToolDeps.builder()
                .ctxService( ctx ).pageManager( pm ).structuralIndex( index ).forAgent( forAgent )
                .referenceManager( () -> refs ).liveValues( () -> live ).publicIdentity( () -> identity )
                .agentReports( () -> reports ).viewGate( gate ).draftActive( "Drafty"::equals )
                .build();

        assertSame( ctx, deps.ctxService() );
        assertSame( pm, deps.pageManager() );
        assertSame( index, deps.structuralIndex() );
        assertSame( forAgent, deps.forAgent() );
        assertSame( refs, deps.referenceManager().get() );
        assertSame( live, deps.liveValues().get() );
        assertSame( identity, deps.publicIdentity().get() );
        assertSame( reports, deps.agentReports().get() );
        assertSame( gate, deps.viewGate() );
        assertTrue( deps.draftActive().test( "Drafty" ) );
        assertFalse( deps.draftActive().test( "Other" ) );
    }

    @Test
    void unsetOptionalHooksDefaultToInertValuesRatherThanNull() {
        final KnowledgeToolDeps deps = KnowledgeToolDeps.builder().build();
        assertNotNull( deps.referenceManager() );
        assertNull( deps.referenceManager().get() );
        assertNotNull( deps.liveValues() );
        assertNull( deps.liveValues().get() );
        assertNotNull( deps.publicIdentity() );
        assertNull( deps.publicIdentity().get() );
        assertNotNull( deps.agentReports() );
        assertNull( deps.agentReports().get() );
        assertNotNull( deps.draftActive() );
        assertFalse( deps.draftActive().test( "AnyPage" ), "no draft is active unless a hook says so" );
        assertNull( deps.ctxService() );
        assertNull( deps.pageManager() );
    }
}
