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
package com.wikantik.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArticleTextExtractorTest {

    @Test
    void titleComesFromMarkupH1() {
        assertEquals( "Real Title", ArticleTextExtractor.title( "intro\n#  Real Title  \nbody", "<h1>Other</h1>" ) );
    }

    @Test
    void titleFallsBackToRenderedHtmlH1WithEntitiesDecoded() {
        assertEquals( "A & B", ArticleTextExtractor.title( "no heading", "<h1 id=\"x\">A &amp; B</h1>" ) );
    }

    @Test
    void titleIsNullWhenNoH1Anywhere() {
        assertNull( ArticleTextExtractor.title( null, null ) );
        assertNull( ArticleTextExtractor.title( "plain", "<p>plain</p>" ) );
    }

    @Test
    void stripHtmlDropsNestedTagsAndDecodesEntities() {
        assertEquals( "Hello & <world> \"q\"",
                ArticleTextExtractor.stripHtml( "<p>Hello <b><i>&amp;</i></b> &lt;world&gt; &quot;q&quot;</p>" ) );
    }

    @Test
    void stripHtmlDecodesNumericEntitiesAndKeepsInvalidOnes() {
        assertEquals( "A A 💡 &#99999999;", ArticleTextExtractor.stripHtml( "&#65; &#x41; &#x1F4A1; &#99999999;" ) );
    }

    @Test
    void stripHtmlOfNullIsEmpty() {
        assertEquals( "", ArticleTextExtractor.stripHtml( null ) );
    }

    @Test
    void excerptIsNullForNullEmptyOrShortText() {
        assertNull( ArticleTextExtractor.excerpt( null, 100 ) );
        assertNull( ArticleTextExtractor.excerpt( "", 100 ) );
        assertNull( ArticleTextExtractor.excerpt( "<p>too short</p>", 100 ) );
    }

    @Test
    void excerptCollapsesWhitespaceAndTruncatesAtWordBoundary() {
        final String html = "<div><p>alpha   beta\n gamma </p><p>delta epsilon zeta eta theta</p></div>";
        assertEquals( "alpha beta gamma delta...", ArticleTextExtractor.excerpt( html, 25 ) );
    }

    @Test
    void excerptBreaksAtMaxLengthWhenNoSpaceIsNear() {
        assertEquals( "abcdefghij...", ArticleTextExtractor.excerpt( "<p>abcdefghijklmnopqrstuvwxyz0123</p>", 10 ) );
    }
}
