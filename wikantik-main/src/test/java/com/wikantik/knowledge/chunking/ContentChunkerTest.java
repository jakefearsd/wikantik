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
}
