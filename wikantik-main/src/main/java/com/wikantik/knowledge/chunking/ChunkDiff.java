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

import java.util.*;

public final class ChunkDiff {

    public record Stored(UUID id, int chunkIndex, String contentHash) {}
    public record Update(UUID existingId, Chunk replacement) {}
    public record Diff(List<Chunk> inserts, List<Update> updates, List<UUID> deletes) {}

    private ChunkDiff() {}

    public static Diff compute(List<Stored> existing, List<Chunk> produced) {
        Map<Integer, Stored> byIndex = new HashMap<>();
        for (Stored s : existing) byIndex.put(s.chunkIndex(), s);

        List<Chunk> inserts = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        Set<Integer> producedIndexes = new HashSet<>();

        for (Chunk p : produced) {
            producedIndexes.add(p.chunkIndex());
            Stored prior = byIndex.get(p.chunkIndex());
            if (prior == null) {
                inserts.add(p);
            } else if (!prior.contentHash().equals(p.contentHash())) {
                updates.add(new Update(prior.id(), p));
            }
        }

        List<UUID> deletes = new ArrayList<>();
        for (Stored s : existing) {
            if (!producedIndexes.contains(s.chunkIndex())) deletes.add(s.id());
        }
        return new Diff(
            List.copyOf(inserts),
            List.copyOf(updates),
            List.copyOf(deletes));
    }
}
