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

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListPagesToolTest {

    @Test
    void name_isListPages() {
        assertEquals( "list_pages", new ListPagesTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_hasNoRequiredFields() {
        final McpSchema.Tool def = new ListPagesTool( mock( ContextRetrievalService.class ) ).definition();
        assertTrue( def.inputSchema().required() == null || def.inputSchema().required().isEmpty() );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) ).thenReturn(
            new PageList( List.of(
                new RetrievedPage( "Alpha", "u1", 0.0, "", "search", List.of(),
                    List.of(), List.of(), null, new Date() ),
                new RetrievedPage( "Beta", "u2", 0.0, "", "search", List.of(),
                    List.of(), List.of(), null, new Date() ) ),
                5, 50, 0 ) );

        final McpSchema.CallToolResult result =
            new ListPagesTool( svc ).execute( Map.of( "cluster", "search" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"totalMatched\":5" ) );
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"name\":\"Beta\"" ) );
    }

    @Test
    void execute_passesFiltersToService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) )
            .thenReturn( new PageList( List.of(), 0, 50, 0 ) );

        new ListPagesTool( svc ).execute( Map.of(
            "cluster", "search",
            "tags", List.of( "retrieval", "bm25" ),
            "limit", 25,
            "offset", 5 ) );

        final ArgumentCaptor< PageListFilter > captor = ArgumentCaptor.forClass( PageListFilter.class );
        verify( svc ).listPages( captor.capture() );
        final PageListFilter f = captor.getValue();
        assertEquals( "search", f.cluster() );
        assertEquals( List.of( "retrieval", "bm25" ), f.tags() );
        assertEquals( 25, f.limit() );
        assertEquals( 5, f.offset() );
    }

    @Test
    void execute_defaultsToEmptyFilter() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) )
            .thenReturn( new PageList( List.of(), 0, 50, 0 ) );

        new ListPagesTool( svc ).execute( Map.of() );

        final ArgumentCaptor< PageListFilter > captor = ArgumentCaptor.forClass( PageListFilter.class );
        verify( svc ).listPages( captor.capture() );
        assertNull( captor.getValue().cluster() );
        assertEquals( 50, captor.getValue().limit() );
    }

    @Test
    void execute_returnsErrorOnRuntimeExceptionFromService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.listPages( any( PageListFilter.class ) ) )
            .thenThrow( new RuntimeException( "DB offline" ) );

        final McpSchema.CallToolResult result = new ListPagesTool( svc ).execute( Map.of() );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }

    @Test
    void execute_rejectsInvalidIsoInstantFilter() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final McpSchema.CallToolResult result = new ListPagesTool( svc ).execute( Map.of(
            "modifiedAfter", "garbage-date" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.toLowerCase().contains( "invalid iso-8601 instant" ) );
    }
}
