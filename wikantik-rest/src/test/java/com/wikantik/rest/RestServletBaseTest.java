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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * Exercises the CORS handling in {@link RestServletBase}. Uses a tiny concrete
 * subclass so we can drive the protected {@code setCorsHeaders} helper without
 * spinning up a full servlet container.
 */
class RestServletBaseTest {

    private static class TestRestServlet extends RestServletBase {
        private final WikiEngine engine;
        TestRestServlet( final WikiEngine engine ) { this.engine = engine; }
        @Override protected Engine getEngine() { return engine; }
        // Expose the package-private helper for tests.
        void applyCors( final HttpServletRequest req, final HttpServletResponse resp ) {
            setCorsHeaders( req, resp );
        }
        String callRequirePathParam( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
            return requirePathParam( req, resp );
        }
        Page callRequirePage( final HttpServletRequest req, final HttpServletResponse resp, final String name ) throws IOException {
            return requirePage( req, resp, name );
        }
        JsonObject callParseJsonBody( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
            return parseJsonBody( req, resp );
        }
        boolean callGetJsonBoolean( final JsonObject obj, final String key, final boolean def ) {
            return getJsonBoolean( obj, key, def );
        }
        double callGetJsonDouble( final JsonObject obj, final String key, final double def ) {
            return getJsonDouble( obj, key, def );
        }
    }

    private WikiEngine engine;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock( WikiEngine.class );
        final Properties props = new Properties();
        props.setProperty( "wikantik.cors.allowedOrigins",
                "https://wiki.example.com,https://other.example.com" );
        Mockito.when( engine.getWikiProperties() ).thenReturn( props );
    }

    @Test
    void testAllowedOriginIsEchoed() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://wiki.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp ).setHeader( "Access-Control-Allow-Origin", "https://wiki.example.com" );
        Mockito.verify( resp ).setHeader( "Vary", "Origin" );
    }

    @Test
    void testDisallowedOriginNotEchoed() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://evil.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void testNoOriginHeaderEmitsNoCorsHeader() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( null );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void testCredentialsHeaderNeverSet() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( "https://wiki.example.com" );

        servlet.applyCors( req, resp );

        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Credentials" ), Mockito.anyString() );
    }

    // ---- Wildcard origin tests ----

    private TestRestServlet servletWithAllowed( final String allowed ) {
        final WikiEngine eng = Mockito.mock( WikiEngine.class );
        final Properties props = new Properties();
        props.setProperty( "wikantik.cors.allowedOrigins", allowed );
        Mockito.when( eng.getWikiProperties() ).thenReturn( props );
        return new TestRestServlet( eng );
    }

    private void assertEchoed( final String allowed, final String origin ) {
        final TestRestServlet servlet = servletWithAllowed( allowed );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( origin );
        servlet.applyCors( req, resp );
        Mockito.verify( resp ).setHeader( "Access-Control-Allow-Origin", origin );
    }

    private void assertNotEchoed( final String allowed, final String origin ) {
        final TestRestServlet servlet = servletWithAllowed( allowed );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getHeader( "Origin" ) ).thenReturn( origin );
        servlet.applyCors( req, resp );
        Mockito.verify( resp, Mockito.never() )
                .setHeader( Mockito.eq( "Access-Control-Allow-Origin" ), Mockito.anyString() );
    }

    @Test
    void wildcardMatchesSingleLabelSubdomain() {
        assertEchoed( "https://*.jakefear.com", "https://wiki.jakefear.com" );
    }

    @Test
    void wildcardMatchesMultiLabelSubdomain() {
        assertEchoed( "https://*.jakefear.com", "https://a.b.jakefear.com" );
    }

    @Test
    void wildcardDoesNotMatchApexDomain() {
        assertNotEchoed( "https://*.jakefear.com", "https://jakefear.com" );
    }

    @Test
    void wildcardDoesNotMatchSiblingHost() {
        assertNotEchoed( "https://*.jakefear.com", "https://evil-jakefear.com" );
    }

    @Test
    void wildcardDoesNotMatchSuffixTrick() {
        assertNotEchoed( "https://*.jakefear.com", "https://foo.jakefear.com.evil.com" );
    }

    @Test
    void wildcardRequiresSchemeMatch() {
        assertNotEchoed( "https://*.jakefear.com", "http://foo.jakefear.com" );
    }

    @Test
    void wildcardRejectsPathInjection() {
        assertNotEchoed( "https://*.jakefear.com", "https://attacker.com/.jakefear.com" );
    }

    @Test
    void wildcardAndExactCoexistInSameList() {
        final String list = "https://app.example.com,https://*.jakefear.com";
        assertEchoed( list, "https://app.example.com" );
        assertEchoed( list, "https://www.jakefear.com" );
        assertNotEchoed( list, "https://other.com" );
    }

    @Test
    void wildcardPortIsRespected() {
        assertEchoed( "https://*.jakefear.com:8443", "https://foo.jakefear.com:8443" );
        assertNotEchoed( "https://*.jakefear.com:8443", "https://foo.jakefear.com" );
    }

    // ---- New helper tests ----

    @Test
    void requirePathParam_nullPath_sends400AndReturnsNull() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        Mockito.when( req.getPathInfo() ).thenReturn( null );

        final String result = servlet.callRequirePathParam( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void requirePathParam_emptyPath_sends400AndReturnsNull() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );
        Mockito.when( req.getPathInfo() ).thenReturn( "/" );

        final String result = servlet.callRequirePathParam( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void requirePathParam_validPath_returnsValueWithoutSendingStatus() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getPathInfo() ).thenReturn( "/Main" );

        final String result = servlet.callRequirePathParam( req, resp );

        assertEquals( "Main", result );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void requirePage_missing_sends404AndReturnsNull() throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        Mockito.when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        Mockito.when( pm.getPage( "Ghost" ) ).thenReturn( null );

        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        final Page result = servlet.callRequirePage( req, resp, "Ghost" );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_NOT_FOUND );
    }

    @Test
    void requirePage_present_returnsPage() throws Exception {
        final Page page = Mockito.mock( Page.class );
        final PageManager pm = Mockito.mock( PageManager.class );
        Mockito.when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        Mockito.when( pm.getPage( "Main" ) ).thenReturn( page );

        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );

        final Page result = servlet.callRequirePage( req, resp, "Main" );

        assertNotNull( result );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    @Test
    void parseJsonBody_malformed_sends400WithExceptionDetail() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        Mockito.when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{bad json" ) ) );

        final JsonObject result = servlet.callParseJsonBody( req, resp );

        assertNull( result );
        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void utcIsoDateSerializer_emitsUtcRegardlessOfDefaultTimeZone() {
        // 2026-04-28T08:23:20Z = epoch millis 1777616600000
        final Date instant = new Date( 1777364600000L );
        final TimeZone original = TimeZone.getDefault();
        try {
            // Force a non-UTC default TZ. The bug we're locking in: setDateFormat()
            // would format these digits in CEST and append 'Z', producing wrong output.
            TimeZone.setDefault( TimeZone.getTimeZone( "Europe/Berlin" ) );
            final Gson gson = new GsonBuilder()
                    .registerTypeAdapter( Date.class, RestServletBase.UTC_ISO_DATE_SERIALIZER )
                    .create();
            assertEquals( "\"2026-04-28T08:23:20Z\"", gson.toJson( instant ) );

            TimeZone.setDefault( TimeZone.getTimeZone( "America/Los_Angeles" ) );
            final Gson gson2 = new GsonBuilder()
                    .registerTypeAdapter( Date.class, RestServletBase.UTC_ISO_DATE_SERIALIZER )
                    .create();
            assertEquals( "\"2026-04-28T08:23:20Z\"", gson2.toJson( instant ) );
        } finally {
            TimeZone.setDefault( original );
        }
    }

    @Test
    void parseJsonBody_valid_returnsObject() throws Exception {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        Mockito.when( req.getReader() ).thenReturn( new BufferedReader( new StringReader( "{\"x\":1}" ) ) );

        final JsonObject result = servlet.callParseJsonBody( req, resp );

        assertNotNull( result );
        assertEquals( 1, result.get( "x" ).getAsInt() );
        Mockito.verify( resp, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    // ----- getJsonBoolean -----

    @Test
    void getJsonBoolean_returnsActualValueWhenPresent() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();
        obj.addProperty( "flag", true );

        assertEquals( true, servlet.callGetJsonBoolean( obj, "flag", false ) );
    }

    @Test
    void getJsonBoolean_returnsDefaultWhenKeyAbsent() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();

        assertEquals( true, servlet.callGetJsonBoolean( obj, "missing", true ) );
    }

    @Test
    void getJsonBoolean_returnsDefaultWhenValueIsNonPrimitive() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();
        obj.add( "flag", new JsonObject() );

        assertEquals( false, servlet.callGetJsonBoolean( obj, "flag", false ) );
    }

    // ----- getJsonDouble -----

    @Test
    void getJsonDouble_returnsActualValueWhenPresent() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();
        obj.addProperty( "score", 3.5 );

        assertEquals( 3.5, servlet.callGetJsonDouble( obj, "score", -1.0 ) );
    }

    @Test
    void getJsonDouble_returnsDefaultWhenKeyAbsent() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();

        assertEquals( -1.0, servlet.callGetJsonDouble( obj, "missing", -1.0 ) );
    }

    @Test
    void getJsonDouble_returnsDefaultWhenValueIsNotParseableNumber() {
        final TestRestServlet servlet = new TestRestServlet( engine );
        final JsonObject obj = new JsonObject();
        obj.addProperty( "score", "not-a-number" );

        assertEquals( -1.0, servlet.callGetJsonDouble( obj, "score", -1.0 ) );
    }

    // ----- isSafeHeaderValue / originMatches -----

    @Test
    void isSafeHeaderValue_rejectsValueContainingCarriageReturn() {
        assertEquals( false, RestServletBase.isSafeHeaderValue( "https://evil.example.com\r\nSet-Cookie: x=1" ) );
    }

    @Test
    void isSafeHeaderValue_acceptsOrdinaryOrigin() {
        assertEquals( true, RestServletBase.isSafeHeaderValue( "https://wiki.example.com" ) );
    }

    @Test
    void originMatches_rejectsEmptyCandidate() {
        assertEquals( false, RestServletBase.originMatches( "https://wiki.example.com", "" ) );
    }

    // ----- sanitizeParseError -----

    @Test
    void sanitizeParseError_returnsFallbackMessageForNullInput() {
        assertEquals( "could not parse body as JSON object", RestServletBase.sanitizeParseError( null ) );
    }

    @Test
    void sanitizeParseError_returnsFallbackMessageForEmptyInput() {
        assertEquals( "could not parse body as JSON object", RestServletBase.sanitizeParseError( "" ) );
    }

    @Test
    void sanitizeParseError_returnsFallbackWhenMessageBecomesEmptyAfterStripping() {
        // The message is entirely made of tokens the sanitizer strips (a URL and the
        // word "gson"), so after stripping only whitespace remains — the fallback
        // message must be returned rather than a blank string.
        assertEquals( "could not parse body as JSON object",
                RestServletBase.sanitizeParseError( "https://example.com/gson  gson" ) );
    }

    @Test
    void sanitizeParseError_stripsUrlsAndGsonReferencesAndClassNames() {
        final String raw = "com.google.gson.JsonSyntaxException: Expected STRING but was NUMBER "
                + "at line 1 column 5 path $.name (see https://github.com/google/gson for details)";

        final String sanitized = RestServletBase.sanitizeParseError( raw );

        assertEquals( -1, sanitized.toLowerCase( java.util.Locale.ROOT ).indexOf( "gson" ) );
        assertEquals( -1, sanitized.indexOf( "https://" ) );
        assertEquals( -1, sanitized.indexOf( "com.google" ) );
    }
}
