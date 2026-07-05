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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BriefingResourceTest {

    private static HttpServletRequest req( final String pins, final String clusters,
                                           final String prompt, final String budget,
                                           final String scopeMode, final String format ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "pins" ) ).thenReturn( pins );
        when( req.getParameter( "clusters" ) ).thenReturn( clusters );
        when( req.getParameter( "prompt" ) ).thenReturn( prompt );
        when( req.getParameter( "budget" ) ).thenReturn( budget );
        when( req.getParameter( "scope_mode" ) ).thenReturn( scopeMode );
        when( req.getParameter( "format" ) ).thenReturn( format );
        return req;
    }

    /* (a) no params -> 400 */
    @Test
    void noParams_returns_400() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
        };
        final HttpServletRequest request = req( null, null, null, null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 400 );
    }

    /* (b) pins=A happy path -> JSON contains items + item A */
    @Test
    void pinsHappyPath_returnsItemsJson() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingItem itemA = new BriefingItem( "A", "01A", "Page A", "summary a",
                "pin", true, "content a" );
        final ContextBriefing briefing = new ContextBriefing( null, List.of(), BundleCoverage.empty(),
                List.of( itemA ), List.of(), 4000, 100 );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );
            }
        };
        final HttpServletRequest request = req( "A", null, null, null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertTrue( body.has( "items" ) );
        assertEquals( 1, body.getAsJsonArray( "items" ).size() );
        assertEquals( "A", body.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject()
                .get( "slug" ).getAsString() );
    }

    /* (c) format=md -> content-type text/markdown, body starts with the renderer's heading */
    @Test
    void formatMd_returnsMarkdown() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingItem itemA = new BriefingItem( "A", "01A", "Page A", "summary a",
                "pin", true, "content a" );
        final ContextBriefing briefing = new ContextBriefing( null, List.of(), BundleCoverage.empty(),
                List.of( itemA ), List.of(), 4000, 100 );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );
            }
        };
        final HttpServletRequest request = req( "A", null, null, null, null, "md" );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 200 );
        verify( resp ).setContentType( "text/markdown; charset=UTF-8" );
        assertTrue( sw.toString().startsWith( "# Wiki context briefing" ) );
    }

    /* (d) service null -> 503 */
    @Test
    void serviceUnavailable_returns_503() throws Exception {
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return null; }
        };
        final HttpServletRequest request = req( "A", null, null, null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 503 );
    }

    /* (e) bad scope_mode -> 400 */
    @Test
    void badScopeMode_returns_400() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
        };
        final HttpServletRequest request = req( "A", null, null, null, "bogus", null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 400 );
    }

    /* (f) ACL: section slug filtered out -> coverage recounted */
    @Test
    void aclGate_dropsRestrictedSection_andRecountsCoverage() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BundleSection pub = new BundleSection( "01PUB", "PublicPage", List.of( "Intro" ),
                "public text", 0.9, new CitationHandle( "01PUB", 1, List.of( "Intro" ), "public text", "aaa" ) );
        final BundleSection sec = new BundleSection( "01SEC", "SecretPage", List.of( "Keys" ),
                "TOP SECRET", 0.8, new CitationHandle( "01SEC", 1, List.of( "Keys" ), "TOP SECRET", "bbb" ) );
        final BundleCoverage cov = new BundleCoverage( 2, 2, 0.9, BundleCoverage.STRONG );
        final ContextBriefing briefing = new ContextBriefing( "deploy", List.of( pub, sec ), cov,
                List.of(), List.of(), 4000, 100 );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                final java.util.Set< String > out = new java.util.HashSet<>( names );
                out.remove( "SecretPage" );
                return out;
            }
        };
        final HttpServletRequest request = req( null, null, "deploy", null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( request, resp );

        verify( resp ).setStatus( 200 );
        final String body = sw.toString();
        assertTrue( body.contains( "public text" ) );
        assertTrue( !body.contains( "TOP SECRET" ), "restricted section text must not leak" );
        final JsonObject obj = JsonParser.parseString( body ).getAsJsonObject();
        assertEquals( 1, obj.getAsJsonArray( "sections" ).size() );
        assertEquals( 1, obj.getAsJsonObject( "coverage" ).get( "sectionCount" ).getAsInt() );
    }

    /* (f2) ACL: dropped-restricted pin surfaces the same "unknown pin" warning as a nonexistent one */
    @Test
    void aclGate_droppedRestrictedPin_getsUnknownPinWarning() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingItem secret = new BriefingItem( "SecretPage", "01SEC", "Secret", "s",
                "pin", true, "TOP SECRET" );
        final ContextBriefing briefing = new ContextBriefing( null, List.of(), BundleCoverage.empty(),
                List.of( secret ), List.of(), 4000, 100 );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                final java.util.Set< String > out = new java.util.HashSet<>( names );
                out.remove( "SecretPage" );
                return out;
            }
        };
        final HttpServletRequest request = req( "SecretPage", null, null, null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( request, resp );

        final String body = sw.toString();
        assertTrue( !body.contains( "TOP SECRET" ), "restricted pin body must not leak" );
        final JsonObject obj = JsonParser.parseString( body ).getAsJsonObject();
        assertEquals( 0, obj.getAsJsonArray( "items" ).size(), "restricted pin dropped" );
        assertTrue( obj.getAsJsonArray( "warnings" ).toString().contains( "unknown pin: SecretPage" ),
                "dropped-restricted pin must warn like a nonexistent one: " + body );
    }

    /* (g) briefing log receives entry with surface == "api_briefing" */
    @Test
    void briefingLog_receivesEntryWithApiBriefingSurface() throws Exception {
        final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
        final BriefingItem itemA = new BriefingItem( "A", "01A", "Page A", "summary a",
                "pin", true, "content a" );
        final BriefingItem pointerB = new BriefingItem( "B", "01B", "Page B", "summary b",
                "cluster", false, null );
        final ContextBriefing briefing = new ContextBriefing( "deploy", List.of(), BundleCoverage.empty(),
                List.of( itemA, pointerB ), List.of(), 4000, 100 );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( briefing );
        final QueryLogService qlog = mock( QueryLogService.class );
        final BriefingLogService blog = mock( BriefingLogService.class );
        final BriefingResource resource = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return svc; }
            @Override protected QueryLogService queryLogService() { return qlog; }
            @Override protected BriefingLogService briefingLogService() { return blog; }
            @Override protected ActorType actorType( final HttpServletRequest r ) { return ActorType.AGENT; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );
            }
        };
        final HttpServletRequest request = req( "A", "cluster1", "deploy", null, null, null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( request, resp );

        final ArgumentCaptor< BriefingLogEntry > captor = ArgumentCaptor.forClass( BriefingLogEntry.class );
        verify( blog ).log( captor.capture() );
        final BriefingLogEntry entry = captor.getValue();
        assertEquals( "api_briefing", entry.surface() );
        assertEquals( 1, entry.pinCount() );
        assertEquals( 1, entry.pointerCount() );
        assertTrue( entry.promptPresent() );

        verify( qlog ).log( "deploy", ActorType.AGENT, SourceSurface.API_BRIEFING, 0 );
    }
}
