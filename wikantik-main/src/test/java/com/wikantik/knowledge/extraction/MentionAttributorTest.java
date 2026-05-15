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
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MentionAttributorTest {

    private final MentionAttributor attr = new MentionAttributor();

    @Test
    void wholeWordMatchOnly() {
        final UUID chunkId = UUID.randomUUID();
        final UUID javaNodeId = UUID.randomUUID();
        final UUID jsNodeId   = UUID.randomUUID();
        final List<MentionAttributor.NameMapping> names = List.of(
            new MentionAttributor.NameMapping(javaNodeId, "Java"),
            new MentionAttributor.NameMapping(jsNodeId, "JavaScript")
        );
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "I write Java and JavaScript code.", names);
        final long javaMatches = matches.stream().filter(m -> m.nodeId().equals(javaNodeId)).count();
        final long jsMatches   = matches.stream().filter(m -> m.nodeId().equals(jsNodeId)).count();
        assertEquals(1, javaMatches, "Java must not match inside JavaScript");
        assertEquals(1, jsMatches);
    }

    @Test
    void caseInsensitivePresenceCaseSensitiveSurface() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "kafka is great. Kafka really is.",
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka")));
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> "kafka".equals(m.surfaceForm())));
        assertTrue(matches.stream().anyMatch(m -> "Kafka".equals(m.surfaceForm())));
    }

    @Test
    void noMatchesReturnsEmpty() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "Nothing relevant here.",
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka")));
        assertTrue(matches.isEmpty());
    }

    @Test
    void emptyChunkTextReturnsEmpty() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        assertTrue(attr.attribute(
            chunkId, "",
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka"))).isEmpty());
    }

    @Test
    void emptyNamesReturnsEmpty() {
        final UUID chunkId = UUID.randomUUID();
        assertTrue(attr.attribute(chunkId, "Kafka and Spark are streaming systems.", List.of()).isEmpty());
    }

    @Test
    void offsetsArePreservedForEachOccurrence() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        final String text = "Kafka. Then Kafka.";
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, text,
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka")));
        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).startOffset());
        assertEquals(5, matches.get(0).endOffset());
        assertEquals(text.indexOf("Kafka", 1), matches.get(1).startOffset());
    }

    @Test
    void regexMetacharactersInNameAreLiteralized() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "We use C++ daily.",
            List.of(new MentionAttributor.NameMapping(nodeId, "C++")));
        assertEquals(1, matches.size());
        assertEquals("C++", matches.get(0).surfaceForm());
    }

    @Test
    void repeatedCallsWithSameNamesYieldIdenticalResults() {
        final UUID chunkId = UUID.randomUUID();
        final UUID nodeId = UUID.randomUUID();
        final List<MentionAttributor.NameMapping> names =
            List.of(new MentionAttributor.NameMapping(nodeId, "Kafka"));
        final List<MentionAttributor.ChunkMention> first =
            attr.attribute(chunkId, "Kafka and Kafka.", names);
        final List<MentionAttributor.ChunkMention> second =
            attr.attribute(chunkId, "Kafka and Kafka.", names);
        final List<MentionAttributor.ChunkMention> third =
            attr.attribute(chunkId, "Kafka and Kafka.", names);
        assertEquals(2, first.size());
        assertEquals(first, second, "repeated calls must be deterministic");
        assertEquals(first, third);
    }

    @Test
    void interleavedDifferentNameSetsDoNotCrossContaminate() {
        final UUID chunkId = UUID.randomUUID();
        final UUID kafka = UUID.randomUUID();
        final UUID spark = UUID.randomUUID();
        final List<MentionAttributor.NameMapping> k =
            List.of(new MentionAttributor.NameMapping(kafka, "Kafka"));
        final List<MentionAttributor.NameMapping> s =
            List.of(new MentionAttributor.NameMapping(spark, "Spark"));
        assertEquals(1, attr.attribute(chunkId, "Kafka here", k).size());
        assertEquals(1, attr.attribute(chunkId, "Spark here", s).size());
        // Re-querying after an interleaved call must still be correct.
        assertEquals(1, attr.attribute(chunkId, "Kafka here", k).size());
        assertTrue(attr.attribute(chunkId, "Spark here", k).isEmpty());
    }

    @Test
    void multipleNamesAreAttributedIndependently() {
        final UUID chunkId = UUID.randomUUID();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final List<MentionAttributor.ChunkMention> matches = attr.attribute(
            chunkId, "Apache Kafka and Apache Spark.",
            List.of(new MentionAttributor.NameMapping(a, "Kafka"),
                    new MentionAttributor.NameMapping(b, "Spark")));
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(m -> m.nodeId().equals(a) && "Kafka".equals(m.surfaceForm())));
        assertTrue(matches.stream().anyMatch(m -> m.nodeId().equals(b) && "Spark".equals(m.surfaceForm())));
    }
}
