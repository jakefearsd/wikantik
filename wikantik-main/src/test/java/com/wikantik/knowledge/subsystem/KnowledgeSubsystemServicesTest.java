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
package com.wikantik.knowledge.subsystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.WikiEngine;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the set-once retrieval trio on {@link KnowledgeSubsystem.Services}:
 * null-safe accessors before install, CAS install-exactly-once semantics, null-reference
 * normalization, and the carry-the-same-reference invariant on the factory rebuild paths.
 */
class KnowledgeSubsystemServicesTest {

    /** All-null Services — the compact constructor must normalize the null retrieval ref. */
    private static KnowledgeSubsystem.Services allNullServices() {
        return new KnowledgeSubsystem.Services(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null );
    }

    @Test
    void retrievalAccessorsAreNullBeforeInstall() {
        final KnowledgeSubsystem.Services services = allNullServices();
        assertNull( services.contextRetrievalService() );
        assertNull( services.bundleAssemblyService() );
        assertNull( services.briefingAssemblyService() );
    }

    @Test
    void installExposesTheTrioThroughTheAccessors() {
        final KnowledgeSubsystem.Services services = allNullServices();
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        final BundleAssemblyService bundle = Mockito.mock( BundleAssemblyService.class );
        final BriefingAssemblyService briefing = Mockito.mock( BriefingAssemblyService.class );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( ctx, bundle, briefing ) ) );
        assertSame( ctx, services.contextRetrievalService() );
        assertSame( bundle, services.bundleAssemblyService() );
        assertSame( briefing, services.briefingAssemblyService() );
    }

    @Test
    void secondInstallIsRefusedAndTheOriginalIsRetained() {
        final KnowledgeSubsystem.Services services = allNullServices();
        final ContextRetrievalService first = Mockito.mock( ContextRetrievalService.class );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( first, null, null ) ) );
        final ContextRetrievalService second = Mockito.mock( ContextRetrievalService.class );
        assertFalse( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( second, null, null ) ),
            "a second install must be refused" );
        assertSame( first, services.contextRetrievalService(),
            "the originally installed service must be retained" );
    }

    @Test
    void nullRetrievalReferenceIsNormalizedToAnEmptyOne() {
        final KnowledgeSubsystem.Services services = allNullServices();
        assertNotNull( services.retrieval(),
            "compact constructor must normalize a null retrieval reference" );
        assertTrue( services.installRetrieval(
            new KnowledgeSubsystem.RetrievalServices( null, null, null ) ) );
    }

    @Test
    void rebuildFromExistingCarriesTheSameRetrievalReference() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final KnowledgeSubsystem.Services existing = allNullServices();
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        existing.installRetrieval( new KnowledgeSubsystem.RetrievalServices( ctx, null, null ) );
        final KnowledgeSubsystem.Services rebuilt =
            KnowledgeSubsystemFactory.rebuildFromExisting( engine, existing );
        assertSame( existing.retrieval(), rebuilt.retrieval(),
            "hot-swap rebuilds must share the set-once reference, not copy its value" );
        assertSame( ctx, rebuilt.contextRetrievalService() );
    }

    @Test
    void readFromManagerRegistrySeedsContextRetrievalFromTheRegistry() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final ContextRetrievalService ctx = Mockito.mock( ContextRetrievalService.class );
        Mockito.when( engine.getManager( ContextRetrievalService.class ) ).thenReturn( ctx );
        final KnowledgeSubsystem.Services services =
            KnowledgeSubsystemFactory.readFromManagerRegistry( engine );
        assertSame( ctx, services.contextRetrievalService() );
        assertNull( services.bundleAssemblyService(),
            "bundle/briefing are only ever built at the patch seam, never on the cold path" );
    }
}
