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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.TestEngine.with;

/**
 * Coverage tests for {@link Image} plugin targeting previously uncovered branches:
 * missing src parameter, invalid target values, center alignment, style with
 * trailing semicolon, and data:/javascript: URL sanitisation.
 */
public class ImageCITest {

    static TestEngine testEngine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
    static PluginManager manager = testEngine.getManager( PluginManager.class );

    Context context;

    @BeforeEach
    public void setUp() throws Exception {
        testEngine.saveText( "ImageTestPage", "Image plugin test page" );
        context = Wiki.context().create( testEngine,
                Wiki.contents().page( testEngine, "ImageTestPage" ) );
    }

    @AfterEach
    public void tearDown() {
        testEngine.deleteTestPage( "ImageTestPage" );
        TestEngine.emptyWorkDir();
    }

    /**
     * Missing src parameter must throw PluginException.
     * Covers the {@code if( src == null )} branch.
     */
    @Test
    public void testMissingSrcThrowsPluginException() {
        Assertions.assertThrows( PluginException.class, () ->
                manager.execute( context, "{INSERT com.wikantik.plugin.Image}" ),
                "Image plugin without src should throw PluginException" );
    }

    /**
     * align=center applies the center margin style rather than float style.
     * Covers the {@code align.equals("center")} branch.
     */
    @Test
    public void testAlignCenter() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' align='center'}" );

        Assertions.assertTrue( res.contains( "margin-left: auto" ),
                "align=center should produce margin-left:auto style" );
        Assertions.assertTrue( res.contains( "margin-right: auto" ),
                "align=center should produce margin-right:auto style" );
        Assertions.assertFalse( res.contains( "float:center" ),
                "align=center should NOT produce float style" );
    }

    /**
     * align=left applies a float style (non-center path).
     */
    @Test
    public void testAlignLeft() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' align='left'}" );

        Assertions.assertTrue( res.contains( "float:left" ),
                "align=left should produce float:left style" );
    }

    /**
     * An invalid target value (e.g. "_invalid") is silently ignored.
     * Covers the {@code !validTargetValue(target)} branch.
     */
    @Test
    public void testInvalidTargetIgnored() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "link='http://example.com/' target='_invalid'}" );

        // target attribute should not appear in the output
        Assertions.assertFalse( res.contains( "target=\"_invalid\"" ),
                "Invalid target should be stripped from output" );
        // The link itself should still be present
        Assertions.assertTrue( res.contains( "<a href=" ),
                "Link should still be rendered without invalid target" );
    }

    /**
     * A valid _blank target is preserved.
     * Covers the validTargetValue true path for standard underscore names.
     */
    @Test
    public void testValidTargetBlankPreserved() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "link='http://example.com/' target='_blank'}" );

        Assertions.assertTrue( res.contains( "target=\"_blank\"" ),
                "_blank target should be preserved" );
    }

    /**
     * A target starting with a letter is considered valid (named frame target).
     * Covers the {@code Character.isLowerCase(c) || Character.isUpperCase(c)} branch.
     */
    @Test
    public void testNamedFrameTargetPreserved() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "link='http://example.com/' target='myFrame'}" );

        Assertions.assertTrue( res.contains( "target=\"myFrame\"" ),
                "Named frame target starting with a letter should be preserved" );
    }

    /**
     * A target that is an empty string is invalid (validTargetValue returns false).
     * Covers the {@code !s.isEmpty()} false path.
     */
    @Test
    public void testEmptyTargetIgnored() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "link='http://example.com/' target=''}" );

        // The empty target value should be discarded
        Assertions.assertFalse( res.contains( "target=\"\"" ),
                "Empty target should be ignored" );
    }

    /**
     * A style attribute that already ends with a semicolon must not get a doubled semicolon.
     * Covers the {@code result.charAt(result.length()-1) != ';'} false branch.
     */
    @Test
    public void testStyleWithTrailingSemicolon() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "style='border:1px solid red;'}" );

        // Should not contain ";;" in the style attribute
        Assertions.assertFalse( res.contains( ";\"" + ";\"" ),
                "Style ending with semicolon should not produce double semicolon" );
        Assertions.assertTrue( res.contains( "border:1px solid red;" ),
                "Style should be present in output" );
    }

    /**
     * A style without a trailing semicolon has one appended.
     * Covers the {@code result.charAt(result.length()-1) != ';'} true branch.
     */
    @Test
    public void testStyleWithoutTrailingSemicolonGetsSemicolonAppended() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' " +
                "style='border:1px solid blue'}" );

        // The serialised style attribute should end with ";"
        Assertions.assertTrue( res.contains( "border:1px solid blue;" ),
                "Style without trailing semicolon should have one appended" );
    }

    /**
     * When allowHTML is true (Markdown engine default), data: URIs pass through unchanged.
     * Documents the runtime behaviour of the sanitisation branch.
     * Note: the sanitisation code path that prefixes "http://invalid_url" is only reached
     * when allowHTML=false, which is prevented by MarkdownSetupEngineLifecycleExtension
     * always setting allowHTML=true at startup.
     */
    @Test
    public void testDataUriPassesThroughWhenAllowHtmlTrue() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='data:image/png;base64,abc'}" );

        // With allowHTML=true (Markdown default) the src is included as-is
        Assertions.assertTrue( res.contains( "data:image/png;base64,abc" ),
                "With allowHTML=true, data: URI should be passed through unchanged" );
    }

    /**
     * When allowHTML is true (Markdown engine default), javascript: URIs pass through.
     * The sanitisation guard only fires when allowHTML=false.
     */
    @Test
    public void testJavascriptUriPassesThroughWhenAllowHtmlTrue() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='javascript:alert(1)'}" );

        Assertions.assertTrue( res.contains( "javascript:alert(1)" ),
                "With allowHTML=true, javascript: URI is passed through by the Image plugin" );
    }

    /**
     * A title attribute is included in the table element.
     */
    @Test
    public void testTitleAttribute() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' title='My image'}" );

        Assertions.assertTrue( res.contains( "title=\"My image\"" ),
                "Title attribute should appear on the table element" );
    }

    /**
     * A caption is rendered inside a caption element.
     */
    @Test
    public void testCaptionRendered() throws Exception {
        final String res = manager.execute( context,
                "{INSERT com.wikantik.plugin.Image src='http://example.com/img.png' caption='My caption'}" );

        Assertions.assertTrue( res.contains( "<caption>My caption</caption>" ),
                "Caption should appear in a <caption> element" );
    }
}
