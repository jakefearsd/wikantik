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
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PUT guards of {@link PageSaveHandler}: the page-name allow-list, the byte limit (D22, counted in UTF-8 bytes) and
 * the strict expected-version mode (D20). The engine runs with a 20-byte limit and strict mode on so each boundary can
 * be probed with a few characters.
 */
class PageSaveHandlerTest {

    private static final int LIMIT = 20;

    private static TestEngine engine;
    private static PageResource servlet;
    private final Gson gson = new Gson();

    @BeforeAll
    static void startEngine() throws Exception {
        engine = TestEngine.build( with( PageApiLimits.PROP_MAX_PAGE_BYTES, String.valueOf( LIMIT ) ),
                with( PageApiLimits.PROP_REQUIRE_EXPECTED_VERSION, "true" ) );
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
    }

    @AfterAll
    static void stopEngine() {
        if ( engine != null ) {
            engine.deleteQuietly( "SaveGuardOk", "SaveGuardExact", "SaveGuardOver", "SaveGuardMulti", "SaveGuardMultiOver",
                    "SaveGuardVersioned", "SaveGuardName" );
            engine.stop();
        }
    }

    private JsonObject put( final String pageName, final JsonObject body ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + pageName );
        Mockito.doReturn( "/" + pageName ).when( request ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        servlet.doPut( request, response );
        return gson.fromJson( sw.toString(), JsonObject.class );
    }

    private static JsonObject body( final String content, final Integer expectedVersion ) {
        final JsonObject b = new JsonObject();
        b.addProperty( "content", content );
        if ( expectedVersion != null ) {
            b.addProperty( "expectedVersion", expectedVersion );
        }
        return b;
    }

    private static boolean exists( final String name ) throws Exception {
        return engine.getManager( PageManager.class ).pageExists( name );
    }

    // ---- page-name allow-list

    @ParameterizedTest
    @ValueSource( strings = { "A", "Page With Space", "Café", "Tilde~Name", "Dot.Name", "a-b_c" } )
    void ordinaryNamesAreAccepted( final String name ) {
        assertFalse( PageSaveHandler.isInvalidPageName( name ), name );
    }

    @ParameterizedTest
    @ValueSource( strings = { "a:b", "a\\b", "a/b", "a\u0000b", "a\nb", "a\u001fb", "a\u007fb", "\t" } )
    void namesWithPathOrControlCharactersAreRefused( final String name ) {
        assertTrue( PageSaveHandler.isInvalidPageName( name ), name );
    }

    @Test
    void aNameIsRefusedWhenNullEmptyOrLongerThanTheLimitAndAcceptedAtTheLimit() {
        assertTrue( PageSaveHandler.isInvalidPageName( null ) );
        assertTrue( PageSaveHandler.isInvalidPageName( "" ) );
        assertFalse( PageSaveHandler.isInvalidPageName( "n".repeat( PageApiLimits.MAX_PAGE_NAME_LENGTH ) ) );
        assertTrue( PageSaveHandler.isInvalidPageName( "n".repeat( PageApiLimits.MAX_PAGE_NAME_LENGTH + 1 ) ) );
    }

    @Test
    void aPutToAnInvalidNameIs400AndWritesNothing() throws Exception {
        final JsonObject res = put( "SaveGuard:Name", body( "x", 1 ) );

        assertEquals( 400, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().contains( "Page name is invalid" ), res.toString() );
        assertFalse( exists( "SaveGuard:Name" ) );
    }

    // ---- byte limit

    @Test
    void contentOfExactlyTheLimitIsSavedAndOneByteMoreIs413() throws Exception {
        final JsonObject exact = put( "SaveGuardExact", body( "x".repeat( LIMIT ), 0 ) );
        assertTrue( exact.get( "success" ).getAsBoolean(), exact.toString() );
        assertTrue( exists( "SaveGuardExact" ) );

        final JsonObject over = put( "SaveGuardOver", body( "x".repeat( LIMIT + 1 ), 0 ) );
        assertEquals( 413, over.get( "status" ).getAsInt() );
        assertTrue( over.get( "message" ).getAsString().contains( "limit is " + LIMIT + " bytes" ), over.toString() );
        assertFalse( exists( "SaveGuardOver" ), "an oversize body must not be persisted" );
    }

    @Test
    void theLimitCountsUtf8BytesNotCharacters() throws Exception {
        // 10 x 'e-acute' = 10 chars but 20 bytes: fits. 11 = 22 bytes: refused although only 11 chars.
        assertTrue( put( "SaveGuardMulti", body( "é".repeat( 10 ), 0 ) ).get( "success" ).getAsBoolean() );
        final JsonObject over = put( "SaveGuardMultiOver", body( "é".repeat( 11 ), 0 ) );
        assertEquals( 413, over.get( "status" ).getAsInt() );
        assertFalse( exists( "SaveGuardMultiOver" ) );
    }

    // ---- strict expected-version mode

    @Test
    void withStrictModeAPutWithoutAnExpectedVersionIs400AndWritesNothing() throws Exception {
        final JsonObject res = put( "SaveGuardVersioned", body( "no version", null ) );

        assertEquals( 400, res.get( "status" ).getAsInt() );
        assertTrue( res.get( "message" ).getAsString().contains( PageApiLimits.PROP_REQUIRE_EXPECTED_VERSION ), res.toString() );
        assertFalse( exists( "SaveGuardVersioned" ) );
    }

    @Test
    void withStrictModeANegativeExpectedVersionCountsAsMissing() throws Exception {
        assertEquals( 400, put( "SaveGuardVersioned", body( "negative", -1 ) ).get( "status" ).getAsInt() );
        assertFalse( exists( "SaveGuardVersioned" ) );
    }

    @Test
    void withStrictModeAnExpectedVersionOfZeroIsAcceptedForANewPage() throws Exception {
        final JsonObject res = put( "SaveGuardOk", body( "fresh", 0 ) );
        assertTrue( res.get( "success" ).getAsBoolean(), res.toString() );
        assertTrue( exists( "SaveGuardOk" ) );
    }

    // ---- the configuration readers

    @Test
    void theLimitAndStrictModeAreReadFromTheEngineConfiguration() {
        assertEquals( LIMIT, PageSaveHandler.parseMaxPageBytes( engine ) );
        assertTrue( PageSaveHandler.isExpectedVersionRequired( engine ) );
    }

    @Test
    void withoutAnEngineTheDefaultsApply() {
        assertEquals( PageApiLimits.DEFAULT_MAX_PAGE_BYTES, PageSaveHandler.parseMaxPageBytes( null ) );
        assertFalse( PageSaveHandler.isExpectedVersionRequired( null ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "   ", "abc", "0", "-5", "12.5" } )
    void anUnusableLimitFallsBackToTheDefault( final String configured ) {
        final TestEngine e = TestEngine.build( with( PageApiLimits.PROP_MAX_PAGE_BYTES, configured ) );
        try {
            assertEquals( PageApiLimits.DEFAULT_MAX_PAGE_BYTES, PageSaveHandler.parseMaxPageBytes( e ), configured );
        } finally {
            e.stop();
        }
    }

    @Test
    void aPaddedPositiveLimitIsAccepted() {
        final TestEngine e = TestEngine.build( with( PageApiLimits.PROP_MAX_PAGE_BYTES, " 1234 " ) );
        try {
            assertEquals( 1234, PageSaveHandler.parseMaxPageBytes( e ) );
        } finally {
            e.stop();
        }
    }

    @Test
    void strictModeIsOffUnlessConfiguredTrue() {
        final TestEngine off = TestEngine.build();
        final TestEngine padded = TestEngine.build( with( PageApiLimits.PROP_REQUIRE_EXPECTED_VERSION, " true " ) );
        try {
            assertFalse( PageSaveHandler.isExpectedVersionRequired( off ) );
            assertTrue( PageSaveHandler.isExpectedVersionRequired( padded ) );
        } finally {
            off.stop();
            padded.stop();
        }
    }
}
