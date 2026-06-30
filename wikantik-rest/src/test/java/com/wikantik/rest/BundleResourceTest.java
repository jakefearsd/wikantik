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
import com.wikantik.WikiEngine;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.core.Engine;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.knowledge.bundle.HybridChunkSectionSource;
import com.wikantik.knowledge.bundle.SectionCandidateSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.api.bundle.BundleCoverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BundleResourceTest {

    @Test
    void missing_q_returns_400() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( null );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
    }

    @Test
    void unavailable_service_returns_503() throws Exception {
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return null; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "something" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
    }

    @Test
    void happy_path_serialises_bundle() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );   // ACL not under test here — allow all
            }
        };
        final ContextBundle bundle = new ContextBundle( "deploy", List.of(
                new BundleSection( "01DEP", "DeployGuide", List.of( "Setup" ), "do x", 0.9,
                        new CitationHandle( "01DEP", 7, List.of( "Setup" ), "do x", "abc123" ) ) ) );
        when( svc.assemble( eq( "deploy" ), any( RetrievalMode.class ) ) ).thenReturn( bundle );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "deploy", body.get( "query" ).getAsString() );
        assertEquals( 1, body.getAsJsonArray( "sections" ).size() );
        final JsonObject section = body.getAsJsonArray( "sections" ).get( 0 ).getAsJsonObject();
        assertEquals( "01DEP", section.get( "canonicalId" ).getAsString() );
        assertEquals( "Setup", section.getAsJsonArray( "headingPath" ).get( 0 ).getAsString() );
        final JsonObject citation = section.getAsJsonObject( "citation" );
        assertEquals( 7,       citation.get( "version" ).getAsInt() );
        assertEquals( "abc123", citation.get( "spanSha256" ).getAsString() );
    }

    @Test
    void restrictedSection_filteredForCaller() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final ContextBundle bundle = new ContextBundle( "deploy", List.of(
                new BundleSection( "01PUB", "DeployGuide", List.of( "Setup" ), "public step", 0.9,
                        new CitationHandle( "01PUB", 7, List.of( "Setup" ), "public step", "aaa" ) ),
                new BundleSection( "01SEC", "DeploySecret", List.of( "Keys" ), "TOP SECRET STEP", 0.8,
                        new CitationHandle( "01SEC", 3, List.of( "Keys" ), "TOP SECRET STEP", "bbb" ) ) ) );
        when( svc.assemble( eq( "deploy" ), any( RetrievalMode.class ) ) ).thenReturn( bundle );
        // The caller may view everything except the restricted page (DeploySecret).
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                final java.util.Set< String > out = new java.util.HashSet<>( names );
                out.remove( "DeploySecret" );
                return out;
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final String body = sw.toString();
        assertFalse( body.contains( "TOP SECRET STEP" ),
                "restricted section text must not leak through /api/bundle" );
        assertTrue( body.contains( "public step" ), "viewable section should remain" );
        assertEquals( 1, JsonParser.parseString( body ).getAsJsonObject()
                .getAsJsonArray( "sections" ).size() );
    }

    @Test
    void doGet_logsQuery_onSuccessfulBundle() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final ContextBundle bundle = new ContextBundle( "deploy", List.of(
                new BundleSection( "01DEP", "DeployGuide", List.of( "Setup" ), "do x", 0.9,
                        new CitationHandle( "01DEP", 7, List.of( "Setup" ), "do x", "abc123" ) ) ) );
        when( svc.assemble( eq( "deploy" ), any( RetrievalMode.class ) ) ).thenReturn( bundle );
        final QueryLogService qlog = mock( QueryLogService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected QueryLogService queryLogService() { return qlog; }
            @Override protected ActorType actorType( final HttpServletRequest r ) { return ActorType.HUMAN; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );   // ACL not under test here — allow all
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        // the returned section count is the result_count signal; api_bundle surface; classified actor
        verify( qlog ).log( "deploy", ActorType.HUMAN, SourceSurface.API_BUNDLE, 1 );
    }

    @Test
    void doGet_doesNotLog_whenServiceUnavailable() throws Exception {
        final QueryLogService qlog = mock( QueryLogService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return null; }   // 503
            @Override protected QueryLogService queryLogService() { return qlog; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verifyNoInteractions( qlog );   // no assemble happened → nothing to log
    }

    @Test
    void assembleThrows_returns_500() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        when( svc.assemble( eq( "boom" ), any( RetrievalMode.class ) ) ).thenThrow( new RuntimeException( "kaboom" ) );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "boom" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 500 );
    }

    /* ---------- debug=rankings ---------- */

    private static BundleResource resourceWithEngine( final Engine engine ) {
        return new BundleResource() {
            @Override protected Engine getEngine() { return engine; }
        };
    }

    private static HttpServletRequest debugReq( final String q, final String k ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "q" ) ).thenReturn( q );
        when( req.getParameter( "debug" ) ).thenReturn( "rankings" );
        when( req.getParameter( "k" ) ).thenReturn( k );
        return req;
    }

    @Test
    void debugRankings_engineNotWikiEngine_returns_409() throws Exception {
        // getEngine() not a WikiEngine → no chunk-hybrid source → 409.
        final BundleResource resource = resourceWithEngine( null );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( debugReq( "foo", null ), resp );

        verify( resp ).setStatus( 409 );
    }

    @Test
    void debugRankings_nonHybridSource_returns_409() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        // a plain dense source (the shipped default) is neither hybrid nor injection → 409.
        when( engine.bundleSectionSources() ).thenReturn(
            Map.of( RetrievalMode.HYBRID, mock( SectionCandidateSource.class ) ) );
        final BundleResource resource = resourceWithEngine( engine );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( debugReq( "foo", null ), resp );

        verify( resp ).setStatus( 409 );
    }

    @Test
    void debugRankings_hybridSource_returns_200_andForwardsCustomK() throws Exception {
        final HybridChunkSectionSource hybrid = mock( HybridChunkSectionSource.class );
        final Map< String, List< HybridChunkSectionSource.DebugRank > > ranks = new LinkedHashMap<>();
        ranks.put( "dense", List.of( new HybridChunkSectionSource.DebugRank( "id1", 0.9 ) ) );
        ranks.put( "bm25", List.of( new HybridChunkSectionSource.DebugRank( "id2", 1.0 ) ) );
        when( hybrid.debugRankings( "foo", 10 ) ).thenReturn( ranks );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.bundleSectionSources() ).thenReturn( Map.of( RetrievalMode.HYBRID, hybrid ) );
        final BundleResource resource = resourceWithEngine( engine );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( debugReq( "foo", "10" ), resp );

        verify( resp ).setStatus( 200 );
        verify( hybrid ).debugRankings( "foo", 10 );   // custom k parsed and forwarded
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertTrue( body.has( "dense" ) );
        assertTrue( body.has( "bm25" ) );
    }

    @Test
    void debugRankings_defaultK_is500() throws Exception {
        final HybridChunkSectionSource hybrid = mock( HybridChunkSectionSource.class );
        when( hybrid.debugRankings( "foo", 500 ) ).thenReturn( new LinkedHashMap<>() );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.bundleSectionSources() ).thenReturn( Map.of( RetrievalMode.HYBRID, hybrid ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resourceWithEngine( engine ).doGet( debugReq( "foo", null ), resp );

        verify( hybrid ).debugRankings( "foo", 500 );   // no k param → default 500
    }

    @Test
    void debugRankings_malformedK_keepsDefault500() throws Exception {
        final HybridChunkSectionSource hybrid = mock( HybridChunkSectionSource.class );
        when( hybrid.debugRankings( "foo", 500 ) ).thenReturn( new LinkedHashMap<>() );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.bundleSectionSources() ).thenReturn( Map.of( RetrievalMode.HYBRID, hybrid ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resourceWithEngine( engine ).doGet( debugReq( "foo", "abc" ), resp );

        verify( hybrid ).debugRankings( "foo", 500 );   // unparseable k → default 500
    }

    /* ---------- coverage block ---------- */

    @Test
    void responseIncludesCoverageBlock() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        // Three sections so the strong coverage survives the >=3 floor check in recount.
        final List< BundleSection > sections = List.of(
                new BundleSection( "01A", "PageA", List.of( "Intro" ), "text a", 0.9,
                        new CitationHandle( "01A", 1, List.of( "Intro" ), "text a", "sha1" ) ),
                new BundleSection( "01B", "PageB", List.of( "Intro" ), "text b", 0.7,
                        new CitationHandle( "01B", 2, List.of( "Intro" ), "text b", "sha2" ) ),
                new BundleSection( "01C", "PageC", List.of( "Body" ), "text c", 0.6,
                        new CitationHandle( "01C", 3, List.of( "Body" ), "text c", "sha3" ) ) );
        final BundleCoverage cov = new BundleCoverage( 3, 3, 0.6, BundleCoverage.STRONG );
        when( svc.assemble( eq( "anything" ), any( RetrievalMode.class ) ) )
                .thenReturn( new ContextBundle( "anything", sections, cov ) );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );   // allow all — ACL not under test
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "anything" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject obj = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertTrue( obj.has( "coverage" ), "response must include a coverage block" );
        assertEquals( "strong", obj.getAsJsonObject( "coverage" ).get( "confidence" ).getAsString() );
    }

    /* ---------- mode parameter ---------- */

    @Test
    void mode_lexical_routes_to_assemble_with_LEXICAL() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final ContextBundle bundle = new ContextBundle( "query", List.of() );
        when( svc.assemble( eq( "query" ), eq( RetrievalMode.LEXICAL ) ) ).thenReturn( bundle );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "query" );
        when( req.getParameter( "mode" ) ).thenReturn( "lexical" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( svc ).assemble( "query", RetrievalMode.LEXICAL );
        verify( resp ).setStatus( 200 );
    }

    @Test
    void invalid_mode_returns_400_listing_valid_modes() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "query" );
        when( req.getParameter( "mode" ) ).thenReturn( "bogus" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        final String body = sw.toString();
        assertTrue( body.contains( "hybrid" ) && body.contains( "dense" ) && body.contains( "lexical" ),
                "400 body must list valid mode values; got: " + body );
        verifyNoInteractions( svc );
    }

    @Test
    void no_mode_routes_to_assemble_with_HYBRID() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final ContextBundle bundle = new ContextBundle( "query", List.of() );
        when( svc.assemble( eq( "query" ), eq( RetrievalMode.HYBRID ) ) ).thenReturn( bundle );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
            @Override protected java.util.Set< String > filterViewable(
                    final HttpServletRequest r, final java.util.Collection< String > names ) {
                return new java.util.HashSet<>( names );
            }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "query" );
        when( req.getParameter( "mode" ) ).thenReturn( null );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( svc ).assemble( "query", RetrievalMode.HYBRID );
        verify( resp ).setStatus( 200 );
    }
}
