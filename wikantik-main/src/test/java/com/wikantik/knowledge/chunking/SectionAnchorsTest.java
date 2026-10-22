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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SectionAnchorsTest {

    @Test
    void anH2SectionLinksToItsOwnAnchor() {
        final Map<List<String>, String> anchors =
            SectionAnchors.byHeadingPath("## Intro\n\ntext\n\n## Second Part\n\nmore\n");
        assertEquals("intro", anchors.get(List.of("Intro")));
        assertEquals("second-part", anchors.get(List.of("Second Part")));
    }

    @Test
    void anH3SectionLinksToItsOwnAnchor() {
        assertEquals("install-steps",
            SectionAnchors.byHeadingPath("## Setup\n\n### Install Steps\n").get(List.of("Setup", "Install Steps")));
    }

    @Test
    void sectionsBelowH3LinkToTheNearestH3OrH2() {
        // The page view only anchors h2/h3, so a deeper section's link lands on its nearest anchored ancestor.
        final Map<List<String>, String> anchors =
            SectionAnchors.byHeadingPath("## Setup\n\n### Install\n\n#### Linux\n\n##### Debian\n\n## Usage\n\n#### Flags\n");
        assertEquals("install", anchors.get(List.of("Setup", "Install", "Linux")));
        assertEquals("install", anchors.get(List.of("Setup", "Install", "Linux", "Debian")));
        // An h4 straight under an h2 has an empty h3 slot in its path, as the chunker records it.
        assertEquals("usage", anchors.get(List.of("Usage", "", "Flags")));
    }

    @Test
    void duplicateTitlesResolveByPathToTheViewsNumberedAnchors() {
        final Map<List<String>, String> anchors =
            SectionAnchors.byHeadingPath("## Linux\n\n### Example\n\n## Mac\n\n### Example\n");
        assertEquals("example", anchors.get(List.of("Linux", "Example")));
        assertEquals("example-2", anchors.get(List.of("Mac", "Example")));
    }

    @Test
    void headingPathsKeepTheirMarkdownSourceLikeTheChunker() {
        assertEquals("using-foo-with-bar",
            SectionAnchors.byHeadingPath("## Using `Foo` with *Bar*\n").get(List.of("Using `Foo` with *Bar*")));
    }

    @Test
    void aPageTitleH1IsPartOfThePathButHasNoAnchorOfItsOwn() {
        final Map<List<String>, String> anchors = SectionAnchors.byHeadingPath("# Guide\n\nintro\n\n## Install\n");
        assertEquals("install", anchors.get(List.of("Guide", "Install")));
        assertFalse(anchors.containsKey(List.of("Guide")));
    }

    @Test
    void headingsTheRendererRewritesFallBackToTheirParentsAnchor() {
        // The view slugs the RENDERED text (wikilink label, plugin output, attribute block consumed), which this
        // parser cannot predict — so these never get a fragment of their own that could land nowhere.
        final Map<List<String>, String> anchors = SectionAnchors.byHeadingPath(
            "## Setup\n\n### See [[Foo|Bar]]\n\n### Output [{Counter}]\n\n### Title {#custom .wide}\n\n"
            + "### Using `{}` safely\n\n## [[Install]]\n");
        assertEquals("setup", anchors.get(List.of("Setup", "See [[Foo|Bar]]")));
        assertEquals("setup", anchors.get(List.of("Setup", "Output [{Counter}]")));
        assertEquals("setup", anchors.get(List.of("Setup", "Title {#custom .wide}")));
        assertEquals("using-safely", anchors.get(List.of("Setup", "Using `{}` safely")));
        assertFalse(anchors.containsKey(List.of("[[Install]]")));
    }

    @Test
    void aHeadingWithNoSlugCharactersHasNoAnchorOfItsOwn() {
        assertEquals("intro", SectionAnchors.byHeadingPath("## Intro\n\n### ???\n").get(List.of("Intro", "???")));
        assertTrue(SectionAnchors.byHeadingPath("## !!!\n").isEmpty());
    }

    @Test
    void anEmptyOrMissingBodyHasNoAnchors() {
        assertTrue(SectionAnchors.byHeadingPath("").isEmpty());
        assertTrue(SectionAnchors.byHeadingPath(null).isEmpty());
        assertTrue(SectionAnchors.byHeadingPath("just prose, no headings\n").isEmpty());
    }

    /**
     * Ties the resolver to the chunker: every heading path the chunker actually records for a
     * section under an h2/h3 must resolve, or bundle links silently lose their fragment.
     */
    @Test
    void everyChunkUnderAnAnchoredSectionResolves() {
        final String body = "# Guide\n\nIntro prose.\n\n"
            + "## Install `cli` *fast*\n\nStep one.\n\n```bash\n# not a heading\n```\n\n"
            + "### Linux\n\nApt.\n\n#### Debian\n\nDetails.\n\n"
            + "### Example\n\nOne.\n\n"
            + "## Usage\n\n#### Flags\n\nFlag text.\n\n"
            + "### Example\n\nTwo.\n";
        final List<Chunk> chunks = new ContentChunker(new ContentChunker.Config(512, 8, 0, 0))
            .chunk("Guide", new ParsedPage(Map.of(), body));
        final Map<List<String>, String> anchors = SectionAnchors.byHeadingPath(body);

        final List<List<String>> anchoredPaths = chunks.stream().map(Chunk::headingPath)
            .filter(p -> !p.equals(List.of("Guide"))).distinct().toList();
        // Usage has no prose of its own, so the chunker records no [Guide, Usage] path.
        assertEquals(6, anchoredPaths.size(), "fixture should exercise every section: " + anchoredPaths);
        for (final List<String> path : anchoredPaths) {
            assertTrue(anchors.containsKey(path), "chunk heading path has no anchor: " + path);
        }
        assertEquals("example", anchors.get(List.of("Guide", "Install `cli` *fast*", "Example")));
        assertEquals("example-2", anchors.get(List.of("Guide", "Usage", "Example")));
    }
}
