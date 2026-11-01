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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.WikiSubsystems;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.parser.markdown.BlockIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code GET/POST /api/pages/{name}/blocks} plus the {@code section=^id} forms of {@code /preview} and {@code /embed}. */
class PageBlocksHandlerTest {

    private static TestEngine engine;
    private static PageResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @AfterEach
    void clean() {
        engine.deleteQuietly( "RestBlkPage", "RestBlkDerived", "RestBlkHost" );
    }

    /** What a call answered. */
    private record Reply( int status, String body ) {
        JsonObject json() {
            return new Gson().fromJson( body, JsonObject.class );
        }
    }

    private PageResource spy( final boolean view, final boolean edit ) {
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( view ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "view" ) );
        Mockito.doReturn( edit ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "edit" ) );
        // POST enforces edit through the audited check, as PUT does: a denial is a 403 it sends itself.
        try {
            Mockito.doAnswer( inv -> {
                if ( !edit ) {
                    RestJson.sendError( inv.getArgument( 1 ), HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
                }
                return edit;
            } ).when( spy ).checkPagePermission( Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.eq( "edit" ) );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( e );
        }
        return spy;
    }

    private Reply get( final PageResource s, final String path, final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();
        params.forEach( ( k, v ) -> Mockito.doReturn( v ).when( request ).getParameter( k ) );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        s.doGet( request, response );
        return new Reply( statusOf( response ), sw.toString() );
    }

    private Reply post( final PageResource s, final String path, final String body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        s.doPost( request, response );
        return new Reply( statusOf( response ), sw.toString() );
    }

    /** The last status set on the mock response; 200 when none was. */
    private static int statusOf( final HttpServletResponse response ) {
        final var captor = org.mockito.ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( response, Mockito.atLeast( 0 ) ).setStatus( captor.capture() );
        return captor.getAllValues().isEmpty() ? 200 : captor.getValue();
    }

    // ----- GET /blocks -----

    @Test
    void listReturnsVersionCanAssignAndBlocks() throws Exception {
        engine.saveText( "RestBlkPage", "First para.\n\nSecond para. ^two\n\n- an item\n" );
        final Reply r = get( spy( true, true ), "RestBlkPage/blocks", Map.of() );
        assertEquals( 200, r.status() );
        final JsonObject o = r.json();
        assertEquals( 1, o.get( "version" ).getAsInt() );
        assertTrue( o.get( "canAssign" ).getAsBoolean() );
        final JsonArray blocks = o.getAsJsonArray( "blocks" );
        assertTrue( blocks.size() >= 3, o.toString() );
        final JsonObject b0 = blocks.get( 0 ).getAsJsonObject();
        assertEquals( 0, b0.get( "index" ).getAsInt() );
        assertTrue( b0.get( "id" ).isJsonNull() );
        assertEquals( "paragraph", b0.get( "kind" ).getAsString() );
        assertEquals( "First para.", b0.get( "text" ).getAsString() );
        final JsonObject b1 = blocks.get( 1 ).getAsJsonObject();
        assertEquals( "two", b1.get( "id" ).getAsString() );
        assertEquals( "Second para.", b1.get( "text" ).getAsString() );
    }

    @Test
    void listFiltersByTextOrIdCaseInsensitivelyAndCapsLimit() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < 130; i++ ) {
            sb.append( "Paragraph number " ).append( i ).append( i == 7 ? " NEEDLE" : "" ).append( ".\n\n" );
        }
        sb.append( "Tail. ^zebra9\n" );
        engine.saveText( "RestBlkPage", sb.toString() );
        final PageResource s = spy( true, true );

        assertEquals( 20, get( s, "RestBlkPage/blocks", Map.of() ).json().getAsJsonArray( "blocks" ).size() );
        assertEquals( 100, get( s, "RestBlkPage/blocks", Map.of( "limit", "5000" ) ).json().getAsJsonArray( "blocks" ).size() );
        assertEquals( 3, get( s, "RestBlkPage/blocks", Map.of( "limit", "3" ) ).json().getAsJsonArray( "blocks" ).size() );

        final JsonArray byText = get( s, "RestBlkPage/blocks", Map.of( "q", "needle" ) ).json().getAsJsonArray( "blocks" );
        assertEquals( 1, byText.size() );
        assertEquals( 7, byText.get( 0 ).getAsJsonObject().get( "index" ).getAsInt() );
        final JsonArray byId = get( s, "RestBlkPage/blocks", Map.of( "q", "ZEBRA" ) ).json().getAsJsonArray( "blocks" );
        assertEquals( 1, byId.size() );
        assertEquals( "zebra9", byId.get( 0 ).getAsJsonObject().get( "id" ).getAsString() );
    }

    @Test
    void listTextIsCutTo120Chars() throws Exception {
        engine.saveText( "RestBlkPage", "x".repeat( 300 ) + "\n" );
        final JsonObject b = get( spy( true, true ), "RestBlkPage/blocks", Map.of() ).json()
                .getAsJsonArray( "blocks" ).get( 0 ).getAsJsonObject();
        assertEquals( 120, b.get( "text" ).getAsString().length() );
    }

    @Test
    void canAssignIsFalseWithoutEditAndOnADerivedPage() throws Exception {
        engine.saveText( "RestBlkPage", "Para.\n" );
        assertFalse( get( spy( true, false ), "RestBlkPage/blocks", Map.of() ).json().get( "canAssign" ).getAsBoolean() );
        engine.saveText( "RestBlkDerived", "---\nderived_from: a.pdf\n---\nPara.\n" );
        final JsonObject d = get( spy( true, true ), "RestBlkDerived/blocks", Map.of() ).json();
        assertFalse( d.get( "canAssign" ).getAsBoolean() );
        assertEquals( 1, d.getAsJsonArray( "blocks" ).size() );
    }

    @Test
    void missingAndUnviewablePagesAreTheSame404() throws Exception {
        engine.saveText( "RestBlkPage", "Secret.\n" );
        final Reply hidden = get( spy( false, false ), "RestBlkPage/blocks", Map.of() );
        final Reply missing = get( spy( true, true ), "RestBlkNoSuchPage/blocks", Map.of() );
        assertEquals( 404, hidden.status() );
        assertEquals( 404, missing.status() );
        assertEquals( missing.body(), hidden.body() );
        assertFalse( hidden.body().contains( "Secret" ) );
    }

    @Test
    void listWhereTheBlockModelFailsIsAnEmptyListingNotA500() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        try ( MockedStatic< BlockIds > ids = Mockito.mockStatic( BlockIds.class, Mockito.CALLS_REAL_METHODS ) ) {
            ids.when( () -> BlockIds.list( Mockito.anyString() ) ).thenThrow( new IllegalStateException( "unmappable" ) );
            final Reply r = get( spy( true, true ), "RestBlkPage/blocks", Map.of() );
            assertEquals( 200, r.status() );
            assertEquals( 0, r.json().getAsJsonArray( "blocks" ).size() );
            assertEquals( 1, r.json().get( "version" ).getAsInt() );
        }
    }

    @Test
    void listOfAPageWhoseTextIsGoneIsAnEmptyListingNotA500() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        final PageResource s = spy( true, true );
        final PageManager real = engine.getManager( PageManager.class );
        final PageManager pages = Mockito.mock( PageManager.class, AdditionalAnswers.delegatesTo( real ) );
        Mockito.doReturn( null ).when( pages ).getPureText( Mockito.eq( "RestBlkPage" ), Mockito.anyInt() );
        final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class, AdditionalAnswers.delegatesTo( servlet.getSubsystems() ) );
        final PageSubsystem.Services page = Mockito.mock( PageSubsystem.Services.class,
                AdditionalAnswers.delegatesTo( servlet.getSubsystems().page() ) );
        Mockito.doReturn( pages ).when( page ).pages();
        Mockito.doReturn( page ).when( subs ).page();
        Mockito.doReturn( subs ).when( s ).getSubsystems();
        final Reply r = get( s, "RestBlkPage/blocks", Map.of() );
        assertEquals( 200, r.status() );
        assertEquals( 0, r.json().getAsJsonArray( "blocks" ).size() );
        assertEquals( 1, r.json().get( "version" ).getAsInt() );
        assertFalse( r.json().get( "canAssign" ).getAsBoolean() );
    }

    // ----- POST /blocks -----

    private static String payload( final int version, final int index, final String fp ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "version", version );
        o.addProperty( "index", index );
        o.addProperty( "fingerprint", fp );
        return o.toString();
    }

    @Test
    void postAssignsAndReturnsIdAndVersion() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n\nBeta.\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 1, "beta" ) );
        assertEquals( 200, r.status() );
        final String id = r.json().get( "id" ).getAsString();
        assertTrue( id.matches( "[a-z0-9]{6}" ) );
        assertTrue( r.json().get( "version" ).getAsInt() >= 1 );
        assertTrue( engine.getManager( PageManager.class ).getPureText( "RestBlkPage", PageProvider.LATEST_VERSION )
                .contains( "Beta. ^" + id ) );
    }

    @Test
    void postOnAnAlreadyTaggedBlockReturnsItsIdWithoutChange() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha. ^abc123\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 0, "alpha" ) );
        assertEquals( 200, r.status() );
        assertEquals( "abc123", r.json().get( "id" ).getAsString() );
    }

    @Test
    void postOnATaggedBlockTheClientSawAsOtherTextIs409BlockNotLocated() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha. ^abc123\n\nBeta.\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 0, "beta" ) );
        assertEquals( 409, r.status() );
        assertEquals( "block-not-located", r.json().get( "reason" ).getAsString() );
        final String text = engine.getManager( PageManager.class ).getPureText( "RestBlkPage", PageProvider.LATEST_VERSION );
        assertFalse( text.contains( "Beta. ^" ), text );
    }

    @Test
    void postIs404WhenMissingOrUnviewableAnd403WhenViewableButNotEditable() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        assertEquals( 404, post( spy( true, true ), "RestBlkNoSuchPage/blocks", payload( 1, 0, "" ) ).status() );
        assertEquals( 404, post( spy( false, false ), "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
        assertEquals( 403, post( spy( true, false ), "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
    }

    @Test
    void postDeniesEditThroughTheAuditedCheck() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        final PageResource s = spy( true, false );
        assertEquals( 403, post( s, "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
        Mockito.verify( s ).checkPagePermission( Mockito.any(), Mockito.any(), Mockito.eq( "RestBlkPage" ),
                Mockito.eq( "edit" ) );
    }

    /** Vetoes a save of a page carrying the marker text, the way the validating filters do. */
    private static final java.util.concurrent.atomic.AtomicReference< java.util.function.Supplier< com.wikantik.api.exceptions.FilterException > >
            VETO = new java.util.concurrent.atomic.AtomicReference<>();
    private static boolean vetoInstalled;

    private static synchronized void installVeto() {
        if ( vetoInstalled ) {
            return;
        }
        engine.getManager( com.wikantik.filters.FilterManager.class ).addPageFilter( new com.wikantik.api.filters.PageFilter() {
            @Override
            public String preSave( final com.wikantik.api.core.Context context, final String content )
                    throws com.wikantik.api.exceptions.FilterException {
                final var veto = VETO.get();
                if ( veto != null && content.contains( "VETO-ME" ) ) {
                    throw veto.get();
                }
                return content;
            }
        }, 0 );
        vetoInstalled = true;
    }

    @Test
    void postMapsValidationRefusalsToTheSame422BodiesAsPut() throws Exception {
        installVeto();
        engine.saveText( "RestBlkPage", "VETO-ME alpha.\n" );
        final PageResource s = spy( true, true );
        try {
            VETO.set( () -> new com.wikantik.api.exceptions.FrontmatterValidationException( java.util.List.of(
                    com.wikantik.api.frontmatter.schema.FieldViolation.of( "type", com.wikantik.api.frontmatter.schema.Severity.ERROR,
                            "bad", "Bad type" ) ) ) );
            final Reply fm = post( s, "RestBlkPage/blocks", payload( 1, 0, "" ) );
            assertEquals( 422, fm.status() );
            assertEquals( "frontmatter_validation_failed", fm.json().get( "error" ).getAsString() );

            VETO.set( () -> new com.wikantik.api.content.ContentValidationException( java.util.List.of(
                    new com.wikantik.api.content.ContentViolation( "body", com.wikantik.api.frontmatter.schema.Severity.ERROR,
                            "bad", "Bad math", null ) ) ) );
            final Reply math = post( s, "RestBlkPage/blocks", payload( 1, 0, "" ) );
            assertEquals( 422, math.status() );
            assertEquals( "math_validation_failed", math.json().get( "error" ).getAsString() );

            VETO.set( () -> new com.wikantik.api.exceptions.FilterException( "vetoed" ) );
            assertEquals( 500, post( s, "RestBlkPage/blocks", payload( 1, 0, "" ) ).status() );
        } finally {
            VETO.set( null );
        }
        assertEquals( 1, get( s, "RestBlkPage/blocks", Map.of() ).json().get( "version" ).getAsInt() );
    }

    @Test
    void listOmitsADirectiveOnlyParagraphAndNumbersLikeTheRender() throws Exception {
        engine.saveText( "RestBlkPage", "[{ALLOW view Admin}]\n\nFirst.\n\nSecond.\n" );
        final JsonArray blocks = get( spy( true, true ), "RestBlkPage/blocks", Map.of() ).json().getAsJsonArray( "blocks" );
        assertEquals( 2, blocks.size() );
        assertEquals( "First.", blocks.get( 0 ).getAsJsonObject().get( "text" ).getAsString() );
        assertEquals( "Second.", blocks.get( 1 ).getAsJsonObject().get( "text" ).getAsString() );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 1, "second" ) );
        assertEquals( 200, r.status() );
        final String text = engine.getManager( PageManager.class ).getPureText( "RestBlkPage", PageProvider.LATEST_VERSION );
        assertTrue( text.contains( "Second. ^" + r.json().get( "id" ).getAsString() ), text );
        assertFalse( text.contains( "First. ^" ), text );
    }

    @Test
    void post409BodiesCarryTheReason() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        final PageResource s = spy( true, true );
        final Reply stale = post( s, "RestBlkPage/blocks", payload( 9, 0, "" ) );
        assertEquals( 409, stale.status() );
        assertEquals( "stale", stale.json().get( "reason" ).getAsString() );
        final Reply gone = post( s, "RestBlkPage/blocks", payload( 1, 7, "" ) );
        assertEquals( 409, gone.status() );
        assertEquals( "block-not-located", gone.json().get( "reason" ).getAsString() );
        engine.saveText( "RestBlkDerived", "---\nderived_from: a.pdf\n---\nPara.\n" );
        final Reply derived = post( s, "RestBlkDerived/blocks", payload( 1, 0, "" ) );
        assertEquals( 409, derived.status() );
        assertEquals( "derived", derived.json().get( "reason" ).getAsString() );
    }

    @Test
    void postWithAMalformedBodyIs400() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n" );
        assertEquals( 400, post( spy( true, true ), "RestBlkPage/blocks", "{\"index\":0}" ).status() );
    }

    // ----- /preview and /embed with section=^id -----

    @Test
    void previewOfABlockExcerptsThatBlock() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nThe **quoted** claim. ^claim1\n" );
        final JsonObject o = get( spy( true, true ), "RestBlkPage/preview", Map.of( "section", "^claim1" ) ).json();
        assertEquals( "The quoted claim.", o.get( "excerpt" ).getAsString() );
        assertFalse( o.has( "blockMissing" ) );
    }

    @Test
    void previewOfAMissingBlockFallsBackToThePageExcerptAndFlagsIt() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nOther. ^there\n" );
        final JsonObject o = get( spy( true, true ), "RestBlkPage/preview", Map.of( "section", "^gone" ) ).json();
        assertTrue( o.get( "excerpt" ).getAsString().startsWith( "Intro para." ) );
        assertTrue( o.get( "blockMissing" ).getAsBoolean() );
    }

    @Test
    void embedOfABlockRendersOnlyThatBlock() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n\nThe **quoted** claim. ^claim1\n\nOutro.\n" );
        final JsonObject o = get( servlet, "RestBlkPage/embed", Map.of( "section", "^claim1" ) ).json();
        final String html = o.get( "html" ).getAsString();
        assertTrue( html.contains( "<strong>quoted</strong>" ), html );
        assertFalse( html.contains( "Intro para" ) || html.contains( "Outro" ), html );
        assertFalse( o.get( "missing" ).getAsBoolean() );
    }

    @Test
    void embedOfAMissingBlockIsFlaggedMissing() throws Exception {
        engine.saveText( "RestBlkPage", "Intro para.\n" );
        final JsonObject o = get( servlet, "RestBlkPage/embed", Map.of( "section", "^gone" ) ).json();
        assertTrue( o.get( "missing" ).getAsBoolean(), o.toString() );
    }

    // ----- never a 500 from the block model -----

    /** Texts ending in an access rule or a plugin, which preparation follows with an appended {@code ()}. */
    private static final String[] ENDING_IN_A_RULE = {
        "Text [{ALLOW view Admin}]", "- [{ALLOW view Admin}]", "Para ^id\n\nx[{ALLOW view Admin}]", "Para ^id\n\nx[{Plugin}]",
    };

    /**
     * A servlet whose page manager reads {@code text} as RestBlkPage's stored text, byte for byte (a save would add the
     * final line end that these texts lack).
     */
    private PageResource readingStoredText( final String text ) {
        final PageResource s = spy( true, true );
        final PageManager real = engine.getManager( PageManager.class );
        final PageManager pages = Mockito.mock( PageManager.class, AdditionalAnswers.delegatesTo( real ) );
        Mockito.doReturn( text ).when( pages ).getPureText( Mockito.eq( "RestBlkPage" ), Mockito.anyInt() );
        final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class, AdditionalAnswers.delegatesTo( servlet.getSubsystems() ) );
        final PageSubsystem.Services page = Mockito.mock( PageSubsystem.Services.class,
                AdditionalAnswers.delegatesTo( servlet.getSubsystems().page() ) );
        Mockito.doReturn( pages ).when( page ).pages();
        Mockito.doReturn( page ).when( subs ).page();
        Mockito.doReturn( subs ).when( s ).getSubsystems();
        return s;
    }

    @Test
    void textsEndingInARuleNeverFailTheBlockEndpoints() throws Exception {
        engine.saveText( "RestBlkPage", "Placeholder.\n" );
        for ( final String text : ENDING_IN_A_RULE ) {
            final Reply list = get( readingStoredText( text ), "RestBlkPage/blocks", Map.of() );
            assertEquals( 200, list.status(), text + " " + list.body() );
            assertTrue( list.json().getAsJsonArray( "blocks" ).size() > 0, text + " " + list.body() );
            final Reply assigned = post( readingStoredText( text ), "RestBlkPage/blocks", payload( 1, 0, null ) );
            assertTrue( assigned.status() == 200 || assigned.status() == 409, text + " -> " + assigned.status() + " " + assigned.body() );
            final Reply preview = get( readingStoredText( text ), "RestBlkPage/preview", Map.of( "section", "^id" ) );
            assertEquals( 200, preview.status(), text + " " + preview.body() );
        }
        assertEquals( "Para", get( readingStoredText( "Para ^id\n\nx[{ALLOW view Admin}]" ), "RestBlkPage/preview",
                Map.of( "section", "^id" ) ).json().get( "excerpt" ).getAsString() );
    }

    @Test
    void anyFailureOfTheBlockModelIsAnEmptyListingAndA409NeverA500() throws Exception {
        engine.saveText( "RestBlkPage", "Alpha.\n\nBeta. ^b\n" );
        try ( MockedStatic< BlockIds > ids = Mockito.mockStatic( BlockIds.class, Mockito.CALLS_REAL_METHODS ) ) {
            ids.when( () -> BlockIds.list( Mockito.anyString() ) ).thenThrow( new StringIndexOutOfBoundsException( "past the end" ) );
            ids.when( () -> BlockIds.find( Mockito.anyString(), Mockito.anyString() ) ).thenThrow( new IllegalArgumentException( "odd" ) );
            final Reply list = get( spy( true, true ), "RestBlkPage/blocks", Map.of() );
            assertEquals( 200, list.status(), list.body() );
            assertEquals( 0, list.json().getAsJsonArray( "blocks" ).size() );
            final Reply assigned = post( spy( true, true ), "RestBlkPage/blocks", payload( 1, 0, "alpha" ) );
            assertEquals( 409, assigned.status(), assigned.body() );
            assertEquals( "block-not-located", assigned.json().get( "reason" ).getAsString() );
            final Reply preview = get( spy( true, true ), "RestBlkPage/preview", Map.of( "section", "^b" ) );
            assertEquals( 200, preview.status(), preview.body() );
            assertTrue( preview.json().get( "blockMissing" ).getAsBoolean() );
        }
    }

    // ----- POST /blocks/draft: the editor's buffer, read by the server's block model -----

    /** The shape where the editor's own parse orphaned an id: the reader reads A's marker as code, so ^b is Other's. */
    private static final String ORPHAN_SHAPE = "> Para\n>\n> [{ALLOW view Admin}]    ^b\n\nOther ^b\n";

    private static String draft( final String text, final Integer offset ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "text", text );
        if ( offset != null ) {
            o.addProperty( "offset", offset );
        }
        return o.toString();
    }

    @Test
    void draftListsTheBlocksTheReaderShowsForTheGivenText() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final Reply r = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( ORPHAN_SHAPE, null ) );
        assertEquals( 200, r.status(), r.body() );
        final JsonArray blocks = r.json().getAsJsonArray( "blocks" );
        final java.util.List< BlockIds.Block > model = BlockIds.list( ORPHAN_SHAPE );
        assertEquals( model.size(), blocks.size() );
        for ( int i = 0; i < model.size(); i++ ) {
            final JsonObject row = blocks.get( i ).getAsJsonObject();
            assertEquals( i, row.get( "index" ).getAsInt() );
            assertEquals( model.get( i ).start(), row.get( "from" ).getAsInt() );
            assertEquals( model.get( i ).end(), row.get( "to" ).getAsInt() );
            assertEquals( model.get( i ).id() == null, row.get( "id" ).isJsonNull() );
        }
        assertEquals( "b", blocks.get( blocks.size() - 1 ).getAsJsonObject().get( "id" ).getAsString() );
        final JsonObject q = new JsonObject();
        q.addProperty( "text", ORPHAN_SHAPE );
        q.addProperty( "q", "other" );
        q.addProperty( "limit", 5 );
        final Reply narrowed = post( spy( true, true ), "RestBlkPage/blocks/draft", q.toString() );
        assertEquals( 1, narrowed.json().getAsJsonArray( "blocks" ).size() );
    }

    @Test
    void draftAtAnOffsetGivesTheReadersIdOrTheOneEditThatAddsOne() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final PageManager pm = engine.getManager( PageManager.class );
        final String saved = pm.getPureText( "RestBlkPage", PageProvider.LATEST_VERSION );
        final int version = pm.getPage( "RestBlkPage" ).getVersion();
        final Reply other = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( ORPHAN_SHAPE, ORPHAN_SHAPE.indexOf( "Other" ) ) );
        assertEquals( 200, other.status(), other.body() );
        assertEquals( "b", other.json().get( "id" ).getAsString() );
        assertTrue( other.json().get( "change" ).isJsonNull() );
        final Reply para = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( ORPHAN_SHAPE, 3 ) );
        assertEquals( 200, para.status(), para.body() );
        final String id = para.json().get( "id" ).getAsString();
        final JsonObject change = para.json().getAsJsonObject( "change" );
        final String after = ORPHAN_SHAPE.substring( 0, change.get( "from" ).getAsInt() ) + change.get( "insert" ).getAsString()
                + ORPHAN_SHAPE.substring( change.get( "to" ).getAsInt() );
        final java.util.List< BlockIds.Block > before = BlockIds.list( ORPHAN_SHAPE );
        final java.util.List< BlockIds.Block > now = BlockIds.list( after );
        final BlockIds.Block target = before.stream().filter( b -> b.start() <= 3 && 3 <= b.end() )
                .max( java.util.Comparator.comparingInt( BlockIds.Block::start ) ).orElseThrow();
        assertEquals( id, now.get( target.index() ).id() );
        for ( int i = 0; i < before.size(); i++ ) {
            if ( i != target.index() ) {
                assertEquals( before.get( i ).id(), now.get( i ).id(), "block " + i );
            }
        }
        assertEquals( saved, pm.getPureText( "RestBlkPage", PageProvider.LATEST_VERSION ), "a draft saves nothing" );
        assertEquals( version, pm.getPage( "RestBlkPage" ).getVersion() );
    }

    @Test
    void draftRefusalsAre409WithTheirReason() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final Reply none = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( "# Heading\n\nPara.\n", 3 ) );
        assertEquals( 409, none.status() );
        assertEquals( "no-block", none.json().get( "reason" ).getAsString() );
        final Reply refused = post( spy( true, true ), "RestBlkPage/blocks/draft",
                draft( "| a | b |\n|---|---|\n| 1 | 2 |\n\tPara.\n", 3 ) );
        assertEquals( 409, refused.status() );
        assertEquals( "refused", refused.json().get( "reason" ).getAsString() );
    }

    @Test
    void draftHasThePostureOfPostBlocks() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        // a missing page is a page not saved yet: PUT-create's posture (aPageNotSavedYetTakesADraft…)
        assertEquals( 404, post( spy( false, true ), "RestBlkPage/blocks/draft", draft( "x", null ) ).status() );
        assertEquals( 403, post( spy( true, false ), "RestBlkPage/blocks/draft", draft( "x", null ) ).status() );
        assertEquals( 400, post( spy( true, true ), "RestBlkPage/blocks/draft", "{}" ).status() );
        assertEquals( 400, post( spy( true, true ), "RestBlkPage/blocks/draft", "not json" ).status() );
        assertEquals( 400, post( spy( true, true ), "RestBlkPage/blocks/draft", "{\"text\":\"x\",\"metadata\":\"t\"}" ).status() );
        assertEquals( 200, post( spy( true, true ), "RestBlkPage/blocks/draft", "{\"text\":\"x\",\"metadata\":null}" ).status() );
        assertEquals( 404, post( spy( true, true ), "Bad:Name/blocks/draft", draft( "x", null ) ).status(), "no such page can be saved" );
        final String big = "x".repeat( PageResource.DEFAULT_MAX_PAGE_BYTES + 1 );
        assertEquals( 413, post( spy( true, true ), "RestBlkPage/blocks/draft", draft( big, null ) ).status() );
    }

    @Test
    void anOversizedDraftBodyIsRefusedBeforeItIsRead() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/RestBlkPage/blocks/draft" );
        Mockito.doReturn( "/RestBlkPage/blocks/draft" ).when( request ).getPathInfo();
        Mockito.doReturn( 100L * PageResource.DEFAULT_MAX_PAGE_BYTES ).when( request ).getContentLengthLong();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        spy( true, true ).doPost( request, response );
        assertEquals( 413, statusOf( response ) );
        Mockito.verify( request, Mockito.never() ).getReader();
    }

    @Test
    void aFailingBlockModelNeverFailsADraft() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        try ( MockedStatic< BlockIds > ids = Mockito.mockStatic( BlockIds.class, Mockito.CALLS_REAL_METHODS ) ) {
            ids.when( () -> BlockIds.list( Mockito.anyString() ) ).thenThrow( new StringIndexOutOfBoundsException( "past the end" ) );
            final Reply list = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( "Para.\n", null ) );
            assertEquals( 200, list.status(), list.body() );
            assertEquals( 0, list.json().getAsJsonArray( "blocks" ).size() );
            final Reply at = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( "Para.\n", 1 ) );
            assertEquals( 409, at.status(), at.body() );
            assertEquals( "refused", at.json().get( "reason" ).getAsString() );
        }
    }

    // ----- round 4: the draft is a BODY behind the editor's frontmatter; chunked bodies; a page not saved yet -----

    /** The reviewer's shape: a body opening with a thematic break, and a later one, each id twice. */
    private static final String HR_BODY = "---\nIntro ^x\n\n---\n\nPara ^x\n\nThird\n";

    private static String draft( final String text, final Integer offset, final Map< String, Object > metadata ) {
        final JsonObject o = new Gson().fromJson( draft( text, offset ), JsonObject.class );
        if ( metadata != null ) {
            o.add( "metadata", new Gson().toJsonTree( metadata ) );
        }
        return o.toString();
    }

    /** The blocks a reader gets for the page a PUT of {@code {content: body, metadata}} saves, in the body's offsets. */
    private static java.util.List< int[] > readerSpans( final String body, final Map< String, Object > metadata ) {
        final String saved = com.wikantik.api.frontmatter.FrontmatterWriter.write( metadata, body );
        final int shift = saved.length() - body.length();
        return BlockIds.list( saved ).stream().map( b -> new int[]{ b.start() - shift, b.end() - shift } ).toList();
    }

    @Test
    void aDraftWithMetadataIsABodyBehindItsFrontmatterAsTheReaderReadsTheSavedPage() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final Map< String, Object > meta = Map.of( "title", "T" );
        final String saved = com.wikantik.api.frontmatter.FrontmatterWriter.write( meta, HR_BODY );
        final int shift = saved.length() - HR_BODY.length();
        final java.util.List< BlockIds.Block > reader = BlockIds.list( saved );

        final JsonArray rows = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, null, meta ) )
                .json().getAsJsonArray( "blocks" );
        assertEquals( reader.size(), rows.size(), rows.toString() );
        for ( int i = 0; i < reader.size(); i++ ) {
            final JsonObject row = rows.get( i ).getAsJsonObject();
            assertEquals( reader.get( i ).start() - shift, row.get( "from" ).getAsInt(), "block " + i );
            assertEquals( reader.get( i ).id() == null, row.get( "id" ).isJsonNull(), "block " + i );
        }

        // "Intro ^x" is a paragraph between two thematic breaks on the saved page: its id is x
        final Reply intro = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, HR_BODY.indexOf( "Intro" ), meta ) );
        assertEquals( 200, intro.status(), intro.body() );
        assertEquals( "x", intro.json().get( "id" ).getAsString() );
        assertTrue( intro.json().get( "change" ).isJsonNull() );
        // "Para ^x" is the losing duplicate: copying ^x would link Intro — it gets a new id instead
        final Reply para = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, HR_BODY.indexOf( "Para" ), meta ) );
        assertEquals( 200, para.status(), para.body() );
        final String id = para.json().get( "id" ).getAsString();
        assertNotEquals( "x", id );
        final JsonObject change = para.json().getAsJsonObject( "change" );
        final String edited = HR_BODY.substring( 0, change.get( "from" ).getAsInt() ) + change.get( "insert" ).getAsString()
                + HR_BODY.substring( change.get( "to" ).getAsInt() );
        assertEquals( id, BlockIds.list( com.wikantik.api.frontmatter.FrontmatterWriter.write( meta, edited ) ).stream()
                .filter( b -> b.plainText().equals( "Para" ) ).findFirst().orElseThrow().id() );
    }

    @Test
    void aDraftWithoutMetadataIsTheWholePageAsAPutWithoutMetadataSavesIt() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        for ( final Map< String, Object > meta : java.util.Arrays.< Map< String, Object > >asList( null, Map.of() ) ) {
            final JsonArray rows = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, null, meta ) )
                    .json().getAsJsonArray( "blocks" );
            final java.util.List< int[] > reader = readerSpans( HR_BODY, meta );
            assertEquals( reader.size(), rows.size(), meta + " " + rows );
            for ( int i = 0; i < reader.size(); i++ ) {
                assertEquals( reader.get( i )[ 0 ], rows.get( i ).getAsJsonObject().get( "from" ).getAsInt() );
            }
            final Reply intro = post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, HR_BODY.indexOf( "Intro" ), meta ) );
            assertEquals( 409, intro.status(), "Intro is frontmatter on a page saved without metadata" );
            assertEquals( "x", post( spy( true, true ), "RestBlkPage/blocks/draft", draft( HR_BODY, HR_BODY.indexOf( "Para" ), meta ) )
                    .json().get( "id" ).getAsString() );
        }
    }

    @Test
    void aChunkedDraftBodyIsReadNoFurtherThanTheCap() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/RestBlkPage/blocks/draft" );
        Mockito.doReturn( "/RestBlkPage/blocks/draft" ).when( request ).getPathInfo();
        Mockito.doReturn( -1L ).when( request ).getContentLengthLong();
        // a short text, but padding far past any body a draft may have: without a Content-Length nothing refuses it early
        final long[] served = { 0 };
        final long padding = 20L * PageResource.DEFAULT_MAX_PAGE_BYTES;
        final java.io.Reader endless = new java.io.Reader() {
            private final String head = "{\"text\":\"Para.\",\"pad\":\"";
            @Override
            public int read( final char[] buf, final int off, final int len ) {
                if ( served[ 0 ] >= head.length() + padding ) {
                    return -1;
                }
                int n = 0;
                while ( n < len && served[ 0 ] < head.length() + padding ) {
                    buf[ off + n++ ] = served[ 0 ] < head.length() ? head.charAt( ( int ) served[ 0 ] ) : 'x';
                    served[ 0 ]++;
                }
                return n;
            }
            @Override
            public void close() {
            }
        };
        Mockito.doReturn( new BufferedReader( endless ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
        spy( true, true ).doPost( request, response );
        assertEquals( 413, statusOf( response ) );
        assertTrue( served[ 0 ] < 7L * PageResource.DEFAULT_MAX_PAGE_BYTES + 65_536, "read " + served[ 0 ] + " chars" );
    }

    @Test
    void aPageNotSavedYetTakesADraftFromWhoeverMayCreateItAsPutDoes() throws Exception {
        final PageResource canEdit = spy( false, true );   // view of a missing page is never asked
        final Reply list = post( canEdit, "RestBlkMissing/blocks/draft", draft( "Para ^p\n\nNext\n", null, Map.of( "title", "T" ) ) );
        assertEquals( 200, list.status(), list.body() );
        assertEquals( 2, list.json().getAsJsonArray( "blocks" ).size() );
        final Reply at = post( canEdit, "RestBlkMissing/blocks/draft", draft( "Para ^p\n\nNext\n", 0, Map.of( "title", "T" ) ) );
        assertEquals( 200, at.status(), at.body() );
        assertEquals( "p", at.json().get( "id" ).getAsString() );
        assertNull( engine.getManager( PageManager.class ).getPage( "RestBlkMissing" ), "a draft creates nothing" );
        // without edit on the name, a missing page is the plain 404 a hidden one is (no existence oracle)
        assertEquals( 404, post( spy( true, false ), "RestBlkMissing/blocks/draft", draft( "Para\n", null ) ).status() );
        // an existing page the caller cannot see stays a plain 404, whatever it may edit
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        assertEquals( 404, post( spy( false, true ), "RestBlkPage/blocks/draft", draft( "Para\n", null ) ).status() );
    }

    /** Everything a call wrote to its response: status, body, and every header / content-type call. */
    private String wholeReply( final PageResource s, final String path ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( draft( "Para ^p\n", 0 ) ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        s.doPost( request, response );
        final StringBuilder out = new StringBuilder( "status=" ).append( statusOf( response ) ).append( '\n' );
        for ( final var inv : Mockito.mockingDetails( response ).getInvocations() ) {
            final String m = inv.getMethod().getName();
            if ( m.startsWith( "set" ) && !m.equals( "setStatus" ) || m.startsWith( "add" ) || m.equals( "sendError" ) ) {
                out.append( m ).append( java.util.Arrays.toString( inv.getArguments() ) ).append( '\n' );
            }
        }
        return out.append( "body=" ).append( sw ).toString();
    }

    @Test
    void aMissingPageWithoutEditAnswersExactlyAsAHiddenOne() throws Exception {
        engine.saveText( "RestBlkPage", "Saved text.\n" );
        final String missing = wholeReply( spy( true, false ), "RestBlkMissing/blocks/draft" );
        assertTrue( missing.startsWith( "status=404" ), missing );
        assertEquals( wholeReply( spy( false, false ), "RestBlkPage/blocks/draft" ), missing );
        assertEquals( wholeReply( spy( false, true ), "RestBlkPage/blocks/draft" ), missing );
        assertEquals( wholeReply( spy( false, false ), "RestBlkMissing/blocks/draft" ), missing );
    }
}
