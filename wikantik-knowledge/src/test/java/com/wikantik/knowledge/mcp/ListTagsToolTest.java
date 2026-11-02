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

import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListTagsToolTest {

    @Test
    void min_pages_defaults_to_1() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( eq( 1 ), any() ) ).thenReturn( List.of( new TagSummary( "retrieval", 5, List.of() ) ) );
        final ListTagsTool tool = new ListTagsTool( svc );
        final var result = tool.execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "retrieval" ) );
        verify( svc ).listTags( eq( 1 ), any() );
    }

    @Test
    void respects_min_pages_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( eq( 5 ), any() ) ).thenReturn( List.of() );
        new ListTagsTool( svc ).execute( Map.of( "min_pages", 5 ) );
        verify( svc ).listTags( eq( 5 ), any() );
    }

    @Test
    void name_returns_tool_name_constant() {
        final ListTagsTool tool = new ListTagsTool( mock( StructuralIndexService.class ) );
        assertEquals( "list_tags", tool.name() );
        assertEquals( ListTagsTool.TOOL_NAME, tool.name() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void definition_declares_min_pages_and_pagination_schema() {
        final ListTagsTool tool = new ListTagsTool( mock( StructuralIndexService.class ) );
        final McpSchema.Tool definition = tool.definition();

        assertEquals( "list_tags", definition.name() );
        assertTrue( definition.description().contains( "tag dictionary" ) );

        final Map< String, Object > props = ToolSchemas.properties( definition.inputSchema() );
        assertTrue( props.containsKey( "min_pages" ), "input schema must declare min_pages" );
        final Map< String, Object > minPages = ( Map< String, Object > ) props.get( "min_pages" );
        assertEquals( "integer", minPages.get( "type" ) );

        assertEquals( "object", definition.outputSchema().get( "type" ) );
        assertNotNull( definition.annotations() );
        assertTrue( definition.annotations().readOnlyHint() );
    }

    @Test
    void execute_returnsErrorResult_whenServiceThrows() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listTags( eq( 1 ), any() ) ).thenThrow( new RuntimeException( "index unavailable" ) );

        final var result = new ListTagsTool( svc ).execute( Map.of() );

        assertTrue( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "index unavailable" ),
                "error payload should surface the underlying exception message" );
    }

    /** list_tags counts only pages the (guest) view gate admits. */
    @SuppressWarnings( "unchecked" )
    @Test
    void passes_the_view_gate_so_restricted_pages_are_not_counted() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final var captor = org.mockito.ArgumentCaptor.forClass( java.util.function.Predicate.class );
        when( svc.listTags( eq( 1 ), captor.capture() ) ).thenReturn( List.of() );

        new ListTagsTool( svc, slug -> !"Secret".equals( slug ) ).execute( Map.of() );

        final java.util.function.Predicate< com.wikantik.api.pagegraph.PageDescriptor > visible = captor.getValue();
        final var open = new com.wikantik.api.pagegraph.PageDescriptor( "1", "Open", "t", null, null, List.of(), "", null, null, false );
        final var secret = new com.wikantik.api.pagegraph.PageDescriptor( "2", "Secret", "t", null, null, List.of(), "", null, null, false );
        assertTrue( visible.test( open ) );
        assertFalse( visible.test( secret ) );
    }
}
