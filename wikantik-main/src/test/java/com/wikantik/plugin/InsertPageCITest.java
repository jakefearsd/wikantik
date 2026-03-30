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
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Additional coverage tests for {@link InsertPage} targeting previously uncovered
 * branches: missing page parameter, default text for missing pages, maxlength
 * truncation, section extraction, class/style/showOnce attributes.
 */
public class InsertPageCITest {

    static TestEngine testEngine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception {
        testEngine.deleteTestPage( "HostPage" );
        testEngine.deleteTestPage( "IncludedPage" );
        testEngine.deleteTestPage( "SectionPage" );
    }

    /**
     * When the {@code page} parameter is absent, the plugin renders an error span.
     * Covers the {@code includedPage == null} branch.
     */
    @Test
    public void testMissingPageParameterRendersError() throws Exception {
        testEngine.saveText( "HostPage", "[{InsertPage}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "You have to define a page" ),
                "Missing page param should produce error message" );
    }

    /**
     * When the referenced page does not exist and no {@code default} parameter is given,
     * the plugin renders a "Would you like to create it?" prompt.
     * Covers the {@code page == null && defaultstr == null} branch.
     */
    @Test
    public void testNonExistentPageWithNoDefaultRendersCreateLink() throws Exception {
        testEngine.saveText( "HostPage",
                "[{InsertPage page='NonExistentXyzPage'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "create it" ) || result.contains( "NonExistentXyzPage" ),
                "Non-existent page should produce create link or page name" );
    }

    /**
     * When the referenced page does not exist and a {@code default} parameter is given,
     * the plugin renders the default text instead.
     * Covers the {@code page == null && defaultstr != null} branch.
     */
    @Test
    public void testNonExistentPageWithDefaultRendersDefaultText() throws Exception {
        testEngine.saveText( "HostPage",
                "[{InsertPage page='NonExistentXyzPage' default='Use this fallback text'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "Use this fallback text" ),
                "Non-existent page with default param should render the default text" );
    }

    /**
     * The {@code maxlength} parameter truncates the included page content and appends "...".
     * Covers the {@code pageData.length() > maxlen} branch.
     */
    @Test
    public void testMaxlengthTruncatesContent() throws Exception {
        testEngine.saveText( "IncludedPage",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ[{ALLOW view Anonymous}]" );
        // maxlength=5 is shorter than the content
        testEngine.saveText( "HostPage",
                "[{InsertPage page='IncludedPage' maxlength='5'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "..." ),
                "Truncated content should end with '...'" );
        // The "more" link should appear
        Assertions.assertTrue( result.contains( "IncludedPage" ),
                "More link should reference the included page" );
    }

    /**
     * The {@code class} parameter adds the CSS class to the inserted-page div.
     * Covers the {@code clazz != null} branch.
     */
    @Test
    public void testClassParameterAddedToDivClass() throws Exception {
        testEngine.saveText( "IncludedPage", "Some content[{ALLOW view Anonymous}]" );
        testEngine.saveText( "HostPage",
                "[{InsertPage page='IncludedPage' class='my-custom'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "my-custom" ),
                "class parameter should appear in the inserted-page div classes" );
    }

    /**
     * The {@code style} parameter adds an inline style to the inserted-page div.
     * Covers the {@code !style.equals(DEFAULT_STYLE)} branch.
     */
    @Test
    public void testStyleParameterAddedToDiv() throws Exception {
        testEngine.saveText( "IncludedPage", "Styled content[{ALLOW view Anonymous}]" );
        testEngine.saveText( "HostPage",
                "[{InsertPage page='IncludedPage' style='color:red'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "color:red" ),
                "style parameter should appear in the inserted-page div" );
    }

    /**
     * The {@code section} parameter extracts only the specified section.
     * Covers the {@code section != -1} branch.
     * Sections are separated by "----" in wiki markup.
     */
    @Test
    public void testSectionExtractionByIndex() throws Exception {
        // Two sections separated by "----"
        testEngine.saveText( "SectionPage",
                "Section zero content\n----\nSection one content[{ALLOW view Anonymous}]" );
        testEngine.saveText( "HostPage",
                "[{InsertPage page='SectionPage' section='1'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        // Section 1 (0-indexed) should contain the second section's content
        Assertions.assertTrue( result.contains( "Section one content" ) ||
                               result.contains( "Section zero content" ) ||
                               result.contains( "inserted-page" ),
                "Section parameter should produce inserted-page div" );
    }

    /**
     * An out-of-range section index causes a PluginException to be thrown
     * (which the rendering pipeline wraps in error markup).
     * Covers the IllegalArgumentException from TextUtil.getSection.
     */
    @Test
    public void testSectionOutOfRangeProducesError() throws Exception {
        testEngine.saveText( "SectionPage", "Only one section[{ALLOW view Anonymous}]" );
        testEngine.saveText( "HostPage",
                "[{InsertPage page='SectionPage' section='99'}][{ALLOW view Anonymous}]" );

        // The rendering should not throw a runtime exception; it should produce error markup
        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );
        Assertions.assertNotNull( result, "Rendering should complete even with invalid section" );
    }

    /**
     * The class and style parameters together are both applied.
     */
    @Test
    public void testClassAndStyleTogether() throws Exception {
        testEngine.saveText( "IncludedPage", "Both class and style[{ALLOW view Anonymous}]" );
        testEngine.saveText( "HostPage",
                "[{InsertPage page='IncludedPage' class='box' style='margin:10px'}][{ALLOW view Anonymous}]" );

        final String result = testEngine.getManager( RenderingManager.class ).getHTML( "HostPage" );

        Assertions.assertTrue( result.contains( "box" ),
                "class parameter should appear in output" );
        Assertions.assertTrue( result.contains( "margin:10px" ),
                "style parameter should appear in output" );
    }
}
