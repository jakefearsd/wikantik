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

import com.wikantik.api.pagegraph.ClusterSummary;
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

class ListClustersToolTest {

    @Test
    void returns_tool_definition_with_expected_name() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        final ListClustersTool tool = new ListClustersTool( svc );
        assertEquals( "list_clusters", tool.name() );
        final McpSchema.Tool def = tool.definition();
        assertNotNull( def );
        assertEquals( "list_clusters", def.name() );
    }

    @Test
    void execute_returns_clusters_as_json_payload() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listClusters() ).thenReturn( List.of( new ClusterSummary(
                "wikantik-development",
                new PageDescriptor( "01A", "WikantikDevelopment", "Wikantik Development",
                        PageType.HUB, "wikantik-development", List.of(), "hub", Instant.EPOCH, Optional.empty() ),
                12, Instant.EPOCH ) ) );

        final var result = new ListClustersTool( svc ).execute( Map.of() );
        assertFalse( result.isError() );
        final var content = ( McpSchema.TextContent ) result.content().get( 0 );
        assertTrue( content.text().contains( "wikantik-development" ) );
    }

    private ClusterSummary cluster( final int i ) {
        return new ClusterSummary( "c" + i,
                new PageDescriptor( "01A" + i, "Hub" + i, "Hub " + i, PageType.HUB, "c" + i,
                        List.of(), "hub", Instant.EPOCH, Optional.empty() ),
                1, Instant.EPOCH );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > parse( final McpSchema.CallToolResult r ) {
        return new com.google.gson.Gson().fromJson(
                ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text(), Map.class );
    }

    @Test
    void paginates_with_default_limit_and_offset() {
        final StructuralIndexService svc = mock( StructuralIndexService.class );
        when( svc.listClusters() ).thenReturn(
                java.util.stream.IntStream.range( 0, 60 ).mapToObj( this::cluster ).toList() );

        // Default limit caps the dump at 50, but reports the true total + hasMore.
        final Map< String, Object > def = parse( new ListClustersTool( svc ).execute( Map.of() ) );
        assertEquals( 60.0, def.get( "count" ) );
        assertEquals( 50.0, def.get( "returned" ) );
        assertEquals( Boolean.TRUE, def.get( "hasMore" ) );
        assertEquals( 50, ( ( List< ? > ) def.get( "clusters" ) ).size() );

        // offset pages to the tail.
        final Map< String, Object > pg = parse( new ListClustersTool( svc ).execute( Map.of( "offset", 50 ) ) );
        assertEquals( 10.0, pg.get( "returned" ) );
        assertEquals( Boolean.FALSE, pg.get( "hasMore" ) );
    }
}
