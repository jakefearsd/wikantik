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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * #104 on the page API: a save that adds an access rule the wiki cannot read is a 422 naming it; an administrator sees
 * why a page is locked ({@code accessRules}), and nobody else ever gets that key; a locked page is closed to everyone
 * else exactly as any restricted page is.
 */
class PageResourceAccessRulesTest {

    private static final String PAGE = "RestAccessRulesPage";
    private static final String DENY = "[{DENY view Bob}]";

    private static TestEngine engine;
    private static PageResource servlet;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build( TestEngine.with( "wikantik.pageProvider", "VersioningFileProvider" ) );
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @AfterEach
    void tearDown() {
        engine.deleteQuietly( PAGE );
    }

    private record Reply( HttpServletResponse response, String body ) {
        JsonObject json() {
            return new Gson().fromJson( body, JsonObject.class );
        }
    }

    private static HttpServletRequest request( final boolean admin, final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/api/pages/" + PAGE );
        Mockito.doReturn( "/" + PAGE ).when( request ).getPathInfo();
        params.forEach( ( k, v ) -> Mockito.doReturn( v ).when( request ).getParameter( k ) );
        if ( admin ) {
            final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
            engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        }
        return request;
    }

    private static Reply call( final HttpServletRequest request, final String verb ) throws Exception {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        switch ( verb ) {
            case "GET" -> servlet.doGet( request, response );
            case "PUT" -> servlet.doPut( request, response );
            default -> servlet.doPatch( request, response );
        }
        return new Reply( response, sw.toString() );
    }

    private static Reply get( final boolean admin, final Map< String, String > params ) throws Exception {
        return call( request( admin, params ), "GET" );
    }

    private static Reply send( final String verb, final JsonObject body ) throws Exception {
        final HttpServletRequest request = request( true, Map.of() );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        return call( request, verb );
    }

    private static String stored() {
        return engine.getManager( PageManager.class ).getPureText( PAGE, PageProvider.LATEST_VERSION ).replace( "\r\n", "\n" );
    }

    /** M3: the refusal leaves none of this save's warnings in the thread's sinks for the next request on the thread. */
    @Test
    void the422ForAPageOfOpenersIsBounded() throws Exception {
        engine.saveText( PAGE, "Open body.\n" );
        final JsonObject body = new JsonObject();
        // Just under the page API's 256 KB cap: about 35,000 refused openers.
        body.addProperty( "content", "[{deny ".repeat( 35_000 ) + "}]" );

        final Reply reply = send( "PUT", body );

        verify( reply.response() ).setStatus( 422 );
        assertTrue( reply.body().length() < 64 * 1024, "422 body of " + reply.body().length() + " chars" );
        final JsonObject json = reply.json();
        assertEquals( 50, json.getAsJsonArray( "violations" ).size() );
        assertTrue( json.get( "total" ).getAsInt() >= 35_000, reply.body().substring( 0, 200 ) );
    }

    @Test
    void aPutThatAddsADenyRuleIs422WithTheAccessRuleShapeCode() throws Exception {
        engine.saveText( PAGE, "Open body.\n" );
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Open body.\n\n" + DENY + "\n" );

        final Reply reply = send( "PUT", body );

        verify( reply.response() ).setStatus( 422 );
        final JsonObject json = reply.json();
        assertEquals( "access_rule_shape", json.get( "error" ).getAsString(), reply.body() );
        final JsonObject violation = json.getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject();
        assertEquals( "ACCESS_RULE_SHAPE", violation.get( "code" ).getAsString() );
        assertEquals( "DENY", violation.get( "shape" ).getAsString() );
        assertEquals( 3, violation.get( "line" ).getAsInt() );
        assertEquals( DENY, violation.get( "text" ).getAsString() );
        assertTrue( violation.get( "message" ).getAsString().contains( "put it in `code`" ), reply.body() );
        assertEquals( "Open body.\n", stored(), "nothing was written" );
    }

    /** The editor sends the body and the frontmatter fields apart: the line is the body line the editor shows. */
    @Test
    void aPutFromTheEditorReportsTheLineOfTheBodyItSent() throws Exception {
        engine.saveText( PAGE, "Open body.\n" );
        final JsonObject body = new JsonObject();
        body.addProperty( "content", "Intro.\n\n" + DENY + "\n" );
        final JsonObject metadata = new JsonObject();
        metadata.addProperty( "title", "Access rules" );
        metadata.addProperty( "type", "article" );
        metadata.addProperty( "summary", "A page about access rules." );
        body.add( "metadata", metadata );

        final Reply reply = send( "PUT", body );

        verify( reply.response() ).setStatus( 422 );
        final JsonObject violation = reply.json().getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject();
        assertEquals( 3, violation.get( "line" ).getAsInt(), reply.body() );
        assertTrue( violation.get( "message" ).getAsString().contains( "line 3" ), reply.body() );
    }

    @Test
    void aMetadataPatchThatPutsARuleInTheFrontmatterIs422() throws Exception {
        engine.saveText( PAGE, "---\ntype: article\n---\nBody.\n" );
        final JsonObject body = new JsonObject();
        final JsonObject metadata = new JsonObject();
        metadata.addProperty( "summary", "[{ALLOW view Admin}]" );
        body.add( "metadata", metadata );

        final Reply reply = send( "PATCH", body );

        verify( reply.response() ).setStatus( 422 );
        final JsonObject violation = reply.json().getAsJsonArray( "violations" ).get( 0 ).getAsJsonObject();
        assertEquals( "IN_FRONTMATTER", violation.get( "shape" ).getAsString(), reply.body() );
        assertEquals( "summary", violation.get( "field" ).getAsString(), reply.body() );
        assertFalse( violation.has( "line" ), "a frontmatter field has no body line: " + reply.body() );
    }

    @Test
    void anAdministratorSeesWhyAPageIsLocked() throws Exception {
        engine.saveTextUnfiltered( PAGE, "---\ntitle: Locked\nsummary: '[{ALLOW view Admin}]'\n---\nIntro.\n\n" + DENY
                + "\n\nBody.\n" );

        final Reply reply = get( true, Map.of() );

        final JsonObject accessRules = reply.json().getAsJsonObject( "accessRules" );
        assertTrue( accessRules.get( "locked" ).getAsBoolean(), reply.body() );
        final JsonArray reasons = accessRules.getAsJsonArray( "reasons" );
        assertEquals( 2, reasons.size(), reply.body() );
        final JsonObject inFrontmatter = reasons.get( 0 ).getAsJsonObject();
        assertEquals( "IN_FRONTMATTER", inFrontmatter.get( "shape" ).getAsString() );
        assertEquals( "summary", inFrontmatter.get( "field" ).getAsString() );
        assertFalse( inFrontmatter.has( "line" ), reply.body() );
        final JsonObject reason = reasons.get( 1 ).getAsJsonObject();
        assertEquals( "DENY", reason.get( "shape" ).getAsString() );
        assertEquals( 3, reason.get( "line" ).getAsInt(), "the body line, below the frontmatter: " + reply.body() );
        assertFalse( reason.has( "field" ), reply.body() );
        assertEquals( DENY, reason.get( "text" ).getAsString() );
    }

    @Test
    void anOpenPageCarriesNoAccessRulesKeyEvenForAnAdministrator() throws Exception {
        engine.saveText( PAGE, "[{ALLOW view All}]\n\nOpen body.\n" );
        assertFalse( get( true, Map.of() ).json().has( "accessRules" ) );
    }

    @Test
    void aNonAdministratorIsRefusedALockedPageAsAnyRestrictedPage() throws Exception {
        engine.saveTextUnfiltered( PAGE, DENY + "\n\nBody.\n" );

        final Reply reply = get( false, Map.of() );

        verify( reply.response() ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        assertFalse( reply.body().contains( "accessRules" ), reply.body() );
        assertFalse( reply.body().contains( "DENY" ), reply.body() );
    }

    /**
     * A viewer of an open page may read an old version whose text would lock the page: an administrator is told so for
     * that version, the viewer is never given the key.
     */
    @Test
    void aViewerWhoIsNoAdministratorNeverGetsTheKey() throws Exception {
        engine.saveText( PAGE, "Open, version one.\n" );
        engine.saveTextUnfiltered( PAGE, "Locked, version two.\n\n" + DENY + "\n" );
        engine.saveText( PAGE, "Open again, version three.\n" );
        final Map< String, String > lockedVersion = Map.of( "version", "2" );

        assertTrue( get( true, lockedVersion ).json().has( "accessRules" ), "control: the administrator is told" );

        final Reply viewer = get( false, lockedVersion );
        final JsonObject json = viewer.json();
        assertTrue( json.get( "content" ).getAsString().contains( "version two" ), "the viewer reads version 2: " + viewer.body() );
        assertFalse( json.has( "accessRules" ), viewer.body() );
    }

    private static final String COMMENT = "%% secret note %%";
    private static final String ORIGINAL = "---\ntitle: Round Trip\ntype: article\n---\n[{ALLOW view All}]\n[{ALLOW edit Admin}]\n\n"
            + "Intro with ==hl== text and a " + COMMENT + " in it.\n\nSecond paragraph.\n";

    private static void assertIntact( final String text, final String why ) {
        assertTrue( text.contains( COMMENT ), why + ": the comment survives: " + text );
        assertTrue( text.contains( "==hl==" ), why + ": the highlight survives: " + text );
        assertTrue( text.contains( "[{ALLOW edit Admin}]" ), why + ": the rule survives: " + text );
        assertTrue( text.contains( "title: Round Trip" ) && text.contains( "type: article" ), why + ": the frontmatter survives: " + text );
    }

    /** An editor is sent the raw stored text, so saving it back cannot drop a comment. */
    @Test
    void anEditorsPutOfWhatTheyWereSentKeepsCommentsFrontmatterAndRules() throws Exception {
        engine.saveText( PAGE, ORIGINAL );
        final JsonObject got = get( true, Map.of() ).json();
        final String sent = got.get( "content" ).getAsString();
        assertTrue( sent.contains( COMMENT ), "the editor is sent the comment: " + sent );

        // The editor sends back what it was given: the body and the frontmatter fields, apart.
        final JsonObject body = new JsonObject();
        body.addProperty( "content", sent );
        body.add( "metadata", got.getAsJsonObject( "metadata" ) );
        final Reply reply = send( "PUT", body );

        verify( reply.response(), org.mockito.Mockito.never() ).setStatus( org.mockito.ArgumentMatchers.anyInt() );
        assertIntact( stored(), "after a PUT" );
    }

    /** A metadata PATCH (the only PATCH there is) rewrites the page text: the body's comments and rules must ride through. */
    @Test
    void anEditorsMetadataPatchKeepsCommentsFrontmatterAndRules() throws Exception {
        engine.saveText( PAGE, ORIGINAL );
        final JsonObject body = new JsonObject();
        final JsonObject metadata = new JsonObject();
        metadata.addProperty( "summary", "A trivial edit elsewhere." );
        body.add( "metadata", metadata );

        final Reply reply = send( "PATCH", body );

        verify( reply.response(), org.mockito.Mockito.never() ).setStatus( org.mockito.ArgumentMatchers.anyInt() );
        final String after = stored();
        assertTrue( after.contains( "summary: A trivial edit elsewhere." ), "the edit landed: " + after );
        assertIntact( after, "after a PATCH" );
    }

    /** A caller without edit is never sent the comment and cannot save, so they cannot write back a stripped copy. */
    @Test
    void aViewerWithoutEditIsNotSentTheCommentAndCannotSave() throws Exception {
        engine.saveText( PAGE, ORIGINAL );
        final Reply read = get( false, Map.of() );
        assertFalse( read.body().contains( "secret note" ), "no comment text for a viewer: " + read.body() );
        assertTrue( read.json().get( "content" ).getAsString().contains( "Second paragraph." ), "control: they do read the page" );

        final HttpServletRequest request = request( false, Map.of() );
        final JsonObject body = new JsonObject();
        body.addProperty( "content", read.json().get( "content" ).getAsString() );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final Reply put = call( request, "PUT" );

        verify( put.response() ).setStatus( org.mockito.ArgumentMatchers.intThat( c -> c == 401 || c == 403 ) );
        assertEquals( ORIGINAL, stored(), "a refused save changes nothing" );
    }
}
