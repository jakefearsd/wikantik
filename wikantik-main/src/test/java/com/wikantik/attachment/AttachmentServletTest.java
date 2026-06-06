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
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.ui.progress.ProgressManager;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
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
import java.nio.charset.StandardCharsets;
import java.security.Permission;
import java.security.Principal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link AttachmentServlet}. Uses the package-private constructor
 * to inject mock dependencies, avoiding the need for a running engine.
 */
class AttachmentServletTest {

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

        // Create the servlet with injected dependencies, then spy for createContext override
        servlet = spy( new AttachmentServlet( engine, attachmentManager, authorizationManager, progressManager ) );
        servlet.setUploadConstraints( new String[0], new String[0], Integer.MAX_VALUE );

        // Override createContext to return our mock context
        doReturn( context ).when( servlet ).createContext( any( HttpServletRequest.class ), anyString() );
        doReturn( session ).when( servlet ).findSession( any( HttpServletRequest.class ) );
    }

    // ---- Helpers ----

    /** Creates a mock Attachment with standard stubs including getWiki() */
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

    // ---- doGet tests ----

    @Test
    void testGetExistingAttachmentReturnsContentAndHeaders() throws Exception {
        final byte[] content = "Hello attachment".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        // No If-Modified-Since header (returns fresh content)
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        final ByteArrayOutputStream baos = captureOutput();
        servlet.doGet( request, response );

        // Verify content was written
        assertArrayEquals( content, baos.toByteArray() );

        // Verify headers
        verify( response ).setContentType( "image/png" );
        verify( response ).addHeader( eq( "Content-Disposition" ), contains( "inline" ) );
        verify( response ).addHeader( eq( "Content-Disposition" ), contains( "test.png" ) );
        verify( response ).addDateHeader( eq( "Last-Modified" ), eq( lastModified.getTime() ) );
        verify( response ).setContentLength( content.length );
    }

    @Test
    void testGetNonExistentAttachmentReturns404() throws Exception {
        when( attachmentManager.getAttachmentInfo( anyString(), anyInt() ) ).thenReturn( null );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( eq( HttpServletResponse.SC_NOT_FOUND ), anyString() );
    }

    @Test
    void testGetRespectsIfModifiedSinceReturns304() throws Exception {
        final Date lastModified = new Date( System.currentTimeMillis() - 60_000 ); // 1 minute ago

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        // Client has a newer version: If-Modified-Since is after lastModified
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( System.currentTimeMillis() );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_NOT_MODIFIED );
        // Should NOT have tried to stream content
        verify( attachmentManager, never() ).getAttachmentStream( any(), any() );
    }

    @Test
    void testGetUnauthorizedUserReturns403() throws Exception {
        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", new Date() );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( false );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( HttpServletResponse.SC_FORBIDDEN );
        verify( attachmentManager, never() ).getAttachmentStream( any(), any() );
    }

    @Test
    void testGetWithVersionParameter() throws Exception {
        final byte[] content = "versioned content".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        // Request version 3
        when( request.getParameter( "version" ) ).thenReturn( "3" );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        when( attachmentManager.getAttachmentInfo( "TestPage/test.png", 3 ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        final ByteArrayOutputStream baos = captureOutput();
        servlet.doGet( request, response );

        // Verify the correct version was requested
        verify( attachmentManager ).getAttachmentInfo( "TestPage/test.png", 3 );
        assertArrayEquals( content, baos.toByteArray() );
    }

    @Test
    void testGetInvalidVersionReturns400() throws Exception {
        when( request.getParameter( "version" ) ).thenReturn( "not-a-number" );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( eq( HttpServletResponse.SC_BAD_REQUEST ), anyString() );
    }

    @Test
    void testGetContentDispositionInlineForNormalFile() {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "photo.jpg" );
        when( attachmentManager.forceDownload( "photo.jpg" ) ).thenReturn( false );

        final String disposition = servlet.getContentDisposition( att );

        assertTrue( disposition.startsWith( "inline" ) );
        assertTrue( disposition.contains( "photo.jpg" ) );
    }

    @Test
    void testGetContentDispositionForcesAttachmentForActiveContentTypes() {
        final String[] activeContent = { "evil.html", "evil.htm", "evil.xhtml", "evil.svg", "evil.xml",
                "EVIL.HTML", "Evil.Svg" };
        for ( final String name : activeContent ) {
            final Attachment att = mock( Attachment.class );
            when( att.getFileName() ).thenReturn( name );
            // forceDownload NOT configured -- must still be forced by the servlet
            when( attachmentManager.forceDownload( name ) ).thenReturn( false );

            final String disposition = servlet.getContentDisposition( att );

            assertTrue( disposition.startsWith( "attachment" ),
                    "active content should force attachment disposition: " + name + " -> " + disposition );
            assertTrue( disposition.contains( name ), "disposition missing filename: " + disposition );
        }
    }

    @Test
    void testGetSetsNoSniffHeader() throws Exception {
        final byte[] content = "x".getBytes( StandardCharsets.UTF_8 );
        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", new Date() );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).setHeader( "X-Content-Type-Options", "nosniff" );
    }

    @Test
    void testGetContentDispositionAttachmentForForcedDownload() {
        final Attachment att = mock( Attachment.class );
        when( att.getFileName() ).thenReturn( "malware.exe" );
        when( attachmentManager.forceDownload( "malware.exe" ) ).thenReturn( true );

        final String disposition = servlet.getContentDisposition( att );

        assertTrue( disposition.startsWith( "attachment" ) );
        assertTrue( disposition.contains( "malware.exe" ) );
    }

    @Test
    void testGetNonCacheableAttachmentSetsCacheHeaders() throws Exception {
        final byte[] content = "no-cache content".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.png", lastModified );
        when( att.isCacheable() ).thenReturn( false );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.png" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).addHeader( "Pragma", "no-cache" );
        verify( response ).addHeader( "Cache-control", "no-cache" );
    }

    @Test
    void testGetProviderExceptionSendsError() throws Exception {
        when( attachmentManager.getAttachmentInfo( anyString(), anyInt() ) )
                .thenThrow( new ProviderException( "storage failed" ) );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).sendError( eq( HttpServletResponse.SC_INTERNAL_SERVER_ERROR ), contains( "storage failed" ) );
    }

    // ---- doOptions tests ----

    @Test
    void testOptionsReturnsAllowedMethods() {
        servlet.doOptions( request, response );

        verify( response ).setHeader( eq( "Allow" ), contains( "GET" ) );
        verify( response ).setHeader( eq( "Allow" ), contains( "PUT" ) );
        verify( response ).setHeader( eq( "Allow" ), contains( "POST" ) );
        verify( response ).setHeader( eq( "Allow" ), contains( "OPTIONS" ) );
        verify( response ).setHeader( eq( "Allow" ), contains( "PROPFIND" ) );
        verify( response ).setStatus( HttpServletResponse.SC_OK );
    }

    // ---- executeUpload tests ----

    @Test
    void testExecuteUploadWithPermission() throws Exception {
        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        final Principal user = mock( Principal.class );
        when( user.getName() ).thenReturn( "testuser" );
        when( context.getCurrentUser() ).thenReturn( user );

        // No existing attachment -- creates a new one
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        final boolean created = servlet.executeUpload( context, data, "test.txt", "http://localhost:8080/error",
                "TestPage", "upload note", 9 );

        assertTrue( created );
        verify( attachmentManager ).storeAttachment( any( Attachment.class ), eq( data ) );
    }

    @Test
    void testExecuteUploadNoPermissionThrowsRedirectException() throws Exception {
        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        when( context.getCurrentUser() ).thenReturn( mock( Principal.class ) );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( false );

        assertThrows( RedirectException.class, () ->
                servlet.executeUpload( context, data, "test.txt", "http://localhost:8080/error",
                        "TestPage", null, 9 )
        );

        verify( attachmentManager, never() ).storeAttachment( any(), any( InputStream.class ) );
    }

    @Test
    void testExecuteUploadForbiddenExtensionThrowsRedirectException() {
        // Configure forbidden extension
        servlet.setUploadConstraints( new String[0], new String[]{ ".exe" }, Integer.MAX_VALUE );

        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        assertThrows( RedirectException.class, () ->
                servlet.executeUpload( context, data, "malware.exe", "http://localhost:8080/error",
                        "TestPage", null, 9 )
        );
    }

    @Test
    void testExecuteUploadAllowedExtensionOnly() throws Exception {
        // Only allow .txt
        servlet.setUploadConstraints( new String[]{ ".txt" }, new String[0], Integer.MAX_VALUE );

        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        when( context.getCurrentUser() ).thenReturn( mock( Principal.class ) );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        // .txt should work
        servlet.executeUpload( context, data, "notes.txt", "http://localhost:8080/error",
                "TestPage", null, 9 );
        verify( attachmentManager ).storeAttachment( any( Attachment.class ), eq( data ) );

        // .png should be rejected
        final InputStream data2 = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );
        assertThrows( RedirectException.class, () ->
                servlet.executeUpload( context, data2, "image.png", "http://localhost:8080/error",
                        "TestPage", null, 9 )
        );
    }

    @Test
    void testExecuteUploadExceedingMaxSizeThrowsRedirectException() {
        servlet.setUploadConstraints( new String[0], new String[0], 100 );

        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        assertThrows( RedirectException.class, () ->
                servlet.executeUpload( context, data, "big.txt", "http://localhost:8080/error",
                        "TestPage", null, 200 )
        );
    }

    @Test
    void testExecuteUploadAdminBypassesSizeAndTypeChecks() throws Exception {
        servlet.setUploadConstraints( new String[]{ ".txt" }, new String[]{ ".exe" }, 10 );
        when( context.hasAdminPermissions() ).thenReturn( true );

        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );
        when( context.getCurrentUser() ).thenReturn( mock( Principal.class ) );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        // Admin can upload .exe beyond max size
        servlet.executeUpload( context, data, "big.exe", "http://localhost:8080/error",
                "TestPage", null, 200 );

        verify( attachmentManager ).storeAttachment( any( Attachment.class ), eq( data ) );
    }

    @Test
    void testExecuteUploadWithChangeNote() throws Exception {
        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        final Principal user = mock( Principal.class );
        when( user.getName() ).thenReturn( "testuser" );
        when( context.getCurrentUser() ).thenReturn( user );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( null );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        servlet.executeUpload( context, data, "test.txt", "http://localhost:8080/error",
                "TestPage", "Updated screenshot", 9 );

        verify( attachmentManager ).storeAttachment( argThat( att ->
                "Updated screenshot".equals( att.getAttribute( Page.CHANGENOTE ) )
        ), eq( data ) );
    }

    @Test
    void testExecuteUploadNullDataThrowsRedirectException() {
        assertThrows( RedirectException.class, () ->
                servlet.executeUpload( context, null, "test.txt", "http://localhost:8080/error",
                        "TestPage", null, 0 )
        );
    }

    @Test
    void testExecuteUploadExistingAttachmentUpdatesVersion() throws Exception {
        final InputStream data = new ByteArrayInputStream( "file data".getBytes( StandardCharsets.UTF_8 ) );

        final Attachment existingAtt = createMockAttachment( "TestPage/test.png", "test.png", new Date() );
        when( attachmentManager.getAttachmentInfo( "TestPage/test.png" ) ).thenReturn( existingAtt );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );

        final Principal user = mock( Principal.class );
        when( user.getName() ).thenReturn( "testuser" );
        when( context.getCurrentUser() ).thenReturn( user );

        final boolean created = servlet.executeUpload( context, data, "test.txt", "http://localhost:8080/error",
                "TestPage", null, 9 );

        assertFalse( created, "Should return false when updating existing attachment" );
        verify( attachmentManager ).storeAttachment( eq( existingAtt ), eq( data ) );
    }

    // ---- sendError tests ----

    @Test
    void testSendErrorSendsInternalServerError() throws Exception {
        servlet.sendError( response, "Something broke" );

        verify( response ).sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Something broke" );
    }

    @Test
    void testSendErrorSwallowsIllegalStateException() throws Exception {
        doThrow( new IllegalStateException( "committed" ) ).when( response )
                .sendError( anyInt(), anyString() );

        // Should not throw
        assertDoesNotThrow( () -> servlet.sendError( response, "Something broke" ) );
    }

    // ---- doPost delegation test ----

    @Test
    void testDoPostRedirectExceptionSetsSessionMessage() throws Exception {
        // Override upload to throw RedirectException
        doThrow( new RedirectException( "Upload rejected", "http://localhost:8080/error" ) )
                .when( servlet ).upload( any( HttpServletRequest.class ) );

        servlet.doPost( request, response );

        verify( session ).addMessage( "Upload rejected" );
        verify( httpSession ).setAttribute( "msg", "Upload rejected" );
        verify( response ).sendRedirect( "http://localhost:8080/error" );
    }

    // ---- doPost happy path ----

    @Test
    void testDoPostHappyPathRedirectsAndClearsMessage() throws Exception {
        final String nextPage = "http://localhost:8080/Wiki.jsp?page=TestPage";
        doReturn( nextPage ).when( servlet ).upload( any( HttpServletRequest.class ) );

        servlet.doPost( request, response );

        verify( httpSession ).removeAttribute( "msg" );
        verify( response ).sendRedirect( nextPage );
    }

    // ---- upload() non-multipart → RedirectException ----

    @Test
    void testUploadNonMultipartThrowsRedirectException() {
        // application/x-www-form-urlencoded makes isMultipartContent() return false
        when( request.getMethod() ).thenReturn( "POST" );
        when( request.getContentType() ).thenReturn( "application/x-www-form-urlencoded" );

        // Call the real upload() method — do NOT stub it on the spy here
        final RedirectException ex = assertThrows( RedirectException.class, () -> servlet.upload( request ) );
        assertNotNull( ex.getRedirect(), "RedirectException must carry a redirect target" );
    }

    // ---- Multipart helpers ----

    private static final String BOUNDARY = "----WikantikTestBoundary";

    /**
     * Builds a minimal multipart/form-data body for Commons FileUpload to parse.
     * Each {@code part} string is the full part content from Content-Disposition onward
     * (without the leading boundary line, which this method adds).
     */
    private byte[] buildMultipartBody( final String... parts ) {
        final StringBuilder sb = new StringBuilder();
        for ( final String part : parts ) {
            sb.append( "--" ).append( BOUNDARY ).append( "\r\n" );
            sb.append( part );
            sb.append( "\r\n" );
        }
        sb.append( "--" ).append( BOUNDARY ).append( "--" ).append( "\r\n" );
        return sb.toString().getBytes( StandardCharsets.UTF_8 );
    }

    /** Wraps a byte array as a {@link ServletInputStream} for mock injection. */
    private static ServletInputStream servletInputStream( final byte[] data ) {
        final ByteArrayInputStream bais = new ByteArrayInputStream( data );
        return new ServletInputStream() {
            @Override public int read() throws IOException { return bais.read(); }
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener( final ReadListener listener ) { }
        };
    }

    /** Stubs the mock request so Commons FileUpload sees a valid multipart stream. */
    private void stubMultipartRequest( final byte[] body ) throws IOException {
        when( request.getMethod() ).thenReturn( "POST" );
        when( request.getContentType() ).thenReturn( "multipart/form-data; boundary=" + BOUNDARY );
        when( request.getCharacterEncoding() ).thenReturn( "UTF-8" );
        when( request.getContentLength() ).thenReturn( body.length );
        when( request.getContentLengthLong() ).thenReturn( (long) body.length );
        when( request.getInputStream() ).thenReturn( servletInputStream( body ) );
        when( request.getParameter( "progressid" ) ).thenReturn( null );
    }

    // ---- upload() real multipart, single file — executeUpload stubbed on spy ----

    @Test
    void testUploadMultipartSingleFileCallsProgressAndReturnsNextPage() throws Exception {
        final String filePart =
                "Content-Disposition: form-data; name=\"content\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "hello";
        final String pagePart =
                "Content-Disposition: form-data; name=\"page\"\r\n" +
                "\r\n" +
                "TestPage";
        final String nextPagePart =
                "Content-Disposition: form-data; name=\"nextpage\"\r\n" +
                "\r\n" +
                "http://localhost:8080/Wiki.jsp?page=TestPage";

        final byte[] body = buildMultipartBody( pagePart, nextPagePart, filePart );
        stubMultipartRequest( body );

        // Stub executeUpload on the spy so we don't need full engine wiring
        doReturn( false ).when( servlet ).executeUpload(
                any( Context.class ), any( InputStream.class ),
                anyString(), anyString(), anyString(), any(), anyLong() );

        final String result = servlet.upload( request );

        assertEquals( "http://localhost:8080/Wiki.jsp?page=TestPage", result );
        verify( progressManager ).startProgress( any(), isNull() );
        verify( progressManager ).stopProgress( isNull() );
    }

    // ---- upload() multipart no file part → RedirectException("Broken file upload") ----

    @Test
    void testUploadMultipartNoFileThrowsBrokenUpload() throws Exception {
        final String pagePart =
                "Content-Disposition: form-data; name=\"page\"\r\n" +
                "\r\n" +
                "TestPage";

        final byte[] body = buildMultipartBody( pagePart );
        stubMultipartRequest( body );

        final RedirectException ex = assertThrows( RedirectException.class, () -> servlet.upload( request ) );
        assertTrue( ex.getMessage().contains( "Broken file upload" ),
                "Expected 'Broken file upload' but got: " + ex.getMessage() );
    }

    // ---- validateNextPage phishing rewrite ----

    @Test
    void testUploadValidateNextPageRejectsOffSiteUrl() throws Exception {
        final String filePart =
                "Content-Disposition: form-data; name=\"content\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "hello";
        final String pagePart =
                "Content-Disposition: form-data; name=\"page\"\r\n" +
                "\r\n" +
                "TestPage";
        final String evilNextPage =
                "Content-Disposition: form-data; name=\"nextpage\"\r\n" +
                "\r\n" +
                "http://evil.example.com/x";

        final byte[] body = buildMultipartBody( pagePart, evilNextPage, filePart );
        stubMultipartRequest( body );

        // Stub executeUpload so we don't need full wiring
        doReturn( false ).when( servlet ).executeUpload(
                any( Context.class ), any( InputStream.class ),
                anyString(), anyString(), anyString(), any(), anyLong() );

        // engine.getURL(WIKI_ERROR,...) → "http://localhost:8080/error" (already stubbed in setUp)
        final String result = servlet.upload( request );

        // The evil URL must have been replaced with the error page
        assertNotEquals( "http://evil.example.com/x", result,
                "Off-site nextPage must be rejected" );
        assertEquals( "http://localhost:8080/error", result,
                "validateNextPage should rewrite off-site URL to errorPage" );
    }

    // ---- getMimeType fallback → "application/binary" ----

    @Test
    void testGetMimeTypeFallbackToApplicationBinary() throws Exception {
        // Override the servletContext stub to return null for getMimeType
        final ServletContext servletContext = httpSession.getServletContext();
        when( servletContext.getMimeType( anyString() ) ).thenReturn( null );

        final byte[] content = "binary content".getBytes( StandardCharsets.UTF_8 );
        final Date lastModified = new Date();

        final Attachment att = createMockAttachment( "TestPage/test.png", "test.bin", lastModified );
        when( att.isCacheable() ).thenReturn( true );
        when( att.getSize() ).thenReturn( (long) content.length );

        when( attachmentManager.getAttachmentInfo( eq( "TestPage/test.png" ), anyInt() ) ).thenReturn( att );
        when( attachmentManager.getAttachmentStream( any( Context.class ), eq( att ) ) )
                .thenReturn( new ByteArrayInputStream( content ) );
        when( attachmentManager.forceDownload( "test.bin" ) ).thenReturn( false );
        when( authorizationManager.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        when( request.getDateHeader( "If-Modified-Since" ) ).thenReturn( -1L );

        captureOutput();
        servlet.doGet( request, response );

        verify( response ).setContentType( "application/binary" );
    }

}
