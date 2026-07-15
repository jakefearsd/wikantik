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

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListPagesByFilterToolTest {

    @Test
    void forwards_filter_arguments_to_service() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of() );
        final ArgumentCaptor< StructuralFilter > cap = ArgumentCaptor.forClass( StructuralFilter.class );

        new ListPagesByFilterTool( svc ).execute( Map.of(
                "type", "article",
                "cluster", "wikantik-development",
                "tag", List.of( "retrieval" ),
                "limit", 5 ) );

        verify( svc ).listPagesByFilter( cap.capture() );
        final StructuralFilter f = cap.getValue();
        assertEquals( PageType.ARTICLE,       f.type().orElseThrow() );
        assertEquals( "wikantik-development", f.cluster().orElseThrow() );
        assertEquals( List.of( "retrieval" ),  f.tags() );
        assertEquals( 5,                       f.limit() );
    }

    @Test
    void returns_pages_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(),
                        "summary", Instant.EPOCH, Optional.empty(), false ) ) );

        final var result = new ListPagesByFilterTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "\"slug\"" ) && content.text().contains( "X" ) );
    }

    @Test
    void name_returns_tool_name_constant() {
        final ListPagesByFilterTool tool = new ListPagesByFilterTool( mock( StructuralIndexService.class ) );
        assertEquals( "list_pages_by_filter", tool.name() );
        assertEquals( ListPagesByFilterTool.TOOL_NAME, tool.name() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void definition_declares_filter_properties_and_output_schema() {
        final ListPagesByFilterTool tool = new ListPagesByFilterTool( mock( StructuralIndexService.class ) );
        final McpSchema.Tool definition = tool.definition();

        assertEquals( "list_pages_by_filter", definition.name() );
        assertTrue( definition.description().contains( "structural filter" ) );

        final Map< String, Object > props = definition.inputSchema().properties();
        assertTrue( props.keySet().containsAll(
                List.of( "type", "cluster", "tag", "updated_since", "limit" ) ) );
        final Map< String, Object > tagProp = ( Map< String, Object > ) props.get( "tag" );
        assertEquals( "array", tagProp.get( "type" ) );

        assertEquals( "object", definition.outputSchema().get( "type" ) );
        assertTrue( definition.annotations().readOnlyHint() );
    }

    @Test
    void coerces_single_string_tag_argument_into_a_singleton_list() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of() );
        final ArgumentCaptor< StructuralFilter > cap = ArgumentCaptor.forClass( StructuralFilter.class );

        new ListPagesByFilterTool( svc ).execute( Map.of( "tag", "retrieval" ) );

        verify( svc ).listPagesByFilter( cap.capture() );
        assertEquals( List.of( "retrieval" ), cap.getValue().tags() );
    }

    @Test
    void execute_returnsErrorResult_whenUpdatedSinceIsUnparseable() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );

        final var result = new ListPagesByFilterTool( svc )
                .execute( Map.of( "updated_since", "not-an-instant" ) );

        assertTrue( result.isError() );
        verify( svc, never() ).listPagesByFilter( any() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertFalse( text.isBlank() );
    }

    @Test
    void execute_returnsErrorResult_whenServiceThrows() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenThrow( new RuntimeException( "index unavailable" ) );

        final var result = new ListPagesByFilterTool( svc ).execute( Map.of() );

        assertTrue( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "index unavailable" ) );
    }

    @Test
    void restrictedPageFilteredForGuest() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "01SEC", "Secret", "Secret Page", PageType.ARTICLE, null,
                        List.of(), "TOP SECRET SUMMARY", Instant.EPOCH, Optional.empty(), false ),
                new PageDescriptor( "01PUB", "Public", "Public Page", PageType.ARTICLE, null,
                        List.of(), "public summary", Instant.EPOCH, Optional.empty(), false ) ) );

        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final var result = new ListPagesByFilterTool( svc, gate ).execute( Map.of() );

        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "TOP SECRET SUMMARY" ),
                "restricted page content must not leak through list_pages_by_filter" );
        assertTrue( text.contains( "Public" ),
                "non-restricted pages must still appear" );
    }
}
