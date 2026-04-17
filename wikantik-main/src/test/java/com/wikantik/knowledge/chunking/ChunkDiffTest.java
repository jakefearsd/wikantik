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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkDiffTest {

    private static ChunkDiff.Stored stored(int idx, String hash) {
        return new ChunkDiff.Stored(UUID.randomUUID(), idx, hash);
    }

    private static Chunk produced(int idx, String hash) {
        return new Chunk("P", idx, List.of(), "text-" + idx, 6, 2, hash);
    }

    @Test
    void unchangedChunksAreClassifiedAsNoop() {
        var existing = List.of(stored(0, "aaa"), stored(1, "bbb"));
        var produced = List.of(produced(0, "aaa"), produced(1, "bbb"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(0, diff.inserts().size());
        assertEquals(0, diff.updates().size());
        assertEquals(0, diff.deletes().size());
    }

    @Test
    void hashMismatchProducesUpdateKeepingId() {
        var existing = List.of(stored(0, "aaa"));
        var old = existing.get(0);
        var produced = List.of(produced(0, "zzz"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.updates().size());
        assertEquals(old.id(), diff.updates().get(0).existingId());
    }

    @Test
    void newIndexBecomesInsert() {
        var existing = List.of(stored(0, "aaa"));
        var produced = List.of(produced(0, "aaa"), produced(1, "bbb"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.inserts().size());
        assertEquals(1, diff.inserts().get(0).chunkIndex());
    }

    @Test
    void missingIndexBecomesDelete() {
        var existing = List.of(stored(0, "aaa"), stored(1, "bbb"));
        var produced = List.of(produced(0, "aaa"));
        var diff = ChunkDiff.compute(existing, produced);
        assertEquals(1, diff.deletes().size());
        assertEquals(existing.get(1).id(), diff.deletes().get(0));
    }
}
