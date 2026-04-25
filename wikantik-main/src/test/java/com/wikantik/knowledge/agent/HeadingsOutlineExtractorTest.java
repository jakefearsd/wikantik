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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.HeadingOutline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeadingsOutlineExtractorTest {

    private final HeadingsOutlineExtractor extractor = new HeadingsOutlineExtractor();

    @Test
    void skips_h1_and_collects_h2_h3() {
        final String md = "# Title\n\nIntro text.\n\n## Wiring\n\n### Detail\n\n## Failure modes\n";
        final List< HeadingOutline > out = extractor.extract( md );
        assertEquals( 3, out.size() );
        assertEquals( 2, out.get( 0 ).level() );
        assertEquals( "Wiring", out.get( 0 ).text() );
        assertEquals( 3, out.get( 1 ).level() );
        assertEquals( "Detail", out.get( 1 ).text() );
        assertEquals( "Failure modes", out.get( 2 ).text() );
    }

    @Test
    void empty_or_null_body_returns_empty_list() {
        assertTrue( extractor.extract( null ).isEmpty() );
        assertTrue( extractor.extract( "" ).isEmpty() );
        assertTrue( extractor.extract( "no headings here, just prose" ).isEmpty() );
    }

    @Test
    void ignores_headings_inside_fenced_code_blocks() {
        final String md = "## Real\n\n```\n## Fake\n```\n\n## AlsoReal\n";
        final List< HeadingOutline > out = extractor.extract( md );
        assertEquals( 2, out.size() );
        assertEquals( "Real",     out.get( 0 ).text() );
        assertEquals( "AlsoReal", out.get( 1 ).text() );
    }

    @Test
    void caps_at_max_entries_to_protect_budget() {
        final StringBuilder b = new StringBuilder();
        for ( int i = 0; i < 200; i++ ) {
            b.append( "## H" ).append( i ).append( "\n\n" );
        }
        final List< HeadingOutline > out = extractor.extract( b.toString() );
        assertTrue( out.size() <= HeadingsOutlineExtractor.MAX_ENTRIES,
                "outline must be capped at " + HeadingsOutlineExtractor.MAX_ENTRIES );
    }

    @Test
    void trims_trailing_punctuation_and_anchor_links() {
        final String md = "## Wiring  \n## Failure modes [#failure-modes]\n";
        final List< HeadingOutline > out = extractor.extract( md );
        assertEquals( "Wiring", out.get( 0 ).text() );
        assertEquals( "Failure modes", out.get( 1 ).text() );
    }
}
