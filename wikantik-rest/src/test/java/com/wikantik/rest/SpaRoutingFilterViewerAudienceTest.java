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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSubsystems;
import com.wikantik.api.core.Session;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server-rendered document of a page whose content depends on the caller (a hub's JSON-LD {@code hasPart}, a
 * page-listing plugin, a transclusion) must hold only what that caller may view, and must never be handed to a
 * different caller by a cache. Anonymous visitors are one audience, so their copy stays revalidatable; a signed-in
 * caller's copy is never stored.
 */
class SpaRoutingFilterViewerAudienceTest {

    private static final String FAKE_INDEX_HTML = "<!DOCTYPE html>\n<html><head>\n<title>Wikantik</title>\n"
            + "<script type=\"module\" crossorigin src=\"/assets/index-ABC.js\"></script>\n"
            + "</head><body><div id=\"root\"></div>\n</body></html>";

    private TestEngine engine;
    private SpaRoutingFilter filter;
    private ServletContext servletContext;
    private final FilterChain chain = mock( FilterChain.class );

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build( with( "wikantik.access.privateClusters", "spa-secret" ) );
        engine.saveText( "SpaPubHub", "---\ntype: hub\ncluster: spa-pub\n---\nThe public hub.\n" );
        engine.saveText( "SpaPubMember", "---\ncluster: spa-pub\n---\nA public member.\n" );
        engine.saveText( "SpaPrivMember", "---\ncluster: [spa-pub, spa-secret]\n---\nA private member.\n" );
        engine.saveText( "SpaIndexHost", "Index: [{IndexPlugin include='Spa.*'}]" );
        engine.saveText( "SpaPlainPage", "Nothing per viewer here." );

        servletContext = mock( ServletContext.class );
        when( servletContext.getResourceAsStream( "/index.html" ) )
                .thenAnswer( inv -> new ByteArrayInputStream( FAKE_INDEX_HTML.getBytes( StandardCharsets.UTF_8 ) ) );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.getCluster( "spa-pub" ) ).thenReturn( Optional.of( new ClusterDetails( "spa-pub",
                descriptor( "SpaPubHub", PageType.HUB ),
                List.of( descriptor( "SpaPubMember", PageType.ARTICLE ), descriptor( "SpaPrivMember", PageType.ARTICLE ) ),
                Map.of(), Instant.now() ) ) );
        final PageGraphSubsystem.Services pageGraph =
                new PageGraphSubsystem.Services( idx, null, null, null, null, null, null, null, null );
        when( servletContext.getAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE ) )
                .thenReturn( new WikiSubsystems( null, null, null, null, null, null, null, pageGraph ) );

        filter = new SpaRoutingFilter();
        filter.init( null );
        filter.setEngineForTest( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private static PageDescriptor descriptor( final String slug, final PageType type ) {
        return new PageDescriptor( "01H8G3Z1K6Q5W7P9X2V4R0T8" + slug.length() + "X", slug, slug, type,
                                   "spa-pub", List.of(), null, Instant.now(), false );
    }

    private record Served( HttpServletResponse response, CapturingOutput body ) {}

    private Served serve( final HttpServletRequest request ) throws Exception {
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final CapturingOutput out = new CapturingOutput();
        when( response.getOutputStream() ).thenReturn( out );
        filter.doFilter( request, response, chain );
        return new Served( response, out );
    }

    private HttpServletRequest anonymousRequest( final String uri ) {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( "" );
        when( request.getRequestURI() ).thenReturn( uri );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html" );
        when( request.getServletContext() ).thenReturn( servletContext );
        return request;
    }

    private HttpServletRequest adminRequest( final String uri ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( uri );
        when( request.getContextPath() ).thenReturn( "" );
        when( request.getRequestURI() ).thenReturn( uri );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html" );
        when( request.getServletContext() ).thenReturn( servletContext );
        final Session session = Wiki.session().find( engine, request );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return request;
    }

    @Test
    void hubHasPartNamesOnlyMembersTheCallerMayView() throws Exception {
        final String guest = serve( anonymousRequest( "/wiki/SpaPubHub" ) ).body().asString();
        assertTrue( guest.contains( "\"hasPart\"" ) && guest.contains( "SpaPubMember" ), guest );
        assertFalse( guest.contains( "SpaPrivMember" ), "a private member of a public hub leaked: " + guest );

        final String admin = serve( adminRequest( "/wiki/SpaPubHub" ) ).body().asString();
        assertTrue( admin.contains( "SpaPrivMember" ), "control: admin's hasPart names the private member" );
    }

    @Test
    void anonymousHubResponseStaysRevalidatableUnderItsOwnValidator() throws Exception {
        final Served guest = serve( anonymousRequest( "/wiki/SpaPubHub" ) );
        final ArgumentCaptor< String > etag = ArgumentCaptor.forClass( String.class );
        verify( guest.response() ).setHeader( eq( "ETag" ), etag.capture() );
        assertTrue( etag.getValue().endsWith( "-a\"" ), "anonymous copy carries its audience: " + etag.getValue() );
        verify( guest.response() ).setHeader( "Cache-Control", "private, no-cache" );

        final HttpServletRequest again = anonymousRequest( "/wiki/SpaPubHub" );
        when( again.getHeader( "If-None-Match" ) ).thenReturn( etag.getValue() );
        verify( serve( again ).response() ).setStatus( HttpServletResponse.SC_NOT_MODIFIED );
    }

    @Test
    void signedInHubResponseIsNeverStoredNorRevalidatedAgainstTheAnonymousCopy() throws Exception {
        final Served guest = serve( anonymousRequest( "/wiki/SpaPubHub" ) );
        final ArgumentCaptor< String > etag = ArgumentCaptor.forClass( String.class );
        verify( guest.response() ).setHeader( eq( "ETag" ), etag.capture() );

        final HttpServletRequest admin = adminRequest( "/wiki/SpaPubHub" );
        when( admin.getHeader( "If-None-Match" ) ).thenReturn( etag.getValue() );
        final Served served = serve( admin );
        verify( served.response(), never() ).setStatus( HttpServletResponse.SC_NOT_MODIFIED );
        verify( served.response(), never() ).setHeader( eq( "ETag" ), any() );
        verify( served.response() ).setHeader( "Cache-Control", "private, no-store" );
    }

    @Test
    void pageWithAListingPluginIsPerAudience() throws Exception {
        final Served guest = serve( anonymousRequest( "/wiki/SpaIndexHost" ) );
        verify( guest.response() ).setHeader( eq( "ETag" ), any() );
        assertFalse( guest.body().asString().contains( "SpaPrivMember" ), guest.body().asString() );

        final Served admin = serve( adminRequest( "/wiki/SpaIndexHost" ) );
        verify( admin.response() ).setHeader( "Cache-Control", "private, no-store" );
        assertTrue( admin.body().asString().contains( "SpaPrivMember" ), "control: admin's index lists it" );
    }

    @Test
    void plainPageKeepsOneValidatorForEveryCaller() throws Exception {
        final Served guest = serve( anonymousRequest( "/wiki/SpaPlainPage" ) );
        final ArgumentCaptor< String > etag = ArgumentCaptor.forClass( String.class );
        verify( guest.response() ).setHeader( eq( "ETag" ), etag.capture() );
        assertTrue( etag.getValue().endsWith( "-v\"" ), etag.getValue() );

        final Served admin = serve( adminRequest( "/wiki/SpaPlainPage" ) );
        verify( admin.response() ).setHeader( "ETag", etag.getValue() );
        verify( admin.response() ).setHeader( "Cache-Control", "private, no-cache" );
    }

    private static final class CapturingOutput extends ServletOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override public void write( final int b ) { buffer.write( b ); }
        @Override public void write( final byte[] b, final int off, final int len ) { buffer.write( b, off, len ); }
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener( final WriteListener writeListener ) {
            // blocking-style test stream
        }

        String asString() {
            return buffer.toString( StandardCharsets.UTF_8 );
        }
    }
}
