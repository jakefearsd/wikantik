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
package com.wikantik.derived;

import com.wikantik.TestEngine;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.WikiProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization test for {@link DerivedIngestionServiceFactory}.
 *
 * <p>Proves the factory wires a real, working {@link DerivedPageIngestionService} against the
 * live wiki managers — not just that it returns a non-null object. Uses a real {@link TestEngine}
 * (rather than deep Mockito mocks) because the page-writer seam runs through the concrete
 * {@code PageSaveHelper}, which in turn drives {@code Wiki.contents()}/{@code Wiki.context()}
 * static SPI factories; mocking that chain would be more brittle than exercising it for real.
 * The detailed derived_from behavior itself is already covered by
 * {@link DerivedPageIngestionServiceTest}, so this test stays to a minimal round-trip.
 */
class DerivedIngestionServiceFactoryTest {

    private TestEngine engine;

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void build_returnsNonNullService() {
        engine = TestEngine.build();
        final PageManager pm = engine.getManager( PageManager.class );
        final AttachmentManager am = engine.getManager( AttachmentManager.class );

        final DerivedPageIngestionService service = DerivedIngestionServiceFactory.build( engine, pm, am );

        assertNotNull( service, "factory must produce a wired service" );
    }

    @Test
    void ingest_roundTripsDerivedFromViaRealManagers() {
        engine = TestEngine.build();
        final PageManager pm = engine.getManager( PageManager.class );
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        final DerivedPageIngestionService service = DerivedIngestionServiceFactory.build( engine, pm, am );

        final byte[] source = "Hello factory-wired ingest".getBytes( StandardCharsets.UTF_8 );
        final IngestResult result = service.ingest( source, "FactoryDoc.txt", "text/plain",
            new IngestOptions( false, "tester" ) );

        assertEquals( IngestResult.Status.CREATED, result.status(), "first ingest must create the page" );

        final String saved = pm.getPureText( result.pageName(), WikiProvider.LATEST_VERSION );
        assertNotNull( saved, "page must be persisted by the real PageManager" );
        final var metadata = FrontmatterParser.parse( saved ).metadata();
        assertEquals( "FactoryDoc.txt", metadata.get( DerivedPage.DERIVED_FROM ),
            "derived_from must round-trip through the factory-wired PageWriter/PageReader seams" );

        // Re-ingesting identical bytes without force must dedup via the wired PageReader.
        final IngestResult second = service.ingest( source, "FactoryDoc.txt", "text/plain",
            new IngestOptions( false, "tester" ) );
        assertEquals( IngestResult.Status.UNCHANGED, second.status(),
            "unchanged dedup proves the PageReader seam reads back the just-written page" );
    }
}
