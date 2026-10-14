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
package com.wikantik.tools;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code search_wiki} must apply the same view-permission rule {@code get_page} applies: a page the
 * caller cannot view is dropped from the results (titles, summaries, snippets and related-page
 * references alike). Runs against a real engine with real inline ACLs and real sessions; only the
 * retrieval service is stubbed, because the ranking is not under test.
 *
 * <p>Fixture notes: a non-empty ACL is deny-by-default, and {@code edit} implies {@code view}, so
 * {@code [{ALLOW view Admin}]} hides a page from every non-admin and {@code [{ALLOW edit Admin}]}
 * does the same while still letting an admin see it.</p>
 */
class SearchWikiToolViewPermissionTest {

    private static TestEngine testEngine;

    private ContextRetrievalService ctxService;
    private SearchWikiTool tool;

    @BeforeAll
    static void startEngine() {
        testEngine = TestEngine.build();
    }

    @AfterAll
    static void cleanWorkDir() {
        TestEngine.emptyWorkDir();
    }

    @BeforeEach
    void setUp() throws Exception {
        testEngine.saveText( "VpPublicOne", "open page one" );
        testEngine.saveText( "VpPublicTwo", "open page two" );
        testEngine.saveText( "VpPublicThree", "open page three" );
        testEngine.saveText( "VpSecret", "[{ALLOW view Admin}]\n\nclassified body" );
        testEngine.saveText( "VpEditLocked", "[{ALLOW edit Admin}]\n\nedit-locked body" );

        ctxService = mock( ContextRetrievalService.class );
        testEngine.setManager( ContextRetrievalService.class, ctxService );
        tool = new SearchWikiTool( testEngine, new ToolsConfig( new Properties() ) );
    }

    @AfterEach
    void tearDown() {
        testEngine.deleteTestPage( "VpPublicOne" );
        testEngine.deleteTestPage( "VpPublicTwo" );
        testEngine.deleteTestPage( "VpPublicThree" );
        testEngine.deleteTestPage( "VpSecret" );
        testEngine.deleteTestPage( "VpEditLocked" );
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static RetrievedPage hit( final String name, final RelatedPage... related ) {
        return RetrievedPage.builder( name, 1.0 )
                .url( "" )
                .summary( "summary of " + name )
                .tags( List.of() )
                .contributingChunks( List.of( new RetrievedChunk( List.of( name ), "snippet of " + name, 0.9, List.of() ) ) )
                .relatedPages( List.of( related ) )
                .build();
    }

    private void retrievalReturns( final RetrievedPage... pages ) {
        when( ctxService.retrieve( any( ContextQuery.class ) ) )
                .thenReturn( new RetrievalResult( "q", List.of( pages ), pages.length ) );
    }

    /** A request carrying its own HttpSession, so it resolves to a fresh anonymous WikiSession. */
    private static HttpServletRequest anonymousRequest() {
        return HttpMockFactory.createIsolatedHttpRequest( "/tools/search_wiki" );
    }

    private static HttpServletRequest adminRequest() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/tools/search_wiki" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return request;
    }

    /** Mirrors production: ToolsAccessFilter hands the servlet a stateless API-key request. */
    private static HttpServletRequest apiKeyRequest() {
        final HttpServletRequest bare = mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( bare ).getSession( false );
        Mockito.doReturn( null ).when( bare ).getCookies();
        Mockito.doReturn( Locale.ROOT ).when( bare ).getLocale();
        Mockito.doReturn( "127.0.0.1" ).when( bare ).getRemoteAddr();
        return new ApiKeyPrincipalRequest( bare, "keyowner" );
    }

    @SuppressWarnings( "unchecked" )
    private static List< Map< String, Object > > results( final Map< String, Object > out ) {
        return ( List< Map< String, Object > > ) out.get( "results" );
    }

    private static List< Object > names( final Map< String, Object > out ) {
        return results( out ).stream().map( r -> r.get( "name" ) ).toList();
    }

    // ---- tests ----------------------------------------------------------------------------

    @Test
    void restrictedPageIsAbsentForAnonymousCallerAndOrderIsPreserved() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ) );
        assertEquals( 2, out.get( "total" ), "total counts only what the caller may see" );
        final String serialized = out.toString();
        assertFalse( serialized.contains( "VpSecret" ), "a restricted title must not appear anywhere: " + serialized );
        assertFalse( serialized.contains( "summary of VpSecret" ) );
        assertFalse( serialized.contains( "snippet of VpSecret" ) );
    }

    @Test
    void restrictedPageIsPresentForAdministrator() throws Exception {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, adminRequest() );

        assertEquals( List.of( "VpPublicOne", "VpSecret", "VpPublicTwo" ), names( out ) );
        assertEquals( 3, out.get( "total" ) );
    }

    @Test
    void editRestrictedPageFollowsTheSameRule() throws Exception {
        retrievalReturns( hit( "VpEditLocked" ), hit( "VpPublicOne" ) );

        assertEquals( List.of( "VpPublicOne" ), names( tool.execute( "q", 10, anonymousRequest() ) ) );
        assertEquals( List.of( "VpEditLocked", "VpPublicOne" ), names( tool.execute( "q", 10, adminRequest() ) ) );
    }

    @Test
    void unrestrictedPagesAreUnaffected() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpPublicTwo" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ) );
        assertEquals( 2, out.get( "total" ) );
        assertEquals( "snippet of VpPublicOne", results( out ).get( 0 ).get( "snippet" ) );
        assertNull( out.get( "error" ) );
    }

    @Test
    void everyResultRestrictedLooksLikeAnEmptySearch() {
        retrievalReturns( hit( "VpSecret" ), hit( "VpEditLocked" ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        assertEquals( List.of(), results( out ) );
        assertEquals( 0, out.get( "total" ) );
        assertNull( out.get( "error" ), "must be indistinguishable from a query with no matches" );
    }

    @Test
    void aHiddenTopRankedHitDoesNotShortenTheResponse() {
        // A retrieval service that honours the page budget it is asked for, as the real one does: if the tool
        // asked for only maxResults (2) pages, the hidden VpSecret would take one of them and the caller would
        // get a shorter response than the corpus can fill - a count oracle for restricted pages.
        final List< RetrievedPage > ranked = List.of( hit( "VpSecret" ), hit( "VpPublicOne" ),
                hit( "VpEditLocked" ), hit( "VpPublicTwo" ), hit( "VpPublicThree" ) );
        when( ctxService.retrieve( any( ContextQuery.class ) ) ).thenAnswer( inv -> {
            final ContextQuery q = inv.getArgument( 0 );
            final List< RetrievedPage > page = ranked.subList( 0, Math.min( q.maxPages(), ranked.size() ) );
            return new RetrievalResult( "q", page, page.size() );
        } );

        final Map< String, Object > out = tool.execute( "q", 2, anonymousRequest() );

        assertEquals( List.of( "VpPublicOne", "VpPublicTwo" ), names( out ),
            "hidden hits ranked inside the top 2 must not shorten the response" );
        assertEquals( 2, out.get( "total" ) );
    }

    @Test
    void restrictedRelatedPagesAreNotRevealedOnVisibleResults() {
        retrievalReturns( hit( "VpPublicOne",
                new RelatedPage( "VpSecret", "shared entities: classified" ),
                new RelatedPage( "VpPublicTwo", "shared entities: open" ) ),
                hit( "VpPublicTwo", new RelatedPage( "VpSecret", "shared entities: classified" ) ) );

        final Map< String, Object > out = tool.execute( "q", 10, anonymousRequest() );

        final Map< String, Object > first = results( out ).get( 0 );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > related = ( List< Map< String, Object > > ) first.get( "relatedPages" );
        assertEquals( 1, related.size() );
        assertEquals( "VpPublicTwo", related.get( 0 ).get( "name" ) );
        assertFalse( results( out ).get( 1 ).containsKey( "relatedPages" ),
                "a result whose only related page is restricted carries no relatedPages key" );
        assertFalse( out.toString().contains( "VpSecret" ) );
        assertFalse( out.toString().contains( "classified" ) );
    }

    @Test
    void restrictedPageIsAbsentForAStatelessApiKeyRequest() {
        retrievalReturns( hit( "VpPublicOne" ), hit( "VpSecret" ) );

        final Map< String, Object > out = tool.execute( "q", 10, apiKeyRequest() );

        assertEquals( List.of( "VpPublicOne" ), names( out ) );
        assertFalse( out.toString().contains( "VpSecret" ) );
    }
}
