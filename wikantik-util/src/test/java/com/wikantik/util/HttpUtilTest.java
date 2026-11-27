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
package com.wikantik.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class HttpUtilTest {

    @Test
    public void testIsIPV4Address() {
        Assertions.assertFalse( HttpUtil.isIPV4Address( null ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( ".123.123.123.123" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "123.123.123.123." ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "123.123.123" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "123.123.123.123.123" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "abc.123.123.123" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "Me" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "Guest" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "1207.0.0.1" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "127..0.1" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "1207.0.0." ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( ".0.0.1" ) );
        Assertions.assertFalse( HttpUtil.isIPV4Address( "..." ) );

        Assertions.assertTrue( HttpUtil.isIPV4Address( "127.0.0.1" ) );
        Assertions.assertTrue( HttpUtil.isIPV4Address( "12.123.123.123" ) );
        Assertions.assertTrue( HttpUtil.isIPV4Address( "012.123.123.123" ) );
        Assertions.assertTrue( HttpUtil.isIPV4Address( "123.123.123.123" ) );
    }

    @Test
    public void testRetrieveCookieValue() {
        final Cookie[] cookies = new Cookie[] { new Cookie( "cookie1", "value1" ),
                                                new Cookie( "cookie2", "\"value2\"" ),
                                                new Cookie( "cookie3", "" ),
                                                new Cookie( "cookie4", null ) };
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( cookies ).when( req ).getCookies();

        assertEquals( "value1", HttpUtil.retrieveCookieValue( req, "cookie1" ) );
        assertEquals( "value2", HttpUtil.retrieveCookieValue( req, "cookie2" ) );
        Assertions.assertNull( HttpUtil.retrieveCookieValue( req, "cookie3" ) );
        Assertions.assertNull( HttpUtil.retrieveCookieValue( req, "cookie4" ) );
        Assertions.assertNull( HttpUtil.retrieveCookieValue( req, "cookie5" ) );
    }

    @Test
    public void testGetAbsoluteUrlWithRelativeUrl() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);

        String relativeUrl = "/login";
        String expected = "http://localhost:8080/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithoutRelativeUrl() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(443);

        String expected = "https://localhost";

        String actual = HttpUtil.getAbsoluteUrl(request);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithDefaultHttpPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);

        String relativeUrl = "/login";
        String expected = "http://localhost/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithDefaultHttpsPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(443);

        String relativeUrl = "/login";
        String expected = "https://localhost/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithForwardedHostAndProto() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-Host")).thenReturn("proxyhost");
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");

        String relativeUrl = "/login";
        String expected = "https://proxyhost/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithForwardedServerAndProto() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-Server")).thenReturn("proxyserver");
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");

        String relativeUrl = "/login";
        String expected = "https://proxyserver/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithNoForwardedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);

        String relativeUrl = "/login";
        String expected = "http://localhost:8080/login";

        String actual = HttpUtil.getAbsoluteUrl(request, relativeUrl);
        assertEquals(expected, actual);
    }

    @Test
    public void testGetAbsoluteUrlWithAllHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-Host")).thenReturn("forwardedHost");
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("forwardedProto");
        when(request.getHeader("X-Forwarded-Server")).thenReturn("forwardedServer");
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(443);

        String expected = "forwardedProto://forwardedHost";

        String actual = HttpUtil.getAbsoluteUrl(request);
        assertEquals(expected, actual);
    }

    // --- guessValidURI tests ---

    @Test
    public void testGuessValidURIEmail() {
        assertEquals( "mailto:user@example.com", HttpUtil.guessValidURI( "user@example.com" ) );
    }

    @Test
    public void testGuessValidURIEmailAlreadyMailto() {
        assertEquals( "mailto:user@example.com", HttpUtil.guessValidURI( "mailto:user@example.com" ) );
    }

    @Test
    public void testGuessValidURIPlainDomain() {
        assertEquals( "http://example.com", HttpUtil.guessValidURI( "example.com" ) );
    }

    @Test
    public void testGuessValidURIHttpAlreadyPresent() {
        assertEquals( "http://example.com", HttpUtil.guessValidURI( "http://example.com" ) );
    }

    @Test
    public void testGuessValidURIHttpsAlreadyPresent() {
        assertEquals( "https://example.com", HttpUtil.guessValidURI( "https://example.com" ) );
    }

    @Test
    public void testGuessValidURIEmpty() {
        // Empty string should stay empty (no protocol added per notBeginningWithHttpOrHttps logic)
        assertEquals( "", HttpUtil.guessValidURI( "" ) );
    }

    // --- notBeginningWithHttpOrHttps tests ---

    @Test
    public void testNotBeginningWithHttpOrHttps() {
        assertTrue( HttpUtil.notBeginningWithHttpOrHttps( "ftp://example.com" ) );
        assertFalse( HttpUtil.notBeginningWithHttpOrHttps( "http://example.com" ) );
        assertFalse( HttpUtil.notBeginningWithHttpOrHttps( "https://example.com" ) );
        assertFalse( HttpUtil.notBeginningWithHttpOrHttps( "" ) );
    }

    // --- isIPV4Address boundary tests ---

    @Test
    public void testIsIPV4AddressBoundaryValues() {
        assertTrue( HttpUtil.isIPV4Address( "255.255.255.255" ) );
        assertFalse( HttpUtil.isIPV4Address( "256.1.1.1" ) );
    }

    // --- retrieveCookieValue with no cookies ---

    @Test
    public void testRetrieveCookieValueNoCookies() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getCookies() ).thenReturn( null );
        assertNull( HttpUtil.retrieveCookieValue( req, "anyCookie" ) );
    }

    // --- createETag tests ---

    @Test
    public void testCreateETag() {
        Date now = new Date();
        String tag = HttpUtil.createETag( "TestPage", now );
        assertEquals( Long.toString( "TestPage".hashCode() ^ now.getTime() ), tag );
        // Should be deterministic for same inputs
        assertEquals( tag, HttpUtil.createETag( "TestPage", now ) );
        // Different page names should produce different ETags
        assertNotEquals( tag, HttpUtil.createETag( "OtherPage", now ) );
    }

    // --- checkFor304 tests ---

    @Test
    public void testCheckFor304WithPragmaNoCache() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Pragma" ) ).thenReturn( "no-cache" );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date() ) );
    }

    @Test
    public void testCheckFor304WithCacheControlNoCache() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "cache-control" ) ).thenReturn( "no-cache" );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date() ) );
    }

    @Test
    public void testCheckFor304WithMatchingETag() {
        Date lastModified = new Date();
        String etag = HttpUtil.createETag( "Page", lastModified );
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "If-None-Match" ) ).thenReturn( etag );
        assertTrue( HttpUtil.checkFor304( req, "Page", lastModified ) );
    }

    @Test
    public void testCheckFor304WithNonMatchingETag() {
        Date lastModified = new Date();
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "If-None-Match" ) ).thenReturn( "wrong-etag" );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );
        assertFalse( HttpUtil.checkFor304( req, "Page", lastModified ) );
    }

    @Test
    public void testCheckFor304WithIfModifiedSinceInFuture() {
        Date lastModified = new Date( 1000000L );
        HttpServletRequest req = mock( HttpServletRequest.class );
        // ifModifiedSince (2000000) is after lastModified (1000000) => page not modified => 304
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( 2000000L );
        assertTrue( HttpUtil.checkFor304( req, "Page", lastModified ) );
    }

    @Test
    public void testCheckFor304IfModifiedSinceNotModified() {
        Date lastModified = new Date( 1000000L );
        HttpServletRequest req = mock( HttpServletRequest.class );
        // ifModifiedSince is after lastModified => page not modified => 304
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( 2000000L );
        assertTrue( HttpUtil.checkFor304( req, "Page", lastModified ) );
    }

    @Test
    public void testCheckFor304IfModifiedSinceModified() {
        Date lastModified = new Date( 3000000L );
        HttpServletRequest req = mock( HttpServletRequest.class );
        // ifModifiedSince is before lastModified => page was modified => NOT 304
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( 1000000L );
        assertFalse( HttpUtil.checkFor304( req, "Page", lastModified ) );
    }

    // --- safeGetQueryString tests ---

    @Test
    public void testSafeGetQueryStringNullRequest() {
        assertEquals( "", HttpUtil.safeGetQueryString( null, StandardCharsets.UTF_8 ) );
    }

    @Test
    public void testSafeGetQueryStringNullQueryString() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getQueryString() ).thenReturn( null );
        assertNull( HttpUtil.safeGetQueryString( req, StandardCharsets.UTF_8 ) );
    }

    @Test
    public void testSafeGetQueryStringRemovesPageParam() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getQueryString() ).thenReturn( "page=Main&action=view" );
        String result = HttpUtil.safeGetQueryString( req, StandardCharsets.UTF_8 );
        assertFalse( result.contains( "page=" ) );
        assertTrue( result.contains( "action=view" ) );
    }

    @Test
    public void testSafeGetQueryStringNoPageParam() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getQueryString() ).thenReturn( "action=view&mode=raw" );
        String result = HttpUtil.safeGetQueryString( req, StandardCharsets.UTF_8 );
        assertEquals( "action=view&mode=raw", result );
    }

    @Test
    public void testSafeGetQueryStringPageParamOnly() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getQueryString() ).thenReturn( "page=Main" );
        String result = HttpUtil.safeGetQueryString( req, StandardCharsets.UTF_8 );
        assertEquals( "", result );
    }

    // --- getRemoteAddress tests ---

    @Test
    public void testGetRemoteAddressNoForwardedHeader() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        assertEquals( "192.168.1.1", HttpUtil.getRemoteAddress( req ) );
    }

    @Test
    public void testGetRemoteAddressWithForwardedHeader() {
        HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "X-Forwarded-For" ) ).thenReturn( "10.0.0.1" );
        assertEquals( "10.0.0.1", HttpUtil.getRemoteAddress( req ) );
    }

    // --- clearCookie tests ---

    @Test
    public void testClearCookie() {
        HttpServletResponse resp = mock( HttpServletResponse.class );
        HttpUtil.clearCookie( resp, "myCookie" );
        verify( resp ).addCookie( Mockito.argThat( cookie ->
            cookie.getName().equals( "myCookie" ) && cookie.getMaxAge() == 0 ) );
    }

    private static HttpServletRequest requestFrom( final String remoteAddr ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( remoteAddr );
        return req;
    }

    // ---- ipIsInRange

    @Test
    void ipIsInRangeAcceptsAnAddressInsideACidrRange() {
        assertTrue( HttpUtil.ipIsInRange( requestFrom( "10.1.2.3" ), "10.1.0.0/16" ) );
    }

    @Test
    void ipIsInRangeRefusesAnAddressOutsideACidrRange() {
        assertFalse( HttpUtil.ipIsInRange( requestFrom( "10.2.2.3" ), "10.1.0.0/16" ) );
    }

    @Test
    void ipIsInRangeComparesAPlainAddressForEquality() {
        assertTrue( HttpUtil.ipIsInRange( requestFrom( "192.168.1.5" ), "192.168.1.5" ) );
        assertFalse( HttpUtil.ipIsInRange( requestFrom( "192.168.1.6" ), "192.168.1.5" ) );
    }

    // ---- getRemoteAddress

    @Test
    void getRemoteAddressTakesTheFirstAddressOfAForwardedList() {
        final HttpServletRequest req = requestFrom( "127.0.0.1" );
        when( req.getHeader( "X-Forwarded-For" ) ).thenReturn( "1.2.3.4, 5.6.7.8" );
        assertEquals( "1.2.3.4", HttpUtil.getRemoteAddress( req ) );
    }

    // ---- ETag and conditional GET

    @Test
    void createETagXorsTheNameHashWithTheModificationTime() {
        // "A".hashCode() == 65; 65 ^ 3 == 66 (an AND would give 1, an OR 67)
        assertEquals( "66", HttpUtil.createETag( "A", new Date( 3L ) ) );
    }

    @Test
    void anIfModifiedSinceDateHeaderAtOrAfterTheModificationTimeIsNotModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( 2_000L );
        assertTrue( HttpUtil.checkFor304( req, "Page", new Date( 1_000L ) ) );
        assertTrue( HttpUtil.checkFor304( req, "Page", new Date( 2_000L ) ) );
    }

    @Test
    void anIfModifiedSinceDateHeaderBeforeTheModificationTimeIsModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( 1_000L );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date( 2_000L ) ) );
    }

    @Test
    void aMalformedIfModifiedSinceHeaderMeansModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenThrow( new IllegalArgumentException( "bad date" ) );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date( 2_000L ) ) );
    }

    @Test
    void noIfModifiedSinceHeaderAtAllMeansModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date( 2_000L ) ) );
    }

    @Test
    void anRfcIfModifiedSinceStringAfterTheModificationTimeIsNotModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );
        when( req.getHeader( "If-Modified-Since" ) ).thenReturn( "Wed, 21 Oct 2015 07:28:00 GMT" );
        assertTrue( HttpUtil.checkFor304( req, "Page", new Date( 0L ) ) );
    }

    @Test
    void anRfcIfModifiedSinceStringBeforeTheModificationTimeIsModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );
        when( req.getHeader( "If-Modified-Since" ) ).thenReturn( "Wed, 21 Oct 2015 07:28:00 GMT" );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date( 2_000_000_000_000L ) ) );
    }

    @Test
    void anUnparseableIfModifiedSinceStringMeansModified() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );
        when( req.getHeader( "If-Modified-Since" ) ).thenReturn( "yesterday-ish" );
        assertFalse( HttpUtil.checkFor304( req, "Page", new Date( 0L ) ) );
    }

    @Test
    void cacheControlNoCacheDefeatsAMatchingETag() {
        final Date modified = new Date( 5_000L );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "If-None-Match" ) ).thenReturn( HttpUtil.createETag( "Page", modified ) );
        assertTrue( HttpUtil.checkFor304( req, "Page", modified ) );
        when( req.getHeader( "cache-control" ) ).thenReturn( "no-cache" );
        assertFalse( HttpUtil.checkFor304( req, "Page", modified ) );
    }

    // ---- safeGetQueryString

    private static String queryOf( final String query ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getQueryString() ).thenReturn( query );
        return HttpUtil.safeGetQueryString( req, StandardCharsets.UTF_8 );
    }

    @Test
    void safeGetQueryStringRemovesThePageParameterAtTheStart() {
        assertEquals( "a=b", queryOf( "page=Foo&a=b" ) );
    }

    @Test
    void safeGetQueryStringRemovesThePageParameterInTheMiddle() {
        assertEquals( "x=1&y=2", queryOf( "x=1&page=Foo&y=2" ) );
    }

    @Test
    void safeGetQueryStringRemovesALonePageParameter() {
        assertEquals( "", queryOf( "page=Foo" ) );
    }

    // ---- isIPV4Address

    @Test
    void isIPV4AddressRecognisesTheDigitZeroAndNine() {
        assertTrue( HttpUtil.isIPV4Address( "0.0.0.0" ) );
        assertTrue( HttpUtil.isIPV4Address( "9.9.9.9" ) );
        assertTrue( HttpUtil.isIPV4Address( "255.255.255.255" ) );
    }

    @Test
    void isIPV4AddressRefusesEmptyNullAndEdgeDots() {
        assertFalse( HttpUtil.isIPV4Address( "" ) );
        assertFalse( HttpUtil.isIPV4Address( null ) );
        assertFalse( HttpUtil.isIPV4Address( ".1.2.3" ) );
        assertFalse( HttpUtil.isIPV4Address( "1.2.3." ) );
    }

    @Test
    void isIPV4AddressRefusesTwoDotsInARow() {
        assertFalse( HttpUtil.isIPV4Address( "1..2.3" ) );
    }

    @Test
    void isIPV4AddressRefusesCharactersAdjacentToTheDigitRange() {
        assertFalse( HttpUtil.isIPV4Address( "/.1.2.3" ) );
        assertFalse( HttpUtil.isIPV4Address( ":.1.2.3" ) );
        assertFalse( HttpUtil.isIPV4Address( "1.2.3.a" ) );
    }

    @Test
    void isIPV4AddressRefusesAnOctetOverTwoFiftyFive() {
        assertFalse( HttpUtil.isIPV4Address( "256.1.1.1" ) );
    }

    // ---- clearCookie

    @Test
    void clearCookieExpiresTheCookieAndKeepsItHttpOnlySecureAndSameSiteStrict() {
        final HttpServletResponse response = mock( HttpServletResponse.class );
        HttpUtil.clearCookie( response, "sid" );
        final ArgumentCaptor< Cookie > captor = ArgumentCaptor.forClass( Cookie.class );
        verify( response ).addCookie( captor.capture() );
        final Cookie cookie = captor.getValue();
        assertEquals( "sid", cookie.getName() );
        assertEquals( "", cookie.getValue() );
        assertEquals( 0, cookie.getMaxAge() );
        assertTrue( cookie.isHttpOnly() );
        assertTrue( cookie.getSecure() );
        assertEquals( "Strict", cookie.getAttribute( "SameSite" ) );
    }

    // ---- getAbsoluteUrl

    private static HttpServletRequest direct( final String scheme, final int port ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getScheme() ).thenReturn( scheme );
        when( req.getServerName() ).thenReturn( "wiki.example" );
        when( req.getServerPort() ).thenReturn( port );
        return req;
    }

    @Test
    void getAbsoluteUrlOmitsTheDefaultPortOfEachScheme() {
        assertEquals( "http://wiki.example/p", HttpUtil.getAbsoluteUrl( direct( "http", 80 ), "/p" ) );
        assertEquals( "https://wiki.example/p", HttpUtil.getAbsoluteUrl( direct( "https", 443 ), "/p" ) );
    }

    @Test
    void getAbsoluteUrlKeepsANonDefaultPort() {
        assertEquals( "http://wiki.example:8080/p", HttpUtil.getAbsoluteUrl( direct( "http", 8080 ), "/p" ) );
        assertEquals( "https://wiki.example:8443/p", HttpUtil.getAbsoluteUrl( direct( "https", 8443 ), "/p" ) );
    }
}
