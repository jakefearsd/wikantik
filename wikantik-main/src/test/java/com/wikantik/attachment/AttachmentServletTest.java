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

}
