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
package com.wikantik.extractcli.mainpage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainPageRendererTest {

    private final MainPageRenderer renderer = new MainPageRenderer();

    @Test
    void renders_canonical_id_frontmatter_block() {
        final String out = renderer.render( new MainPageData( "", "", List.of(), List.of() ) );
        assertTrue( out.startsWith( "---\n" ) );
        assertTrue( out.contains( "canonical_id:" ) );
        assertTrue( out.contains( "AUTO-GENERATED" ) );
    }

    @Test
    void renders_intro_section_and_footer() {
        final var data = new MainPageData(
                "Hello.",
                "Powered by tests.",
                List.of( new MainPageData.Section( "Tech", "tech", List.of(
                        new MainPageData.Page( "01A", "Alpha", "Alpha Title", "Alpha summary." ),
                        new MainPageData.Page( "01B", "Beta", "Beta Title", "" ) ) ) ),
                List.of() );
        final String out = renderer.render( data );

        assertTrue( out.contains( "Hello." ) );
        assertTrue( out.contains( "## Article Clusters" ) );
        assertTrue( out.contains( "### Tech" ) );
        assertTrue( out.contains( "[Alpha Title](Alpha) — Alpha summary." ) );
        // Beta has no summary; the "—" must be omitted.
        assertTrue( out.contains( "[Beta Title](Beta)" ) );
        assertFalse( out.contains( "[Beta Title](Beta) —" ) );
        assertTrue( out.contains( "## About This Wiki" ) );
        assertTrue( out.contains( "Powered by tests." ) );
    }

    @Test
    void empty_sections_omits_article_clusters_heading() {
        final var data = new MainPageData( "Hi.", "", List.of(), List.of() );
        final String out = renderer.render( data );
        assertFalse( out.contains( "## Article Clusters" ) );
    }

    @Test
    void output_ends_with_single_newline_and_uses_lf() {
        final var data = new MainPageData( "x", "y",
                List.of( new MainPageData.Section( "S", null, List.of(
                        new MainPageData.Page( "01A", "A", "A", "s" ) ) ) ),
                List.of() );
        final String out = renderer.render( data );
        assertTrue( out.endsWith( "\n" ) );
        assertFalse( out.endsWith( "\n\n" ) );
        assertFalse( out.contains( "\r\n" ) );
    }

    @Test
    void render_is_deterministic_given_equal_input() {
        final var data = new MainPageData( "intro", "footer",
                List.of( new MainPageData.Section( "S", null, List.of(
                        new MainPageData.Page( "01A", "A", "Alpha", "summary" ) ) ) ),
                List.of() );
        assertEquals( renderer.render( data ), renderer.render( data ) );
    }
}
