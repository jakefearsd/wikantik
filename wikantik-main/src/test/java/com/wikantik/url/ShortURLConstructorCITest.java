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
package com.wikantik.url;

import com.wikantik.InternalWikiException;
import com.wikantik.TestEngine;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.exceptions.WikiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Additional tests for {@link ShortURLConstructor} covering URL contexts not
 * exercised by {@link ShortURLConstructorTest}.
 */
public class ShortURLConstructorCITest {

    private TestEngine testEngine;
    private final Properties props = TestEngine.getTestProperties();

    private URLConstructor getConstructor( final String prefix ) throws WikiException {
        if( prefix != null ) {
            props.setProperty( ShortURLConstructor.PROP_PREFIX, prefix );
        }
        testEngine = new TestEngine( props );
        final URLConstructor c = new ShortURLConstructor();
        c.initialize( testEngine, props );
        return c;
    }

    // -----------------------------------------------------------------------
    // PAGE_PREVIEW
    // -----------------------------------------------------------------------

    @Test
    void testPreviewURL_noName_returnsBaseUrl() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        // name == null for preview → base URL
        final String url = c.makeURL( ContextEnum.PAGE_PREVIEW.getRequestContext(), null, null );
        Assertions.assertFalse( url.isEmpty() );
    }

    @Test
    void testPreviewURL_withName() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_PREVIEW.getRequestContext(), "SomePage", null );
        Assertions.assertTrue( url.contains( "wiki/SomePage" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=Preview" ), "Expected do=Preview in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_INFO
    // -----------------------------------------------------------------------

    @Test
    void testInfoURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_INFO.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=PageInfo" ), "Expected do=PageInfo in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_DIFF
    // -----------------------------------------------------------------------

    @Test
    void testDiffURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_DIFF.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=Diff" ), "Expected do=Diff in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_UPLOAD
    // -----------------------------------------------------------------------

    @Test
    void testUploadURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_UPLOAD.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=Upload" ), "Expected do=Upload in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_COMMENT
    // -----------------------------------------------------------------------

    @Test
    void testCommentURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_COMMENT.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=Comment" ), "Expected do=Comment in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // WIKI_LOGIN
    // -----------------------------------------------------------------------

    @Test
    void testLoginURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.WIKI_LOGIN.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "Login.jsp" ), "Expected Login.jsp in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_DELETE
    // -----------------------------------------------------------------------

    @Test
    void testDeleteURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_DELETE.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=Delete" ), "Expected do=Delete in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // PAGE_CONFLICT
    // -----------------------------------------------------------------------

    @Test
    void testConflictURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_CONFLICT.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=PageModified" ), "Expected do=PageModified in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // WIKI_PREFS
    // -----------------------------------------------------------------------

    @Test
    void testPrefsURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.WIKI_PREFS.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "wiki/Main" ), "Expected short prefix in URL: " + url );
        Assertions.assertTrue( url.contains( "do=UserPreferences" ), "Expected do=UserPreferences in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // WIKI_FIND
    // -----------------------------------------------------------------------

    @Test
    void testFindURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.WIKI_FIND.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "do=Search" ), "Expected do=Search in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // WIKI_ERROR
    // -----------------------------------------------------------------------

    @Test
    void testErrorURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.WIKI_ERROR.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "Error.jsp" ), "Expected Error.jsp in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // WIKI_CREATE_GROUP
    // -----------------------------------------------------------------------

    @Test
    void testCreateGroupURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.WIKI_CREATE_GROUP.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "do=NewGroup" ), "Expected do=NewGroup in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // GROUP_DELETE
    // -----------------------------------------------------------------------

    @Test
    void testDeleteGroupURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.GROUP_DELETE.getRequestContext(), "MyGroup", null );
        Assertions.assertTrue( url.contains( "do=DeleteGroup" ), "Expected do=DeleteGroup in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // GROUP_EDIT
    // -----------------------------------------------------------------------

    @Test
    void testEditGroupURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.GROUP_EDIT.getRequestContext(), "MyGroup", null );
        Assertions.assertTrue( url.contains( "do=EditGroup" ), "Expected do=EditGroup in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // GROUP_VIEW
    // -----------------------------------------------------------------------

    @Test
    void testViewGroupURL() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.GROUP_VIEW.getRequestContext(), "MyGroup", null );
        Assertions.assertTrue( url.contains( "do=Group" ), "Expected do=Group in URL: " + url );
        Assertions.assertTrue( url.contains( "group=" ), "Expected group= param in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // Unknown context → InternalWikiException
    // -----------------------------------------------------------------------

    @Test
    void testMakeURL_unknownContext_throwsInternalWikiException() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        Assertions.assertThrows(
            InternalWikiException.class,
            () -> c.makeURL( "totally_unknown_context", "Main", null )
        );
    }

    // -----------------------------------------------------------------------
    // PAGE_VIEW with null name → base URL only
    // -----------------------------------------------------------------------

    @Test
    void testViewURL_nullName_returnsBaseUrl() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_VIEW.getRequestContext(), null, null );
        Assertions.assertFalse( url.isEmpty() );
        // Should not contain the wiki/ prefix since we hit the null-name branch
        Assertions.assertFalse( url.contains( "wiki/" ), "Null name should not produce wiki/ path: " + url );
    }

    // -----------------------------------------------------------------------
    // Parameters are appended correctly for various contexts
    // -----------------------------------------------------------------------

    @Test
    void testViewURL_withParameters() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_VIEW.getRequestContext(), "Main", "version=3" );
        Assertions.assertTrue( url.contains( "?version=3" ), "Expected ?version=3 in URL: " + url );
    }

    @Test
    void testAttachURL_withParameters() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_ATTACH.getRequestContext(), "Main/file.pdf", "inline=true" );
        Assertions.assertTrue( url.contains( "?inline=true" ), "Expected ?inline=true in URL: " + url );
    }

    @Test
    void testEditURL_withParameters() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final String url = c.makeURL( ContextEnum.PAGE_EDIT.getRequestContext(), "Main", "section=2" );
        Assertions.assertTrue( url.contains( "&amp;section=2" ), "Expected &amp;section=2 in URL: " + url );
    }

    // -----------------------------------------------------------------------
    // parsePage — uses 'page' parameter if present, URL path otherwise
    // -----------------------------------------------------------------------

    @Test
    void testParsePage_withPageParam_returnsPageParam() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameter( "page" ) ).thenReturn( "TestPage" );

        final String result = c.parsePage(
            ContextEnum.PAGE_VIEW.getRequestContext(), req, StandardCharsets.UTF_8 );
        Assertions.assertEquals( "TestPage", result );
    }

    @Test
    void testParsePage_noPageParam_delegatesToUrlParsing() throws Exception {
        final URLConstructor c = getConstructor( "wiki/" );
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameter( "page" ) ).thenReturn( null );
        // No path info → URLConstructor.parsePageFromURL returns null when no
        // usable path is present; assert it does not throw
        Assertions.assertDoesNotThrow(
            () -> c.parsePage( ContextEnum.PAGE_VIEW.getRequestContext(), req, StandardCharsets.UTF_8 ) );
    }

    // -----------------------------------------------------------------------
    // getForwardPage — respects 'do' parameter
    // -----------------------------------------------------------------------

    @Test
    void testGetForwardPage_doParamPresent_returnsDoJsp() throws Exception {
        final ShortURLConstructor c = new ShortURLConstructor();
        c.initialize( new TestEngine( props ), props );

        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameter( "do" ) ).thenReturn( "Edit" );

        Assertions.assertEquals( "Edit.jsp", c.getForwardPage( req ) );
    }

    @Test
    void testGetForwardPage_noDoParam_returnsWikiJsp() throws Exception {
        final ShortURLConstructor c = new ShortURLConstructor();
        c.initialize( new TestEngine( props ), props );

        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameter( "do" ) ).thenReturn( null );

        Assertions.assertEquals( "Wiki.jsp", c.getForwardPage( req ) );
    }

    // -----------------------------------------------------------------------
    // Custom prefix via PROP_PREFIX
    // -----------------------------------------------------------------------

    @Test
    void testCustomPrefix_appearsInViewUrl() throws Exception {
        final URLConstructor c = getConstructor( "pages/" );
        final String url = c.makeURL( ContextEnum.PAGE_VIEW.getRequestContext(), "Main", null );
        Assertions.assertTrue( url.contains( "pages/Main" ), "Expected pages/ prefix in URL: " + url );
    }
}
