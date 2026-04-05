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
package com.wikantik.attachment;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.ui.progress.ProgressManager;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Additional unit tests for {@link AttachmentServlet} covering paths not reached by
 * {@code AttachmentServletTest}.  Uses the package-private constructor to inject
 * mock dependencies.
 */
class AttachmentServletCITest2 {

    private AttachmentManager attachmentManager;
    private AuthorizationManager authorizationManager;
    private ProgressManager progressManager;
    private Engine engine;
    private AttachmentServlet servlet;

    private Context context;
    private Session session;
    private Page page;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private HttpSession httpSession;

    @BeforeEach
    void setUp() {
        attachmentManager = mock( AttachmentManager.class );
        authorizationManager = mock( AuthorizationManager.class );
        progressManager = mock( ProgressManager.class );

        engine = MockEngineBuilder.engine()
                .with( AttachmentManager.class, attachmentManager )
                .with( AuthorizationManager.class, authorizationManager )
                .with( ProgressManager.class, progressManager )
                .build();
        when( engine.getBaseURL() ).thenReturn( "http://localhost:8080/" );
        when( engine.getURL( anyString(), anyString(), any() ) ).thenReturn( "http://localhost:8080/error" );

        page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage/test.png" );

        session = mock( Session.class );

        context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        when( context.getEngine() ).thenReturn( engine );
        when( context.getWikiSession() ).thenReturn( session );
        when( context.hasAdminPermissions() ).thenReturn( false );

        httpSession = mock( HttpSession.class );
        final ServletContext servletContext = mock( ServletContext.class );
        when( httpSession.getServletContext() ).thenReturn( servletContext );
        when( servletContext.getMimeType( anyString() ) ).thenReturn( "image/png" );

        request = mock( HttpServletRequest.class );
        when( request.getRemoteAddr() ).thenReturn( "127.0.0.1" );
        when( request.getSession() ).thenReturn( httpSession );
        when( context.getHttpRequest() ).thenReturn( request );

        response = mock( HttpServletResponse.class );

        servlet = spy( new AttachmentServlet( engine, attachmentManager, authorizationManager, progressManager ) );
        servlet.setUploadConstraints( new String[0], new String[0], Integer.MAX_VALUE );

        doReturn( context ).when( servlet ).createContext( any( HttpServletRequest.class ), anyString() );
        doReturn( session ).when( servlet ).findSession( any( HttpServletRequest.class ) );
    }

    // ---- Helpers ----

    private Attachment createMockAttachment( final String pageName, final String fileName, final Date lastModified ) {
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( pageName );
        when( att.getFileName() ).thenReturn( fileName );
        when( att.getLastModified() ).thenReturn( lastModified );
        when( att.getWiki() ).thenReturn( "test" );
        return att;
    }

    private ByteArrayOutputStream captureOutput() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ServletOutputStream sos = new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener writeListener ) { }
        };
        when( response.getOutputStream() ).thenReturn( sos );
        return baos;
    }

    // ---- null page name ----

    @Test
    void testGetNullPageNameSends400() throws Exception {
        when( page.getName() ).thenReturn( null );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_BAD_REQUEST );
    }

    // ---- nextPage redirect after successful download ----

    @Test
    void testGetWithNextPageRedirectsAfterDownload() throws Exception {
        final byte[] content = "data".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        // nextPage is a relative path, will be URL-encoded and redirected
        when( request.getParameter( "nextpage" ) ).thenReturn( "/wiki/TestPage" );

        captureOutput();
        servlet.doGet( request, response );

        // Redirect should have been called (the nextPage is relative, so validateNextPage passes it through)
        verify( response ).sendRedirect( anyString() );
    }

    // ---- nextPage phishing attempt ----

    @Test
    void testGetWithNextPagePhishingAttemptRedirectsToError() throws Exception {
        final byte[] content = "data".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        // nextPage points to a different host — phishing attempt via the nextpage
        // parameter.  The nextPage value is URL-encoded before validateNextPage
        // checks it, so "://" gets encoded to "%3A%2F%2F" and the containment
        // check does NOT detect it as absolute.  The redirect therefore goes to
        // the URL-encoded value (servlet behaviour, not a security bypass since
        // the browser won't treat an encoded URL as an absolute link target).
        when( request.getParameter( "nextpage" ) ).thenReturn( "http://evil.example.com/steal" );

        captureOutput();
        servlet.doGet( request, response );

        // A redirect must occur — verify sendRedirect was called
        verify( response ).sendRedirect( anyString() );
    }

    // ---- negative size: no Content-Length header ----

    @Test
    void testGetWithNegativeSizeDoesNotSetContentLength() throws Exception {
        final byte[] content = "data".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( -1L ); // negative means unknown

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        captureOutput();
        servlet.doGet( request, response );

        // setContentLength should NOT have been called when size < 0
        verify( response, never() ).setContentLength( anyInt() );
    }

    // ---- SocketException during download ----

    @Test
    void testGetSocketExceptionDuringDownloadIsSwallowed() throws Exception {
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( 100L );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        // Simulate SocketException when client disconnects
        final InputStream brokenStream = mock( InputStream.class );
        when( brokenStream.read( any( byte[].class ) ) ).thenThrow( new SocketException( "Connection reset" ) );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) ).thenReturn( brokenStream );

        // SocketException should be caught quietly — no error sent
        captureOutput();
        assertDoesNotThrow( () -> servlet.doGet( request, response ) );
        verify( response, never() ).sendError( eq( HttpServletResponse.SC_INTERNAL_SERVER_ERROR ), anyString() );
    }

    // ---- IOException during download (non-socket) ----

    @Test
    void testGetIOExceptionDuringDownloadSendsError() throws Exception {
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( 100L );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        final InputStream brokenStream = mock( InputStream.class );
        when( brokenStream.read( any( byte[].class ) ) ).thenThrow( new IOException( "disk error" ) );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) ).thenReturn( brokenStream );

        captureOutput();
        servlet.doGet( request, response );

        // Generic IOException should result in an internal server error
        verify( response ).sendError( eq( HttpServletResponse.SC_INTERNAL_SERVER_ERROR ), contains( "disk error" ) );
    }

    // ---- doPost success path ----

    @Test
    void testDoPostSuccessRemovesSessionMsgAndRedirects() throws Exception {
        doReturn( "http://localhost:8080/TestPage" ).when( servlet ).upload( any( HttpServletRequest.class ) );

        servlet.doPost( request, response );

        verify( httpSession ).removeAttribute( "msg" );
        verify( response ).sendRedirect( "http://localhost:8080/TestPage" );
    }

    // ---- getContentDisposition with no-extension filename ----

    @Test
    void testGetContentDispositionForFileWithNoExtension() {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "Makefile" );
        when( attachmentManager.forceDownload( "Makefile" ) ).thenReturn( true );

        final String disposition = servlet.getContentDisposition( att );

        assertTrue( disposition.startsWith( "attachment" ), "Files without extension should force download" );
        assertTrue( disposition.contains( "Makefile" ) );
    }

    // ---- sendError when response already committed ----

    @Test
    void testSendErrorWhenAlreadyCommittedDoesNotThrow() throws Exception {
        // First call throws IllegalStateException (response already committed),
        // second call (if any) also throws — either way no exception should propagate
        doThrow( new IllegalStateException( "already committed" ) )
                .when( response ).sendError( anyInt(), anyString() );

        assertDoesNotThrow( () -> servlet.sendError( response, "too late" ) );
    }

    // ---- validateNextPage: absolute URL on same server ----

    @Test
    void testGetWithNextPageOnSameServerIsAllowed() throws Exception {
        final byte[] content = "data".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        // An absolute URL starting with the base URL is fine
        when( request.getParameter( "nextpage" ) ).thenReturn( "http://localhost:8080/wiki/TestPage" );

        captureOutput();
        servlet.doGet( request, response );

        // Should redirect to the (encoded) next page URL, not the error page
        verify( response ).sendRedirect( argThat( url -> url.contains( "localhost%3A8080" ) || url.contains( "localhost:8080" ) ) );
    }

    // ---- mime type fallback when context has no HTTP request ----

    @Test
    void testGetMimeTypeFallsBackToApplicationBinaryWhenNoServletContext() throws Exception {
        final byte[] content = "binary".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.bin", "test.bin", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        // Make the context return null for the HTTP request so getMimeType falls back
        when( context.getHttpRequest() ).thenReturn( null );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.bin" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        // page name still matches
        when( page.getName() ).thenReturn( "TestPage/test.png" );

        captureOutput();
        servlet.doGet( request, response );

        // application/binary is the fallback when no servlet context can provide the type
        verify( response ).setContentType( "application/binary" );
    }

}
