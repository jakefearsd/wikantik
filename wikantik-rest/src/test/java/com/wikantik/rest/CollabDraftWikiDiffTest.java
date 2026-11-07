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
import com.wikantik.TestEngine;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/collab/{page}/draft} on the PRODUCTION diff path: {@link CollabResource#init} wires the wiki's own
 * {@code DifferenceManager} (a real {@link TestEngine}), so the {@code diff} the editor shows with
 * {@code dangerouslySetInnerHTML} is pinned as escaped, for a page that exists and for one that does not exist yet.
 */
class CollabDraftWikiDiffTest {

    private static final String SCRIPT = "<script>alert(1)</script>";

    private TestEngine engine;
    private ScriptedPageWriter writer;
    private CollabSessionRegistry registry;
    private CollabResource resource;

    private final CollabPermissions allow = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private final CollabResource.Access access = new CollabResource.Access() {
        @Override
        public CollabResource.Caller caller( final HttpServletRequest request ) {
            return new CollabResource.Caller( "ada", "ada" );
        }

        @Override
        public boolean mayView( final CollabResource.Caller caller, final String pageName ) {
            return true;
        }

        @Override
        public boolean mayEdit( final CollabResource.Caller caller, final String pageName, final boolean create ) {
            return true;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        writer = new ScriptedPageWriter();
        registry = new CollabSessionRegistry( new InMemoryCollabStore(), writer, allow, k -> k,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ) );
        resource = new CollabResource( registry, access, ( ms, task ) -> task.run(), 0 );
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        resource.init( config );   // no differ handed in: the production one, over the engine's DifferenceManager
    }

    @AfterEach
    void tearDown() {
        resource.destroy();
        engine.deleteQuietly( "CollabExistingPage" );
        engine.stop();
    }

    /** Joins {@code page}, types {@code typed} at the end and fetches the draft summary. */
    private JsonObject draftAfterTyping( final String page, final String typed ) throws Exception {
        final CollabSessionRegistry.Joined joined = ( CollabSessionRegistry.Joined ) registry.join( page,
            JoinRequest.of( "ada", "ada", null ) );
        final CollabSession s = joined.session();
        final JoinResponse ada = joined.response();
        final int len = s.view().doc().length();
        assertTrue( s.push( ada.participantId(), "ada", 0,
            List.of( new Update( ada.clientId(), ChangeSet.replace( len, len, len, typed ) ) ) ).ok() );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getMethod() ).thenReturn( "GET" );
        when( req.getPathInfo() ).thenReturn( "/" + page + "/draft" );
        when( req.getRequestURI() ).thenReturn( "/api/collab/" + page + "/draft" );
        final Map< String, String > q = Map.of( "sessionId", s.id().toString(), "participantId", ada.participantId() );
        when( req.getParameter( anyString() ) ).thenAnswer( i -> q.get( i.< String >getArgument( 0 ) ) );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter out = new StringWriter();
        final int[] status = { 200 };
        when( resp.getWriter() ).thenReturn( new PrintWriter( out, true ) );
        doAnswer( i -> {
            status[ 0 ] = i.getArgument( 0 );
            return null;
        } ).when( resp ).setStatus( anyInt() );
        resource.doGet( req, resp );
        assertEquals( 200, status[ 0 ], () -> "draft summary: " + out );
        return JsonParser.parseString( out.toString() ).getAsJsonObject();
    }

    private void seed( final String page, final String body ) {
        writer.seed( page, body, Map.of() );
    }

    private void assertEscaped( final String diff ) {
        assertFalse( diff.isEmpty(), "the text changed: a diff is rendered" );
        assertFalse( diff.contains( "<script" ), () -> "no live markup from the page text: " + diff );
        assertTrue( diff.contains( "&lt;script&gt;" ), () -> "the page text is escaped: " + diff );
    }

    @Test
    void theRealDiffOfAPageThatDoesNotExistYetIsEscaped() throws Exception {
        seed( "CollabNewPage", "Intro " + SCRIPT );   // known to the draft's writer only, never saved in the wiki
        assertFalse( engine.getManager( com.wikantik.api.managers.PageManager.class ).wikiPageExists( "CollabNewPage" ) );
        assertEscaped( draftAfterTyping( "CollabNewPage", " more " + SCRIPT ).get( "diff" ).getAsString() );
    }

    @Test
    void theRealDiffOfAnExistingPageIsEscaped() throws Exception {
        engine.saveText( "CollabExistingPage", "Intro " + SCRIPT );
        seed( "CollabExistingPage", "Intro " + SCRIPT );
        assertEscaped( draftAfterTyping( "CollabExistingPage", " more " + SCRIPT ).get( "diff" ).getAsString() );
    }
}
