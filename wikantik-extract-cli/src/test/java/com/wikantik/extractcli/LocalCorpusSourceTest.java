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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCorpusSourceTest {

    private static void write( final Path dir, final String fileName, final String body ) throws Exception {
        Files.writeString( dir.resolve( fileName ), body );
    }

    @Test
    void reads_taxonomy_frontmatter_from_each_markdown_page( @TempDir final Path dir ) throws Exception {
        write( dir, "MLHub.md", """
                ---
                canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN
                type: hub
                cluster: machine-learning
                ---
                Body.
                """ );

        final CorpusSnapshot snap = new LocalCorpusSource( dir ).load();

        assertTrue( snap.complete() );
        final PageFacts f = snap.pages().get( "MLHub" );
        assertEquals( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", f.canonicalId() );
        assertEquals( "machine-learning", f.cluster() );
        assertEquals( "hub", f.type() );
    }

    /**
     * `AbstractFileProvider.mangleName()` URL-encodes page names, so a page named
     * "AgentLoops Hub" is stored as `AgentLoops+Hub.md`. Comparing raw filenames against
     * production page names would report every spaced-name page as diverging.
     */
    @Test
    void unmangles_the_filesystem_encoding_back_into_the_page_name( @TempDir final Path dir ) throws Exception {
        write( dir, "AgentLoops+Hub.md", """
                ---
                canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8AA
                type: hub
                cluster: agentic-ai/agent-loops
                ---
                Body.
                """ );

        final CorpusSnapshot snap = new LocalCorpusSource( dir ).load();

        assertTrue( snap.pages().containsKey( "AgentLoops Hub" ),
                    "expected the unmangled page name, got: " + snap.pages().keySet() );
    }

    @Test
    void a_page_with_no_frontmatter_still_appears_with_null_taxonomy( @TempDir final Path dir ) throws Exception {
        write( dir, "Bare.md", "Just a body, no frontmatter.\n" );

        final CorpusSnapshot snap = new LocalCorpusSource( dir ).load();

        assertTrue( snap.complete() );
        assertEquals( null, snap.pages().get( "Bare" ).cluster() );
    }

    @Test
    void a_missing_directory_is_reported_as_an_error_not_an_empty_corpus( @TempDir final Path dir ) {
        final CorpusSnapshot snap = new LocalCorpusSource( dir.resolve( "does-not-exist" ) ).load();

        assertTrue( snap.pages().isEmpty() );
        assertTrue( !snap.complete(),
                    "an unreadable corpus must not masquerade as an empty one" );
    }
}
