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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.citation.CitationStatus;
import com.wikantik.citation.CitationRepository;
import com.wikantik.citation.CitationRow;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListStaleCitationsToolTest {

    private static CitationRow row( final String src, final String tgt, final CitationStatus status ) {
        return new CitationRow( 1L, src, tgt, "## Intro", "span", "abc123",
                "claim text", 0, 42, status,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH );
    }

    @Test
    void name_is_list_stale_citations() {
        final CitationRepository repo = mock( CitationRepository.class );
        assertEquals( "list_stale_citations", new ListStaleCitationsTool( repo ).name() );
    }

    @Test
    void page_outbound_filters_to_stale_only() {
        final CitationRepository repo = mock( CitationRepository.class );
        when( repo.findBySource( "p1" ) ).thenReturn( List.of(
                row( "p1", "t1", CitationStatus.STALE ),
                row( "p1", "t2", CitationStatus.CURRENT )
        ) );

        final ListStaleCitationsTool tool = new ListStaleCitationsTool( repo );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "page", "p1" ) );

        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final JsonObject obj = JsonParser.parseString( text ).getAsJsonObject();
        final JsonArray citations = obj.getAsJsonArray( "citations" );
        assertEquals( 1, citations.size(), "only stale row should appear" );
        assertEquals( "t1", citations.get( 0 ).getAsJsonObject().get( "targetCanonicalId" ).getAsString() );
        assertEquals( "stale", citations.get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
    }

    @Test
    void page_inbound_filters_to_stale_only() {
        final CitationRepository repo = mock( CitationRepository.class );
        when( repo.findByTarget( "p1" ) ).thenReturn( List.of(
                row( "src1", "p1", CitationStatus.TARGET_MISSING ),
                row( "src2", "p1", CitationStatus.CURRENT )
        ) );

        final ListStaleCitationsTool tool = new ListStaleCitationsTool( repo );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "page", "p1", "direction", "inbound" ) );

        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final JsonObject obj = JsonParser.parseString( text ).getAsJsonObject();
        final JsonArray citations = obj.getAsJsonArray( "citations" );
        assertEquals( 1, citations.size(), "only target_missing row should appear" );
        assertEquals( "target_missing", citations.get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
    }

    @Test
    void page_both_merges_outbound_and_inbound() {
        final CitationRepository repo = mock( CitationRepository.class );
        when( repo.findBySource( "p1" ) ).thenReturn( List.of(
                row( "p1", "t1", CitationStatus.STALE )
        ) );
        when( repo.findByTarget( "p1" ) ).thenReturn( List.of(
                row( "src1", "p1", CitationStatus.TARGET_MISSING ),
                row( "src2", "p1", CitationStatus.CURRENT )
        ) );

        final ListStaleCitationsTool tool = new ListStaleCitationsTool( repo );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "page", "p1", "direction", "both" ) );

        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final JsonObject obj = JsonParser.parseString( text ).getAsJsonObject();
        final JsonArray citations = obj.getAsJsonArray( "citations" );
        assertEquals( 2, citations.size(), "stale outbound + target_missing inbound" );
    }

    @Test
    void no_page_uses_findByStatus_and_caps_at_limit() {
        final CitationRepository repo = mock( CitationRepository.class );
        when( repo.findByStatus( CitationStatus.STALE ) ).thenReturn( List.of(
                row( "a", "b", CitationStatus.STALE ),
                row( "c", "d", CitationStatus.STALE )
        ) );
        when( repo.findByStatus( CitationStatus.TARGET_MISSING ) ).thenReturn( List.of(
                row( "e", "f", CitationStatus.TARGET_MISSING )
        ) );

        final ListStaleCitationsTool tool = new ListStaleCitationsTool( repo );
        final McpSchema.CallToolResult result = tool.execute( Map.of( "limit", 2 ) );

        assertFalse( result.isError() );
        final String text = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        final JsonObject obj = JsonParser.parseString( text ).getAsJsonObject();
        final JsonArray citations = obj.getAsJsonArray( "citations" );
        assertEquals( 2, citations.size(), "capped at limit=2" );
    }
}
