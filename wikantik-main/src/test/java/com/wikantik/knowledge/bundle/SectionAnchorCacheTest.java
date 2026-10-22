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
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SectionAnchorCacheTest {

    /** Records every body it is asked to parse; answers one anchor per call. */
    private final List< String > parsed = new ArrayList<>();
    private final Function< String, Map< List< String >, String > > parser = body -> {
        parsed.add( body );
        return Map.of( List.of( "H" ), "h" );
    };

    @Test
    void anUnchangedPageIsParsedOnce() {
        final SectionAnchorCache cache = new SectionAnchorCache( 10, parser );

        assertEquals( "h", cache.anchorsFor( "PageX", "## H\n\ntext\n" ).get( List.of( "H" ) ) );
        assertEquals( "h", cache.anchorsFor( "PageX", "## H\n\ntext\n" ).get( List.of( "H" ) ) );

        assertEquals( 1, parsed.size() );
    }

    @Test
    void anEditedPageIsParsedAgain() {
        final SectionAnchorCache cache = new SectionAnchorCache( 10, parser );
        cache.anchorsFor( "PageX", "## H\n\nold\n" );
        cache.anchorsFor( "PageX", "## H\n\nnew\n" );
        assertEquals( 2, parsed.size() );
    }

    @Test
    void onlyTheBodyIsParsed_frontmatterIsStrippedWithoutReadingIt() {
        new SectionAnchorCache( 10, parser ).anchorsFor( "PageX", "---\nsummary: [unclosed\n---\n## H\n" );
        assertEquals( List.of( "## H\n" ), parsed );
    }

    @Test
    void theLeastRecentlyUsedPageIsEvictedBeyondCapacity() {
        final SectionAnchorCache cache = new SectionAnchorCache( 2, parser );
        cache.anchorsFor( "A", "## A\n" );
        cache.anchorsFor( "B", "## B\n" );
        cache.anchorsFor( "A", "## A\n" );   // A is now the most recently used
        cache.anchorsFor( "C", "## C\n" );   // evicts B
        assertEquals( 3, parsed.size() );

        cache.anchorsFor( "A", "## A\n" );
        assertEquals( 3, parsed.size(), "A stayed cached" );
        cache.anchorsFor( "B", "## B\n" );
        assertEquals( 4, parsed.size(), "B was evicted" );
    }

    @Test
    void aMissingPageHasNoAnchorsAndIsNotParsed() {
        assertTrue( new SectionAnchorCache( 10, parser ).anchorsFor( "Gone", null ).isEmpty() );
        assertTrue( parsed.isEmpty() );
    }
}
