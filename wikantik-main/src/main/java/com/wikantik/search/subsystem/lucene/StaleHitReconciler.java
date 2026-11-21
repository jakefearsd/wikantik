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

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The policy for a Lucene hit whose page could not be loaded: remove the index entry only when the
 * page is confirmed absent, and otherwise leave the index alone.
 *
 * <p>A null page is AMBIGUOUS: the page may genuinely be gone, or the page provider may have failed
 * transiently (an FS race, a closed cache). Deleting on that signal lets one bad read permanently
 * drop a valid document from the index, and the only symptom the user ever sees is fewer results.
 * So the absence is confirmed explicitly, and when the check itself cannot answer the index is left
 * alone — a stale entry is filtered out of results anyway, so keeping it is strictly safer than
 * deleting a live one.</p>
 */
final class StaleHitReconciler {

    private static final Logger LOG = LogManager.getLogger( StaleHitReconciler.class );

    /** What {@link #reconcile} did with the index entry. */
    enum Outcome {
        /** The page is confirmed gone; its index entry was removed. */
        DELETED_FROM_INDEX,
        /** The page still exists, or its absence could not be confirmed; the index is untouched. */
        KEPT
    }

    private final PageManager pageManager;
    private final LuceneIndexer indexer;
    private final Engine engine;

    StaleHitReconciler( final PageManager pageManager, final LuceneIndexer indexer, final Engine engine ) {
        this.pageManager = pageManager;
        this.indexer = indexer;
        this.engine = engine;
    }

    /**
     * Reconciles the index with a hit whose page loaded as null.
     *
     * @param pageName the hit's page name
     * @return {@link Outcome#DELETED_FROM_INDEX} when absence was confirmed, else {@link Outcome#KEPT}
     */
    Outcome reconcile( final String pageName ) {
        boolean confirmedAbsent;
        try {
            confirmedAbsent = !pageManager.wikiPageExists( pageName, PageProvider.LATEST_VERSION );
        } catch ( final ProviderException e ) {
            LOG.warn( "Lucene hit '{}' could not be loaded and its existence could not be confirmed "
                      + "({}); leaving the index entry in place", pageName, e.getMessage(), e );
            confirmedAbsent = false;
        }
        if ( confirmedAbsent ) {
            // LOG.error justified: stale index entry; Lucene references a page that no longer exists in the page store.
            LOG.error( "Lucene found a result page '{}' that no longer exists, removing from Lucene cache",
                       pageName );
            indexer.pageRemoved( Wiki.contents().page( engine, pageName ) );
            return Outcome.DELETED_FROM_INDEX;
        }
        LOG.warn( "Lucene hit '{}' could not be loaded but the page still exists; "
                  + "skipping it for this search and leaving the index intact", pageName );
        return Outcome.KEPT;
    }
}
