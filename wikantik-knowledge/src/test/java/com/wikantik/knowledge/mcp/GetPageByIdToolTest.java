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
import com.wikantik.api.pagegraph.StructuralIndexService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageByIdToolTest {

    @Test
    void returns_descriptor_for_known_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01A", "X", "X", PageType.ARTICLE, null, List.of(), "s", Instant.EPOCH, Optional.empty(), false ) ) );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "01A" ) );
        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"canonicalId\":\"01A\"" ), "payload must echo the resolved canonical id" );
        assertTrue( text.contains( "\"slug\":\"X\"" ), "payload must include the page slug" );
    }

    @Test
    void name_returns_tool_name_constant() {
        final GetPageByIdTool tool = new GetPageByIdTool( mock( StructuralIndexService.class ) );
        assertEquals( "get_page_by_id", tool.name() );
        assertEquals( GetPageByIdTool.TOOL_NAME, tool.name() );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void definition_declares_id_and_legacy_alias_properties() {
        final GetPageByIdTool tool = new GetPageByIdTool( mock( StructuralIndexService.class ) );
        final McpSchema.Tool definition = tool.definition();

        assertEquals( "get_page_by_id", definition.name() );
        assertTrue( definition.description().contains( "canonical id" ) );

        final Map< String, Object > props = definition.inputSchema().properties();
        assertTrue( props.containsKey( "id" ) );
        assertTrue( props.containsKey( "canonical_id" ) );
        final Map< String, Object > canonicalIdProp = ( Map< String, Object > ) props.get( "canonical_id" );
        assertTrue( ( (String) canonicalIdProp.get( "description" ) ).contains( "Deprecated" ) );

        assertEquals( "object", definition.outputSchema().get( "type" ) );
        assertTrue( definition.annotations().readOnlyHint() );
    }

    @Test
    void execute_returnsErrorResult_whenServiceThrows() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01A" ) ).thenThrow( new RuntimeException( "index unavailable" ) );

        final var result = new GetPageByIdTool( svc ).execute( Map.of( "id", "01A" ) );

        assertTrue( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "index unavailable" ) );
    }

    @Test
    void null_viewGate_defaults_to_allow_all() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01A" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01A", "AnyPage", "AnyPage", PageType.ARTICLE, null, List.of(),
                        "s", Instant.EPOCH, Optional.empty(), false ) ) );

        final var result = new GetPageByIdTool( svc, null ).execute( Map.of( "id", "01A" ) );

        assertFalse( result.isError(), "a null viewGate must degrade to allow-all rather than blocking every page" );
    }

    @Test
    void returns_error_for_missing_id() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "missing" ) ).thenReturn( Optional.empty() );
        final var result = new GetPageByIdTool( svc ).execute( Map.of( "canonical_id", "missing" ) );
        assertTrue( result.isError() );
    }

    @Test
    void requires_canonical_id_argument() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final var result = new GetPageByIdTool( svc ).execute( Map.of() );
        assertTrue( result.isError() );
    }

    @Test
    void restrictedPageFilteredForGuest() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.getByCanonicalId( "01SECRET" ) ).thenReturn( Optional.of(
                new PageDescriptor( "01SECRET", "Secret", "Secret Page",
                        PageType.ARTICLE, null, List.of(), "TOP SECRET SUMMARY", Instant.EPOCH, Optional.empty(), false ) ) );

        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final var result = new GetPageByIdTool( svc, gate ).execute( Map.of( "canonical_id", "01SECRET" ) );

        assertTrue( result.isError(), "restricted page must return error (same as not found)" );
        final String text = ( (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "TOP SECRET SUMMARY" ),
                "restricted page content must not leak through get_page_by_id" );
    }
}
