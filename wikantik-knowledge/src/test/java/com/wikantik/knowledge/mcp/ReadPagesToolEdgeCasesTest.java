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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Boundary, argument-handling and metrics behaviour of {@code read_pages} that the happy-path tests do not pin. */
class ReadPagesToolEdgeCasesTest {

    private static final String COUNTER = "wikantik_read_pages_partial_failures_total";

    private PageManager pageManager;
    private SimpleMeterRegistry registry;
    private ReadPagesTool tool;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        registry = new SimpleMeterRegistry();
        final ReadPagesMetrics metrics = new ReadPagesMetrics();
        metrics.bind( registry );
        tool = new ReadPagesTool( pageManager, metrics );
    }

    private static JsonArray pages( final McpSchema.CallToolResult result ) {
        final String json = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        return new Gson().fromJson( json, JsonObject.class ).getAsJsonArray( "pages" );
    }

    private void page( final String slug, final String body ) {
        when( pageManager.getPageWithoutMetadata( eq( slug ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pageManager.getPureText( eq( slug ), anyInt() ) ).thenReturn( body );
    }

    private double count( final String reason ) {
        final var c = registry.find( COUNTER ).tag( "reason", reason ).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void exactlyTwentySlugsAreAccepted() {
        final List< String > slugs = new ArrayList<>();
        for ( int i = 0; i < 20; i++ ) {
            slugs.add( "P" + i );
            page( "P" + i, "body" + i );
        }
        final var result = tool.execute( Map.of( "slugs", slugs ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        assertEquals( 20, pages( result ).size() );
    }

    @Test
    void twentyOneSlugsAreRefusedAndNothingIsRead() {
        final List< String > slugs = new ArrayList<>();
        for ( int i = 0; i < 21; i++ ) slugs.add( "P" + i );
        final var result = tool.execute( Map.of( "slugs", slugs ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
        verifyNoInteractions( pageManager );
    }

    @Test
    void missingSlugsArgumentIsRefused() {
        final var result = tool.execute( Map.of() );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
        assertTrue( ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text().contains( "slugs" ) );
    }

    @Test
    void nullAndBlankEntriesAreSkippedAndTheRestKeepInputOrder() {
        page( "B", "bee" );
        page( "A", "ay" );
        final var result = tool.execute( Map.of( "slugs", Arrays.asList( null, " B ", "   ", "A", "B" ) ) );
        final JsonArray out = pages( result );
        assertEquals( 2, out.size() );
        assertEquals( "B", out.get( 0 ).getAsJsonObject().get( "slug" ).getAsString() );
        assertEquals( "bee", out.get( 0 ).getAsJsonObject().get( "content" ).getAsString() );
        assertEquals( "A", out.get( 1 ).getAsJsonObject().get( "slug" ).getAsString() );
    }

    @Test
    void aListOfOnlyBlankEntriesIsRefused() {
        final var result = tool.execute( Map.of( "slugs", Arrays.asList( null, "", "  " ) ) );
        assertTrue( Boolean.TRUE.equals( result.isError() ) );
        verifyNoInteractions( pageManager );
    }

    @Test
    void aPageWhoseTextIsNullIsReturnedWithEmptyContent() {
        page( "Empty", null );
        final JsonObject entry = pages( tool.execute( Map.of( "slugs", List.of( "Empty" ) ) ) )
                .get( 0 ).getAsJsonObject();
        assertEquals( "", entry.get( "content" ).getAsString() );
        assertFalse( entry.has( "error" ) );
    }

    @Test
    void aMissingPageReportsNotFoundWithNullContentAndCountsIt() {
        when( pageManager.getPageWithoutMetadata( eq( "Gone" ), anyInt() ) ).thenReturn( null );
        final JsonObject entry = pages( tool.execute( Map.of( "slugs", List.of( "Gone" ) ) ) )
                .get( 0 ).getAsJsonObject();
        assertEquals( "not_found", entry.get( "error" ).getAsString() );
        assertTrue( !entry.has( "content" ) || entry.get( "content" ).isJsonNull() );
        assertEquals( 1.0, count( "not_found" ), 0.0 );
        assertEquals( 0.0, count( "internal_error" ), 0.0 );
    }

    @Test
    void aViewGateRefusalIsCountedAsNotFound() {
        final ReadPagesMetrics metrics = new ReadPagesMetrics();
        metrics.bind( registry );
        final ReadPagesTool gated = new ReadPagesTool( pageManager, metrics, slug -> false );
        final JsonObject entry = pages( gated.execute( Map.of( "slugs", List.of( "Hidden" ) ) ) )
                .get( 0 ).getAsJsonObject();
        assertEquals( "not_found", entry.get( "error" ).getAsString() );
        assertEquals( 1.0, count( "not_found" ), 0.0 );
        verifyNoInteractions( pageManager );
    }

    @Test
    void anInternalFailureIsReportedAndCountedUnderItsOwnReason() {
        when( pageManager.getPageWithoutMetadata( eq( "Boom" ), anyInt() ) ).thenThrow( new IllegalStateException( "x" ) );
        final JsonObject entry = pages( tool.execute( Map.of( "slugs", List.of( "Boom" ) ) ) )
                .get( 0 ).getAsJsonObject();
        assertEquals( "internal_error", entry.get( "error" ).getAsString() );
        assertTrue( !entry.has( "content" ) || entry.get( "content" ).isJsonNull() );
        assertEquals( 1.0, count( "internal_error" ), 0.0 );
        assertEquals( 0.0, count( "not_found" ), 0.0 );
    }

    @Test
    void aNullViewGateMeansEveryPageIsReadable() {
        page( "Open", "open body" );
        final ReadPagesTool open = new ReadPagesTool( pageManager, null, null );
        final JsonObject entry = pages( open.execute( Map.of( "slugs", List.of( "Open" ) ) ) )
                .get( 0 ).getAsJsonObject();
        assertEquals( "open body", entry.get( "content" ).getAsString() );
    }

    @Test
    void nullMetricsDoNotBreakAFailurePath() {
        final ReadPagesTool noMetrics = new ReadPagesTool( pageManager, null );
        when( pageManager.getPageWithoutMetadata( eq( "Gone" ), anyInt() ) ).thenReturn( null );
        when( pageManager.getPageWithoutMetadata( eq( "Boom" ), anyInt() ) ).thenThrow( new IllegalStateException( "x" ) );
        final JsonArray out = pages( noMetrics.execute( Map.of( "slugs", List.of( "Gone", "Boom" ) ) ) );
        assertEquals( "not_found", out.get( 0 ).getAsJsonObject().get( "error" ).getAsString() );
        assertEquals( "internal_error", out.get( 1 ).getAsJsonObject().get( "error" ).getAsString() );
    }
}
