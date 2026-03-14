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
package org.apache.wiki.mcp.completions;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.wiki.TestEngine;
import org.apache.wiki.references.ReferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiCompletionsTest {

    private TestEngine engine;
    private ReferenceManager referenceManager;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        referenceManager = engine.getManager( ReferenceManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testReturnsThreeCompletionSpecs() {
        final List< McpServerFeatures.SyncCompletionSpecification > specs = WikiCompletions.all( referenceManager );
        assertEquals( 3, specs.size() );
    }

    @Test
    void testCompletionFiltersPagesByPrefix() throws Exception {
        engine.saveText( "AlphaPage", "Content" );
        engine.saveText( "AlphaBeta", "Content" );
        engine.saveText( "GammaPage", "Content" );

        final List< McpServerFeatures.SyncCompletionSpecification > specs = WikiCompletions.all( referenceManager );
        // Find the audit-links completion (first one)
        final McpServerFeatures.SyncCompletionSpecification auditCompletion = specs.get( 0 );

        final McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(
                new McpSchema.PromptReference( "audit-links" ),
                new McpSchema.CompleteRequest.CompleteArgument( "pageName", "Alpha" ) );

        final McpSchema.CompleteResult result = auditCompletion.completionHandler().apply( null, request );
        final List< String > values = result.completion().values();

        assertTrue( values.contains( "AlphaPage" ) );
        assertTrue( values.contains( "AlphaBeta" ) );
        assertFalse( values.contains( "GammaPage" ) );
    }

    @Test
    void testCompletionReturnsEmptyForNonMatchingArgument() throws Exception {
        engine.saveText( "TestPage", "Content" );

        final List< McpServerFeatures.SyncCompletionSpecification > specs = WikiCompletions.all( referenceManager );
        final McpServerFeatures.SyncCompletionSpecification auditCompletion = specs.get( 0 );

        // Send a different argument name than expected
        final McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(
                new McpSchema.PromptReference( "audit-links" ),
                new McpSchema.CompleteRequest.CompleteArgument( "wrongArg", "Test" ) );

        final McpSchema.CompleteResult result = auditCompletion.completionHandler().apply( null, request );
        assertTrue( result.completion().values().isEmpty() );
    }
}
