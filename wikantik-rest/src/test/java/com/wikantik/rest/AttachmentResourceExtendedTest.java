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
import com.wikantik.auth.SessionMonitor;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended coverage tests for {@link AttachmentResource} that pin branches
 * not exercised by {@link AttachmentResourceTest}: non-multipart POST (415),
 * PUT rename validation (missing newName, invalid name, extension mismatch,
 * attachment not found), and path-parsing edge cases.
 */
class AttachmentResourceExtendedTest {

    private TestEngine engine;
    private AttachmentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        engine.saveText( "ExtAttachPage", "Page for extended attachment tests." );

        servlet = new AttachmentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );

        // Evict the admin session so tests start from an anonymous state by default.
        anonymizeMockSession();
    }

    private void anonymizeMockSession() {
        SessionMonitor.getInstance( engine ).remove( "mock-session" );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try { pm.deletePage( "ExtAttachPage" ); } catch ( final Exception e ) { /* ignore */ }
            engine.stop();
        }
    }

    // ---- POST — non-multipart body returns 415 ----

    @Test
    void postUpload_nonMultipartContentType_returns415() throws Exception {
        // Anonymous has no upload permission so we need to give them a real multipart
        // check first; however the 415 guard fires BEFORE the permission check.
        // Wait — the production code checks permission then content-type:
        //   checkPagePermission → 403 if denied, THEN content-type check → 415.
        // Test with admin session (engine.saveText already set one up) to reach 415.
        // We DON'T anonymize here — use the existing admin session from setUp.
        engine.saveText( "ExtAttachPage", "content" ); // logs in admin

        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/attachments/ExtAttachPage" );
        Mockito.doReturn( "/ExtAttachPage" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPost( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 415, obj.get( "status" ).getAsInt(),
            "Non-multipart upload must return 415 Unsupported Media Type" );
    }

    @Test
    void postUpload_nullContentType_returns415() throws Exception {
        engine.saveText( "ExtAttachPage", "content" ); // admin session

        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/attachments/ExtAttachPage" );
        Mockito.doReturn( "/ExtAttachPage" ).when( req ).getPathInfo();
        Mockito.doReturn( null ).when( req ).getContentType();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPost( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 415, obj.get( "status" ).getAsInt() );
    }

    // ---- PUT rename — missing newName ----

    @Test
    void putRename_missingNewName_returns400() throws Exception {
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.storeAttachment(
            Wiki.contents().attachment( engine, "ExtAttachPage", "source.txt" ),
            new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) ) );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/ExtAttachPage/source.txt" );
        Mockito.doReturn( "/ExtAttachPage/source.txt" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        // Body: JSON without newName
        Mockito.doReturn( new BufferedReader( new StringReader( "{}" ) ) ).when( req ).getReader();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void putRename_blankNewName_returns400() throws Exception {
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.storeAttachment(
            Wiki.contents().attachment( engine, "ExtAttachPage", "source.txt" ),
            new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) ) );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/ExtAttachPage/source.txt" );
        Mockito.doReturn( "/ExtAttachPage/source.txt" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        Mockito.doReturn( new BufferedReader( new StringReader( "{\"newName\":\"\"}" ) ) )
            .when( req ).getReader();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- PUT rename — invalid name (fails AttachmentNameValidator.isValid) ----

    @Test
    void putRename_invalidNewName_returns400WithMessage() throws Exception {
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.storeAttachment(
            Wiki.contents().attachment( engine, "ExtAttachPage", "ok.txt" ),
            new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) ) );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/ExtAttachPage/ok.txt" );
        Mockito.doReturn( "/ExtAttachPage/ok.txt" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        // Name with two periods (invalid per AttachmentNameValidator — exactly one period required)
        Mockito.doReturn( new BufferedReader( new StringReader( "{\"newName\":\"bad..name.txt\"}" ) ) )
            .when( req ).getReader();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().toLowerCase().contains( "invalid" )
            || obj.get( "message" ).getAsString().toLowerCase().contains( "name" ),
            "400 message should mention invalid name; got: " + obj.get( "message" ).getAsString() );
    }

    // ---- PUT rename — extension mismatch ----

    @Test
    void putRename_extensionMismatch_returns400() throws Exception {
        final AttachmentManager am = engine.getManager( AttachmentManager.class );
        am.storeAttachment(
            Wiki.contents().attachment( engine, "ExtAttachPage", "image.jpg" ),
            new ByteArrayInputStream( "imgdata".getBytes( StandardCharsets.UTF_8 ) ) );

        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/ExtAttachPage/image.jpg" );
        Mockito.doReturn( "/ExtAttachPage/image.jpg" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        // Rename to .png — extension mismatch
        Mockito.doReturn( new BufferedReader( new StringReader( "{\"newName\":\"image.png\"}" ) ) )
            .when( req ).getReader();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().toLowerCase().contains( "mismatch" ),
            "400 message should mention extension mismatch; got: " + obj.get( "message" ).getAsString() );
    }

    // ---- PUT rename — attachment not found ----

    @Test
    void putRename_attachmentNotFound_returns404() throws Exception {
        // Page exists but no attachment named "missing.txt"
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/ExtAttachPage/missing.txt" );
        Mockito.doReturn( "/ExtAttachPage/missing.txt" ).when( req ).getPathInfo();
        Mockito.doReturn( "application/json" ).when( req ).getContentType();
        Mockito.doReturn( new BufferedReader( new StringReader( "{\"newName\":\"other.txt\"}" ) ) )
            .when( req ).getReader();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    // ---- PUT rename — missing page name (null pageName from path) ----

    @Test
    void putRename_missingPageName_returns400() throws Exception {
        // Path is just a filename with a dot and no leading page — parseAttachmentPath
        // returns [null, "file.txt"] → pageName is null → 400
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/attachments/file.txt" );
        Mockito.doReturn( "/file.txt" ).when( req ).getPathInfo();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- PUT rename — missing path entirely ----

    @Test
    void putRename_nullPath_returns400() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/attachments" );
        Mockito.doReturn( null ).when( req ).getPathInfo();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doPut( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- GET — page name resolves to null ----

    @Test
    void getList_pathWithDotButNoSlash_returns400() throws Exception {
        // Single segment with a dot: parseAttachmentPath returns [null, "onlydot.txt"]
        // → pageName is null → 400 "Page name is required"
        final HttpServletRequest req = HttpMockFactory.createHttpRequest(
            "/api/attachments/onlydot.txt" );
        Mockito.doReturn( "/onlydot.txt" ).when( req ).getPathInfo();

        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    // ---- parseAttachmentPath — static contract tests ----

    @Test
    void parseAttachmentPath_singleSegmentWithDot_returnsNullPageName() {
        final String[] result = AttachmentResource.parseAttachmentPath( "report.pdf" );
        assertNull( result[0], "Single dotted segment has no page name" );
        assertEquals( "report.pdf", result[1] );
    }

    @Test
    void parseAttachmentPath_singleSegmentNoDot_returnsNullFileName() {
        final String[] result = AttachmentResource.parseAttachmentPath( "PageName" );
        assertEquals( "PageName", result[0] );
        assertNull( result[1] );
    }

    @Test
    void parseAttachmentPath_deepHierarchyNoDot_entirePathIsPageName() {
        final String[] result = AttachmentResource.parseAttachmentPath( "a/b/c/d" );
        assertEquals( "a/b/c/d", result[0] );
        assertNull( result[1] );
    }

    @Test
    void parseAttachmentPath_deepHierarchyWithDot_correctlySplits() {
        final String[] result = AttachmentResource.parseAttachmentPath( "a/b/c/file.pdf" );
        assertEquals( "a/b/c", result[0] );
        assertEquals( "file.pdf", result[1] );
    }
}
