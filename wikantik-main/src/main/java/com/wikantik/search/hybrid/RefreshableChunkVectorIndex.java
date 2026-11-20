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
package com.wikantik.search.hybrid;

import java.util.Collection;
import java.util.UUID;

/**
 * A {@link ChunkVectorIndex} that holds an in-process snapshot of the stored embeddings
 * and so must be told when the database changes underneath it. pgvector, which reads
 * through to the database on every query, is deliberately NOT one of these.
 *
 * <p>Contract: both mutators fail closed (a SQL error is logged and the prior contents
 * stay live) and never throw to the caller.
 */
public interface RefreshableChunkVectorIndex extends ChunkVectorIndex {

    /** Rebuilds the whole snapshot from the database. */
    void reload();

    /**
     * Brings the snapshot up to date for the given chunks after their embeddings were written.
     *
     * @param chunkIds the chunks whose embeddings changed.
     */
    void upsertChunks( Collection< UUID > chunkIds );

    /** @return the number of vectors currently held; {@code 0} when the load failed or nothing is embedded. */
    int size();
}
