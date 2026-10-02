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
package com.wikantik.preview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageExcerptsTest {
    @Test void reducesMarkdownToPlainProse() {
        final String md = "# Title\n\nSee [the hub](IndexFundsHub) and **bold** text.\n\n```java\ncode();\n```\n\n- item one\n";
        assertEquals( "See the hub and bold text. item one", PageExcerpts.excerpt( md, 280 ) );
    }

    @Test void dropsAclAndPluginMarkup() {
        final String md = "[{ALLOW view Admin}]\n\nBefore [{TableOfContents}] after.\n";
        assertEquals( "Before after.", PageExcerpts.excerpt( md, 280 ) );
    }

    @Test void cutsAtAWordBoundaryWithAnEllipsis() {
        final String md = "alpha beta gamma delta epsilon";
        assertEquals( "alpha beta…", PageExcerpts.excerpt( md, 14 ) );
    }

    @Test void emptyBodyGivesEmptyExcerpt() {
        assertEquals( "", PageExcerpts.excerpt( "", 280 ) );
        assertEquals( "", PageExcerpts.excerpt( "## Only a heading\n", 280 ) );
    }

    @Test void calloutMarkersAreNotPartOfTheExcerpt() {
        final String md = "> [!warning] Draft\n> Numbers are provisional.\n\nThen prose.\n";
        assertEquals( "Draft Numbers are provisional. Then prose.", PageExcerpts.excerpt( md, 280 ) );
    }

    @Test void foldMarkerAndTitlelessCalloutsAreStrippedToo() {
        assertEquals( "Hidden detail.", PageExcerpts.excerpt( "> [!note]- \n> Hidden detail.\n", 280 ) );
        assertEquals( "Tip body.", PageExcerpts.excerpt( "> [!TIP]+\n> Tip body.\n", 280 ) );
    }

    @Test void onlyTheCalloutMarkerIsStrippedNotOtherBracketedText() {
        // Guard: a plain "[sic]" is not a callout marker, so its text survives (the collector drops brackets).
        assertEquals( "sic as quoted.", PageExcerpts.excerpt( "> [sic] as quoted.\n", 280 ) );
    }

    @Test void nativeWikiLinksReadAsPlainText() {
        assertEquals( "See the target, T > H done.",
                PageExcerpts.excerpt( "See [[Target|the target]], [[T#H]] ![[Embedded]] done.", 280 ) );
    }
}
