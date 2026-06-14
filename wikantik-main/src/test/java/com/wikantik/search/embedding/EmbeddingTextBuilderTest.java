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
package com.wikantik.search.embedding;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingTextBuilderTest {

    @Test
    void emptyPathReturnsBodyUnchanged() {
        assertEquals("body only",
            EmbeddingTextBuilder.forDocument(List.of(), "body only"));
    }

    @Test
    void nullPathReturnsBodyUnchanged() {
        assertEquals("body only",
            EmbeddingTextBuilder.forDocument(null, "body only"));
    }

    @Test
    void singleHeadingPrependsBeforeBody() {
        assertEquals("Section\n\nbody",
            EmbeddingTextBuilder.forDocument(List.of("Section"), "body"));
    }

    @Test
    void multipleHeadingsJoinedWithArrow() {
        assertEquals("Top > Mid > Leaf\n\nbody",
            EmbeddingTextBuilder.forDocument(List.of("Top", "Mid", "Leaf"), "body"));
    }

    @Test
    void blankHeadingsAreSkipped() {
        assertEquals("Top > Leaf\n\nbody",
            EmbeddingTextBuilder.forDocument(Arrays.asList("Top", "", "Leaf"), "body"));
    }

    @Test
    void allBlankHeadingsReturnsBodyUnchanged() {
        assertEquals("body",
            EmbeddingTextBuilder.forDocument(Arrays.asList("", "   "), "body"));
    }

    @Test
    void headingsAreTrimmed() {
        assertEquals("Top > Leaf\n\nbody",
            EmbeddingTextBuilder.forDocument(List.of("  Top ", " Leaf"), "body"));
    }

    @Test
    void nullBodyWithHeadingEmitsPathOnly() {
        assertEquals("Top > Leaf\n\n",
            EmbeddingTextBuilder.forDocument(List.of("Top", "Leaf"), null));
    }

    // ---- contextual (frontmatter) document format ----

    private static final EmbeddingTextBuilder.PageContext CTX =
        new EmbeddingTextBuilder.PageContext("Ollama Setup", "agentic-ai", "Local LLM serving guide.");

    @Test
    void contextualEmitsAllFieldsInOrder() {
        assertEquals(
            "Page: Ollama Setup | Cluster: agentic-ai | Section: Hardware Sizing > vRAM\n"
          + "Summary: Local LLM serving guide.\n\nA 7B model needs 8GB.",
            EmbeddingTextBuilder.forDocument(CTX, List.of("Hardware Sizing", "vRAM"), "A 7B model needs 8GB."));
    }

    @Test
    void contextualOmitsBlankFields() {
        // cluster + summary blank → only Page + Section appear, joined by " | "
        final EmbeddingTextBuilder.PageContext partial =
            new EmbeddingTextBuilder.PageContext("Title", null, "  ");
        assertEquals("Page: Title | Section: Sec\n\nbody",
            EmbeddingTextBuilder.forDocument(partial, List.of("Sec"), "body"));
    }

    @Test
    void contextualWithNoHeadingPathDropsSection() {
        assertEquals("Page: Title | Cluster: c\nSummary: s\n\nbody",
            EmbeddingTextBuilder.forDocument(
                new EmbeddingTextBuilder.PageContext("Title", "c", "s"), List.of(), "body"));
    }

    @Test
    void contextualEmptyContextFallsBackToBody() {
        assertEquals("body",
            EmbeddingTextBuilder.forDocument(EmbeddingTextBuilder.PageContext.EMPTY, List.of(), "body"));
    }

    @Test
    void contextualSummaryOnlyGoesOnItsOwnLine() {
        assertEquals("Summary: only\n\nbody",
            EmbeddingTextBuilder.forDocument(
                new EmbeddingTextBuilder.PageContext(null, null, "only"), List.of(), "body"));
    }
}
