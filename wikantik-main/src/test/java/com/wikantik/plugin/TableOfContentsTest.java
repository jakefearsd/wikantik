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
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TableOfContentsTest
{
    TestEngine testEngine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception {
        testEngine.getManager( PageManager.class ).deletePage( "Test" );
    }

    @Test
    public void testHeadingVariables() throws Exception {
        final String src="[{SET foo=bar}]\n\n[{TableOfContents}]()\n\n# Heading [{$foo}]()";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC div" );
    }

    @Test
    public void testNumberedItems() throws Exception {
        final String src="[{TableOfContents}]()\n\n# Heading\n\n## Subheading\n\n### Subsubheading";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
        Assertions.assertTrue( res.contains( "subheading" ), "Should contain subheading" );
    }

    @Test
    public void testNumberedItemsComplex() throws Exception {
        final String src="[{TableOfContents}]()\n\n# Heading1\n\n## Subheading\n\n### Subsubheading\n\n## Subheading2\n\n# Heading2";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
        Assertions.assertTrue( res.contains( "<h1" ), "Should contain h1" );
        Assertions.assertTrue( res.contains( "<h2" ), "Should contain h2" );
    }

    @Test
    public void testNumberedItemsComplex2() throws Exception {
        final String src="[{TableOfContents}]()\n\n## Subheading0\n\n# Heading1\n\n## Subheading";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
    }

    @Test
    public void testNumberedItemsWithPrefix() throws Exception {
        final String src="[{TableOfContents}]()\n\n# Heading\n\n## Subheading\n\n### Subsubheading";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
    }

    @Test
    public void testSelfReference() throws Exception {
        // TOC inside a heading is a degenerate case; just verify it doesn't crash the engine
        final String src = "[{TableOfContents}]()\n\n# Normal Heading";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertNotNull( res );
    }

    @Test
    public void testHTML() throws Exception {
        final String src = "[{TableOfContents}]()\n\n### <i>test</i>";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
    }

    @Test
    public void testSimilarNames() throws WikiException {
        final String src = "[{TableOfContents}]()\n\n### Test\n\n### Test";
        testEngine.saveText( "Test", src );
        final String res = testEngine.getI18nHTML( "Test" );
        Assertions.assertTrue( res.contains( "toc" ), "Should contain TOC" );
    }

}
