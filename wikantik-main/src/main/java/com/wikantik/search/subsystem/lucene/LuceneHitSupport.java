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
package com.wikantik.search.subsystem.lucene;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;

/**
 * Stateless per-hit helpers used by {@link DefaultLuceneSearcher}: recency scoring
 * and id extraction from a scored hit. Split out of the searcher so that class
 * stays focused on query execution and ACL filtering rather than accreting the
 * scoring/columnar-read utilities as well.
 */
public final class LuceneHitSupport {

    /** Half-life for recency decay: a page's recency multiplier falls from 1.0 toward 0.5 as it ages. */
    private static final long RECENCY_HALF_LIFE_MS = 365L * 24 * 60 * 60 * 1000;

    /** Floor for the recency multiplier so that very old pages still rank (just behind fresh ones). */
    private static final double RECENCY_FLOOR = 0.5;

    private LuceneHitSupport() {}

    /**
     * Recency multiplier for a hit: {@code 1.0} for a just-modified page, decaying toward
     * {@link #RECENCY_FLOOR} on a {@link #RECENCY_HALF_LIFE_MS} half-life.
     *
     * @param lastModifiedMs epoch millis of the page's last modification
     * @param nowMs          epoch millis of "now" (injected for testability)
     * @return recency multiplier in {@code [RECENCY_FLOOR, 1.0]}
     */
    public static double recencyFactor( final long lastModifiedMs, final long nowMs ) {
        final long ageMs = Math.max( 0L, nowMs - lastModifiedMs );
        final double decay = Math.pow( 0.5, ( double ) ageMs / RECENCY_HALF_LIFE_MS );
        return RECENCY_FLOOR + ( 1.0 - RECENCY_FLOOR ) * decay;
    }

    /**
     * Reads a hit's page id ({@link DefaultLuceneIndexer#LUCENE_ID}) from columnar
     * DocValues. Unlike a stored-field read, this needs no per-hit decompression of
     * the stored-fields block — the dominant search CPU + allocation cost when
     * highlighting is off (the default read path). The {@code globalDocId} is mapped
     * to its segment via {@link ReaderUtil#subIndex} and read at the leaf-local
     * doc id ({@code globalDocId - docBase}).
     *
     * <p>Falls back to the stored field for index segments written before the
     * DocValues field existed, so correctness never depends on a reindex — only the
     * speedup does (a reindex populates DocValues on every segment).</p>
     */
    static String readPageId( final IndexReader reader,
                              final StoredFields storedFields,
                              final int globalDocId,
                              final java.util.Set< String > idOnlyFields ) throws IOException {
        final List< LeafReaderContext > leaves = reader.leaves();
        final LeafReaderContext leaf = leaves.get( ReaderUtil.subIndex( globalDocId, leaves ) );
        final BinaryDocValues dv = leaf.reader().getBinaryDocValues( DefaultLuceneIndexer.LUCENE_ID );
        if ( dv != null && dv.advanceExact( globalDocId - leaf.docBase ) ) {
            return dv.binaryValue().utf8ToString();
        }
        // Pre-DocValues segment — fall back to the stored field.
        return storedFields.document( globalDocId, idOnlyFields ).get( DefaultLuceneIndexer.LUCENE_ID );
    }
}
