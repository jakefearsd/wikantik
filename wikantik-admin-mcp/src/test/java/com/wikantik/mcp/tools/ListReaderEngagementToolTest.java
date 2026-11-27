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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.insights.readers.PageReaderStats;
import com.wikantik.insights.readers.ReaderReport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListReaderEngagementToolTest {

    private ReaderReport report;
    private ListReaderEngagementTool tool;

    @BeforeEach
    void setUp() {
        report = mock( ReaderReport.class );
        tool = new ListReaderEngagementTool( report, "wiki.example.com" );
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    @Test
    void nameIsListReaderEngagement() {
        assertEquals( "list_reader_engagement", tool.name() );
        assertEquals( "list_reader_engagement", tool.definition().name() );
    }

    @Test
    void refusesWhenUnwired() {
        final McpSchema.CallToolResult r = new ListReaderEngagementTool( null, "h" ).execute( Map.of() );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "not available" ) );
    }

    @Test
    void defaultsAndClampsArguments() {
        when( report.pages( anyString(), anyInt(), anyInt(), isNull() ) ).thenReturn( List.of() );
        tool.execute( Map.of() );
        verify( report ).pages( "wiki.example.com", 28, 25, null );
        tool.execute( Map.of( "days", 500, "limit", 9999 ) );
        verify( report ).pages( "wiki.example.com", 90, 200, null );
    }

    @Test
    void passesClusterAndRendersPagesWithRefreshedAt() {
        when( report.pages( "wiki.example.com", 28, 25, "agent-cookbook" ) ).thenReturn( List.of(
                new PageReaderStats( "ID1", "Alpha", 10, 7, 30.0, null, 0.5, Map.of( "direct", 4 ),
                        0.25, 0.1, 2100.0, null, null ) ) );
        when( report.refreshedAt( "wiki.example.com" ) ).thenReturn( Instant.parse( "2026-11-20T05:00:00Z" ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "cluster", "agent-cookbook" ) );
        assertTrue( !Boolean.TRUE.equals( r.isError() ) );
        final JsonObject b = JsonParser.parseString( text( r ) ).getAsJsonObject();
        assertEquals( 1, b.get( "count" ).getAsInt() );
        assertEquals( "2026-11-20T05:00:00Z", b.get( "refreshedAt" ).getAsString() );
        final JsonObject p = b.getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject();
        assertEquals( "Alpha", p.get( "slug" ).getAsString() );
        assertEquals( 30.0, p.get( "typical_active_s" ).getAsDouble() );
        assertEquals( 0.1, p.get( "returning_this_month_rate" ).getAsDouble() );
    }

    @Test
    void refusesWhenReportThrows() {
        when( report.pages( anyString(), anyInt(), anyInt(), isNull() ) ).thenThrow( new IllegalStateException( "boom" ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of() );
        assertTrue( r.isError() );
    }

    @Test
    void numericStringArgumentsAreParsedGarbageFallsBackAndTheBoundsAreInclusive() {
        when( report.pages( anyString(), anyInt(), anyInt(), isNull() ) ).thenReturn( List.of() );

        tool.execute( Map.of( "days", " 7 ", "limit", "10" ) );
        verify( report ).pages( "wiki.example.com", 7, 10, null );

        tool.execute( Map.of( "days", "soon", "limit", "many" ) );
        verify( report ).pages( "wiki.example.com", 28, 25, null );

        tool.execute( Map.of( "days", 0, "limit", 0 ) );
        verify( report ).pages( "wiki.example.com", 1, 1, null );

        tool.execute( Map.of( "days", 90, "limit", 200 ) );
        verify( report ).pages( "wiki.example.com", 90, 200, null );
    }

    @Test
    void aBlankClusterMeansNoClusterFilterAndAClusterIsStripped() {
        when( report.pages( anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.any() ) ).thenReturn( List.of() );

        tool.execute( Map.of( "cluster", "   " ) );
        verify( report ).pages( "wiki.example.com", 28, 25, null );

        tool.execute( Map.of( "cluster", " agents " ) );
        verify( report ).pages( "wiki.example.com", 28, 25, "agents" );
    }

    @Test
    void anUnknownRefreshTimeIsReportedAsNullAndTheDaysAreEchoed() {
        when( report.pages( anyString(), anyInt(), anyInt(), isNull() ) ).thenReturn( List.of() );
        when( report.refreshedAt( "wiki.example.com" ) ).thenReturn( null );

        final JsonObject b = JsonParser.parseString( text( tool.execute( Map.of( "days", 14 ) ) ) ).getAsJsonObject();

        assertEquals( 14, b.get( "days" ).getAsInt() );
        assertEquals( 0, b.get( "count" ).getAsInt() );
        assertTrue( b.get( "refreshedAt" ).isJsonNull() || !b.has( "refreshedAt" ) );
    }
}
