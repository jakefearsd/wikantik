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
package com.wikantik.admin;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of the live state of the combined Lucene + chunk-table
 * content index. Returned by
 * {@link ContentIndexRebuildService#snapshot()} and by
 * {@link ContentIndexRebuildService#triggerRebuild()} so callers can poll
 * rebuild progress without holding a lock.
 *
 * <p>The nested records mirror the JSON shape emitted by the admin REST
 * endpoint in a later task — keep field names stable.</p>
 */
public record IndexStatusSnapshot( Pages pages,
                                   Lucene lucene,
                                   Chunks chunks,
                                   Embeddings embeddings,
                                   Rebuild rebuild ) {

    /**
     * Backwards-compatible constructor for callers that pre-date the
     * embedding index (Phase 1 of hybrid retrieval). Fills
     * {@link #embeddings()} with an empty snapshot.
     */
    public IndexStatusSnapshot( final Pages pages, final Lucene lucene,
                                final Chunks chunks, final Rebuild rebuild ) {
        this( pages, lucene, chunks, Embeddings.empty(), rebuild );
    }

    /**
     * Page-population counts. {@code indexable = total - system}.
     */
    public record Pages( int total, int system, int indexable ) {}

    /**
     * Lucene index metrics.
     *
     * @param documentsIndexed documents currently held by the Lucene index
     * @param queueDepth       pages waiting on the async reindex queue
     * @param lastUpdate       timestamp of the most recent successful update,
     *                         {@code null} if the index has never been touched
     */
    public record Lucene( int documentsIndexed, int queueDepth, Instant lastUpdate ) {}

    /**
     * Aggregate statistics over the {@code kg_content_chunks} table.
     */
    public record Chunks( int pagesWithChunks,
                          int pagesMissingChunks,
                          int totalChunks,
                          int avgTokens,
                          int minTokens,
                          int maxTokens ) {}

    /**
     * Aggregate stats over {@code content_chunk_embeddings} for the current
     * production model. A null/empty model code means the embedding subsystem
     * has not been configured yet — UI should treat it as "disabled".
     *
     * @param modelCode   model identifier the counts are for
     *                    (empty string when disabled)
     * @param dim         vector dimension currently on record; {@code 0} when
     *                    no rows exist
     * @param rowCount    number of {@code (chunk_id, model_code)} rows
     * @param lastUpdate  most recent {@code updated} timestamp, or {@code null}
     *                    if no rows exist
     */
    public record Embeddings( String modelCode, int dim, int rowCount, Instant lastUpdate ) {
        public static Embeddings empty() { return new Embeddings( "", 0, 0, null ); }
    }

    /**
     * Rebuild run progress. All counters are zero while {@code state == "IDLE"}.
     */
    public record Rebuild( String state,
                           Instant startedAt,
                           int pagesTotal,
                           int pagesIterated,
                           int pagesChunked,
                           int systemPagesSkipped,
                           int luceneQueued,
                           int chunksWritten,
                           List< RebuildError > errors ) {}

    /**
     * A single per-page failure recorded during a rebuild run.
     */
    public record RebuildError( String page, String error, Instant at ) {}
}
