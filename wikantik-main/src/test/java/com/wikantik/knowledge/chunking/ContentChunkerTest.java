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

import static org.junit.jupiter.api.Assertions.*;

class ContentChunkerTest {

    private final ContentChunker chunker = new ContentChunker(
        new ContentChunker.Config(300, 512, 80));

    @Test
    void emptyBodyProducesZeroChunks() {
        ParsedPage page = new ParsedPage(java.util.Map.of(), "");
        List<Chunk> chunks = chunker.chunk("Empty", page);
        assertEquals(0, chunks.size());
    }

    @Test
    void singleShortParagraphProducesOneChunk() {
        String body = "This is a single short paragraph of prose "
                    + "intended to exercise the simplest happy path.";
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Short", page);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals("Short", chunks.get(0).pageName());
        assertTrue(chunks.get(0).headingPath().isEmpty());
        assertTrue(chunks.get(0).text().contains("single short paragraph"));
        assertTrue(chunks.get(0).charCount() > 0);
        assertTrue(chunks.get(0).tokenCountEstimate() > 0);
        assertNotNull(chunks.get(0).contentHash());
    }

    @Test
    void threeShortSectionsProduceThreeChunksWithHeadingPaths() {
        String body = """
            # Top Title

            ## First Section

            Alpha paragraph with enough words to count as a real chunk of prose.

            ## Second Section

            Bravo paragraph, also substantive enough to be emitted on its own.

            ## Third Section

            Charlie paragraph — again, long enough to warrant emission.
            """;
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Sections", page);
        assertEquals(3, chunks.size());
        assertEquals(List.of("Top Title", "First Section"), chunks.get(0).headingPath());
        assertEquals(List.of("Top Title", "Second Section"), chunks.get(1).headingPath());
        assertEquals(List.of("Top Title", "Third Section"), chunks.get(2).headingPath());
    }

    @Test
    void headingStackPopsOnShallowerHeading() {
        String body = """
            # One

            ## Two

            ### Three

            Leaf text sufficient to be emitted.

            ## TwoPrime

            Sibling text also long enough to warrant emission.
            """;
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Pops", page);
        assertEquals(2, chunks.size());
        assertEquals(List.of("One", "Two", "Three"), chunks.get(0).headingPath());
        assertEquals(List.of("One", "TwoPrime"), chunks.get(1).headingPath());
    }

    @Test
    void headingWithInlineMarkupPreservesFullTitle() {
        String body = """
            # Top

            ## First *emphasized* Section

            Body paragraph under the emphasized-title section, long enough to be emitted as its own chunk.
            """;
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Inline", page);
        assertEquals(1, chunks.size());
        assertEquals(2, chunks.get(0).headingPath().size());
        assertEquals("Top", chunks.get(0).headingPath().get(0));
        String second = chunks.get(0).headingPath().get(1);
        assertTrue(second.contains("First"), "title contains First: " + second);
        assertTrue(second.contains("emphasized"), "title contains emphasized: " + second);
        assertTrue(second.contains("Section"), "title contains Section: " + second);
    }

    @Test
    void oversizedParagraphIsSplitOnSentenceBoundaries() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            big.append("Sentence number ").append(i).append(" filler text. ");
        }
        ParsedPage page = new ParsedPage(java.util.Map.of(), big.toString());
        List<Chunk> chunks = chunker.chunk("Big", page);
        assertTrue(chunks.size() > 1, "expected multiple chunks, got " + chunks.size());
        for (Chunk c : chunks) {
            assertTrue(c.tokenCountEstimate() <= 512,
                "chunk " + c.chunkIndex() + " exceeds max: " + c.tokenCountEstimate());
        }
    }

    @Test
    void fencedCodeBlockIsAtomicEvenIfOversized() {
        StringBuilder body = new StringBuilder("# Title\n\n```\n");
        for (int i = 0; i < 200; i++) {
            body.append("public int x").append(i).append(" = ").append(i).append(";\n");
        }
        body.append("```\n");
        ParsedPage page = new ParsedPage(java.util.Map.of(), body.toString());
        List<Chunk> chunks = chunker.chunk("Code", page);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("public int x0 = 0;"));
        assertTrue(chunks.get(0).text().contains("public int x199 = 199;"));
    }

    @Test
    void shortChunkBelowMinMergesForwardAcrossHeadingBoundary() {
        String body = """
            ## Stub

            Tiny.

            ## Real

            This paragraph has plenty of real content to ensure we cross the min
            threshold on merge and emit a single well-formed chunk.
            """;
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Merge", page);
        assertEquals(1, chunks.size(), "tiny first section should merge forward");
        assertEquals(List.of("Stub"), chunks.get(0).headingPath(),
                     "merged chunk carries first section's heading path");
        assertTrue(chunks.get(0).text().contains("Tiny."));
        assertTrue(chunks.get(0).text().contains("plenty of real content"));
    }

    @Test
    void pluginMarkupIsPreservedVerbatim() {
        String body = "[{Plugin}] then some prose of reasonable length to be emitted.";
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Plugin", page);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).text().contains("[{Plugin}]"));
    }

    @Test
    void contentHashIsDeterministicAndSensitiveToHeadingPath() {
        String body = "## A\n\nSame body text repeated across two sections.\n\n"
                    + "## B\n\nSame body text repeated across two sections.\n";
        ParsedPage page = new ParsedPage(java.util.Map.of(), body);
        List<Chunk> chunks = chunker.chunk("Hash", page);
        assertEquals(2, chunks.size());
        assertNotEquals(chunks.get(0).contentHash(), chunks.get(1).contentHash(),
            "identical body text under different headings must hash differently");

        List<Chunk> again = chunker.chunk("Hash", page);
        assertEquals(chunks.get(0).contentHash(), again.get(0).contentHash());
        assertEquals(chunks.get(1).contentHash(), again.get(1).contentHash());
    }
}
