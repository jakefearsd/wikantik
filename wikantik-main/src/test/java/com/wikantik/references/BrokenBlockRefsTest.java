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
package com.wikantik.references;

import com.wikantik.test.StubPageManager;
import com.wikantik.wikilink.WikiLinkResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokenBlockRefsTest {

    private static WikiLinkResolver resolver( final String... pages ) {
        final Set< String > names = Set.of( pages );
        return new WikiLinkResolver( n -> names.contains( n ) ? n : null, Optional::empty );
    }

    @Test
    void reportsMissingIdsOnExistingTargetsAndSamePage() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "see [[B#^x]] and [[B#^ok]] and [[#^self]]" );
        pm.savePage( "B", "Para ^ok" );
        pm.savePage( "C", "![[Missing#^q]]" );

        final List< BrokenBlockRefs.Entry > out = new BrokenBlockRefs().find( pm, resolver( "A", "B", "C" ) );

        assertEquals( List.of( new BrokenBlockRefs.Entry( "A", "A", "self" ),
                new BrokenBlockRefs.Entry( "A", "B", "x" ) ), out );
    }

    @Test
    void linksInsideCodeAreIgnoredAndHeadingLinksAreNotBlockRefs() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "`[[B#^x]]` and [[B#Heading]] and [[B]]\n\n```\n[[B#^y]]\n```\n" );
        pm.savePage( "B", "text" );
        assertTrue( new BrokenBlockRefs().find( pm, resolver( "A", "B" ) ).isEmpty() );
    }

    @Test
    void sortedAndDeduplicatedAcrossPages() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "Z", "[[B#^b]] [[B#^a]] [[B#^a]]" );
        pm.savePage( "A", "[[B#^c]]" );
        pm.savePage( "B", "nothing" );
        final List< BrokenBlockRefs.Entry > out = new BrokenBlockRefs().find( pm, resolver( "A", "B", "Z" ) );
        assertEquals( List.of( new BrokenBlockRefs.Entry( "A", "B", "c" ), new BrokenBlockRefs.Entry( "Z", "B", "a" ),
                new BrokenBlockRefs.Entry( "Z", "B", "b" ) ), out );
    }

    @Test
    void aliasResolvedTargetReportsResolvedPageName() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "[[bee#^x]]" );
        pm.savePage( "B", "t ^x" );
        pm.savePage( "D", "[[bee#^nope]]" );
        final WikiLinkResolver r = new WikiLinkResolver( n -> "bee".equals( n ) ? "B" : ( Set.of( "A", "B", "D" ).contains( n ) ? n : null ),
                Optional::empty );
        assertEquals( List.of( new BrokenBlockRefs.Entry( "D", "B", "nope" ) ), new BrokenBlockRefs().find( pm, r ) );
    }
}
