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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListOrphanedKgNodesToolTest {

    private KnowledgeGraphService service;
    private ListOrphanedKgNodesTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        service = Mockito.mock( KnowledgeGraphService.class );
        tool = new ListOrphanedKgNodesTool( service );
    }

    @Test
    void definitionExposesAdminBypassToolNameAndReadOnlyHint() {
        final McpSchema.Tool def = tool.definition();
        assertEquals( "list_orphaned_kg_nodes", def.name() );
        assertTrue( def.annotations().readOnlyHint(),
                "list_orphaned_kg_nodes is read-only and must declare it" );
        assertTrue( def.description().toLowerCase().contains( "orphan" ),
                "description should mention orphan: " + def.description() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void executeWithNoArgsForwardsDefaultsAndAdminBypass() {
        when( service.listOrphanedNodes( any(), anyInt(), anyInt() ) )
                .thenReturn( List.of() );
        when( service.countOrphanedNodes( any() ) )
                .thenReturn( 0L );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        final ArgumentCaptor< Map< String, Object > > filterCaptor =
                ArgumentCaptor.forClass( Map.class );
        verify( service ).listOrphanedNodes( filterCaptor.capture(), eq( 50 ), eq( 0 ) );
        // No filters supplied -> tool passes null (matches AdminQueryNodesTool convention).
        assertNull( filterCaptor.getValue() );

        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > payload = gson.fromJson( body, Map.class );
        assertEquals( Boolean.TRUE, payload.get( "admin_bypass" ) );
        assertNotNull( payload.get( "results" ) );
        assertEquals( 0.0, ( ( Number ) payload.get( "count" ) ).doubleValue() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void executeForwardsNodeTypeAndSourcePageExcludedFilters() {
        when( service.listOrphanedNodes( any(), anyInt(), anyInt() ) )
                .thenReturn( List.of() );
        when( service.countOrphanedNodes( any() ) )
                .thenReturn( 5L );

        tool.execute( Map.of(
                "filters", Map.of(
                        "node_type", "concept",
                        "source_page_excluded", true ),
                "limit", 25,
                "offset", 10 ) );

        final ArgumentCaptor< Map< String, Object > > listCaptor =
                ArgumentCaptor.forClass( Map.class );
        final ArgumentCaptor< Integer > limitCaptor = ArgumentCaptor.forClass( Integer.class );
        final ArgumentCaptor< Integer > offsetCaptor = ArgumentCaptor.forClass( Integer.class );
        verify( service ).listOrphanedNodes(
                listCaptor.capture(), limitCaptor.capture(), offsetCaptor.capture() );

        final Map< String, Object > captured = listCaptor.getValue();
        assertNotNull( captured );
        assertEquals( "concept", captured.get( "node_type" ) );
        assertEquals( Boolean.TRUE, captured.get( "source_page_excluded" ) );
        assertEquals( 25, limitCaptor.getValue().intValue() );
        assertEquals( 10, offsetCaptor.getValue().intValue() );

        final ArgumentCaptor< Map< String, Object > > countCaptor =
                ArgumentCaptor.forClass( Map.class );
        verify( service ).countOrphanedNodes( countCaptor.capture() );
        assertEquals( captured, countCaptor.getValue(),
                "count must use the same filter map as the list call so the totals cannot drift" );
    }

    // Covers ListOrphanedKgNodesTool.java:138-141 — service exceptions are
    // logged and surfaced via McpToolUtils.errorResult instead of propagating.
    @Test
    void serviceExceptionYieldsErrorResult() {
        when( service.listOrphanedNodes( any(), anyInt(), anyInt() ) )
                .thenThrow( new RuntimeException( "DB connection refused" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError(), "service exception must produce an error result" );
        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( body.contains( "DB connection refused" ),
                "error body must include the service exception message: " + body );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void executeReturnsResultsAndCount() {
        final KgNode orphan = new KgNode(
                UUID.fromString( "ffffffff-0001-0000-0000-000000000001" ),
                "OrphanConcept",
                "concept",
                "ExcludedPage",
                Provenance.HUMAN_AUTHORED,
                Map.of(),
                Instant.parse( "2026-05-14T00:00:00Z" ),
                Instant.parse( "2026-05-14T00:00:00Z" ),
                "human",
                null );
        when( service.listOrphanedNodes( any(), anyInt(), anyInt() ) )
                .thenReturn( List.of( orphan ) );
        when( service.countOrphanedNodes( any() ) )
                .thenReturn( 1L );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final Map< String, Object > payload = gson.fromJson( body, Map.class );

        assertEquals( 1.0, ( ( Number ) payload.get( "count" ) ).doubleValue() );
        final List< Map< String, Object > > results =
                ( List< Map< String, Object > > ) payload.get( "results" );
        assertEquals( 1, results.size() );
        assertEquals( "OrphanConcept", results.get( 0 ).get( "name" ) );
        assertEquals( Boolean.TRUE, payload.get( "admin_bypass" ) );
    }

}
