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
import com.wikantik.api.core.Attachment;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.pages.PageManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentResourceTest {

    private TestEngine engine;
    private AttachmentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        // Create a test page
        engine.saveText( "RestAttachPage", "Page with attachments." );

        servlet = new AttachmentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "RestAttachPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    @Test
    void testListAttachmentsEmptyPage() throws Exception {
        final String json = doGet( "RestAttachPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestAttachPage", obj.get( "page" ).getAsString() );
        assertTrue( obj.has( "attachments" ) );
        assertTrue( obj.get( "attachments" ).isJsonArray() );
        assertEquals( 0, obj.get( "count" ).getAsInt() );
    }

    @Test
    void testListAttachmentsWithAttachment() throws Exception {
        // Store an attachment
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        final Attachment att = Wiki.contents().attachment( engine, "RestAttachPage", "test.txt" );
        am.storeAttachment( att, new ByteArrayInputStream( "hello".getBytes( StandardCharsets.UTF_8 ) ) );

        final String json = doGet( "RestAttachPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestAttachPage", obj.get( "page" ).getAsString() );
        final JsonArray attachments = obj.getAsJsonArray( "attachments" );
        assertEquals( 1, attachments.size() );
        assertEquals( 1, obj.get( "count" ).getAsInt() );

        final JsonObject entry = attachments.get( 0 ).getAsJsonObject();
        assertTrue( entry.has( "name" ) );
        assertTrue( entry.has( "fileName" ) );
        assertTrue( entry.has( "size" ) );
        assertTrue( entry.has( "version" ) );
        assertTrue( entry.has( "lastModified" ) );
    }

    @Test
    void testListAttachmentsPageNotFound() throws Exception {
        final String json = doGet( "NonExistentPage12345" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDownloadAttachmentNotFound() throws Exception {
        final String json = doGet( "RestAttachPage/nonexistent.txt" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDeleteAttachmentNotFound() throws Exception {
        final String json = doDelete( "RestAttachPage/nonexistent.txt" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        // Anonymous users lack delete permission, so the authorization check
        // fires before the not-found check.  This is correct security behavior:
        // don't reveal resource existence to unauthorized users.
        assertEquals( 403, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDeleteAttachmentMissingFileName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage" );
        Mockito.doReturn( "/RestAttachPage" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testMissingPageName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDownloadExistingAttachment() throws Exception {
        // Store an attachment
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        final Attachment att = Wiki.contents().attachment( engine, "RestAttachPage", "download.txt" );
        am.storeAttachment( att, new ByteArrayInputStream( "download content".getBytes( StandardCharsets.UTF_8 ) ) );

        // Download the attachment
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage/download.txt" );
        Mockito.doReturn( "/RestAttachPage/download.txt" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        final jakarta.servlet.ServletOutputStream sos = new jakarta.servlet.ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final jakarta.servlet.WriteListener listener ) { }
        };
        Mockito.doReturn( sos ).when( response ).getOutputStream();

        servlet.doGet( request, response );

        final String downloaded = baos.toString( StandardCharsets.UTF_8 );
        assertEquals( "download content", downloaded,
                "Downloaded content should match uploaded content" );
        Mockito.verify( response ).setContentType( "text/plain" );
        Mockito.verify( response ).setHeader( Mockito.eq( "Content-Disposition" ),
                Mockito.contains( "download.txt" ) );
    }

    @Test
    void testUploadAttachmentRequiresPermission() throws Exception {
        // Anonymous users lack "upload" permission, so upload returns 403 before
        // the file part is even checked. This verifies permission enforcement.
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage" );
        Mockito.doReturn( "/RestAttachPage" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 403, obj.get( "status" ).getAsInt(),
                "Anonymous upload should return 403 Forbidden" );
    }

    @Test
    void testUploadAttachmentMissingPageName() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDeleteMissingPageAndFileReturns400() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testDownloadBinaryAttachment() throws Exception {
        // Store a binary attachment with an unknown extension to cover the
        // content-type fallback to "application/octet-stream"
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        final byte[] binaryData = new byte[] { 0x00, 0x01, 0x02, 0x03 };
        final Attachment att = Wiki.contents().attachment( engine, "RestAttachPage", "data.xyz" );
        am.storeAttachment( att, new ByteArrayInputStream( binaryData ) );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/RestAttachPage/data.xyz" );
        Mockito.doReturn( "/RestAttachPage/data.xyz" ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        final jakarta.servlet.ServletOutputStream sos = new jakarta.servlet.ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final jakarta.servlet.WriteListener listener ) { }
        };
        Mockito.doReturn( sos ).when( response ).getOutputStream();

        servlet.doGet( request, response );

        final byte[] downloaded = baos.toByteArray();
        assertArrayEquals( binaryData, downloaded,
                "Downloaded binary content should match uploaded" );
        // Unknown extension should fall back to application/octet-stream
        Mockito.verify( response ).setContentType( "application/octet-stream" );
    }

    @Test
    void testListAttachmentsWithMultipleFiles() throws Exception {
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.storeAttachment(
                Wiki.contents().attachment( engine, "RestAttachPage", "file1.txt" ),
                new ByteArrayInputStream( "content1".getBytes( StandardCharsets.UTF_8 ) ) );
        am.storeAttachment(
                Wiki.contents().attachment( engine, "RestAttachPage", "file2.txt" ),
                new ByteArrayInputStream( "content2".getBytes( StandardCharsets.UTF_8 ) ) );

        final String json = doGet( "RestAttachPage" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertEquals( "RestAttachPage", obj.get( "page" ).getAsString() );
        final JsonArray attachments = obj.getAsJsonArray( "attachments" );
        assertEquals( 2, attachments.size(), "Should have 2 attachments" );
        assertEquals( 2, obj.get( "count" ).getAsInt() );

        // Verify attachment entry structure has specific field values
        for ( int i = 0; i < attachments.size(); i++ ) {
            final JsonObject entry = attachments.get( i ).getAsJsonObject();
            final String fileName = entry.get( "fileName" ).getAsString();
            assertTrue( "file1.txt".equals( fileName ) || "file2.txt".equals( fileName ),
                    "Attachment fileName should be file1.txt or file2.txt, got: " + fileName );
            assertTrue( entry.get( "version" ).getAsInt() >= 1, "Version should be >= 1" );
            assertTrue( entry.get( "size" ).getAsLong() > 0, "Size should be positive" );
        }
    }

    // ----- Helper methods -----

    private String doGet( final String path ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doDelete( final String path ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/attachments/" + path );
        Mockito.doReturn( "/" + path ).when( request ).getPathInfo();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doDelete( request, response );
        return sw.toString();
    }

}
