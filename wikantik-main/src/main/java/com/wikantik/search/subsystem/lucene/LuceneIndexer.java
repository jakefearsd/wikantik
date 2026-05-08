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

import com.wikantik.api.core.Page;
import com.wikantik.api.core.Attachment;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;

import java.io.IOException;
import java.util.Set;

/**
 * Write-side seam for {@code LuceneSearchProvider}.
 *
 * <p>Owns the reindex queue, the background drain thread, per-page indexing,
 * attachment content helpers, and index-clearing operations.</p>
 */
public interface LuceneIndexer {

    /**
     * Adds a page to the reindex queue for background processing.
     *
     * @param page the page to reindex; ignored if {@code null}
     */
    void reindexPage( Page page );

    /**
     * Removes the given page from the Lucene index immediately (synchronous).
     *
     * @param page the page to remove
     */
    void pageRemoved( Page page );

    /**
     * Clears all documents from the Lucene index (synchronous).
     * Does NOT delete the index directory.
     */
    void clearIndex();

    /**
     * @return number of pages currently queued for background reindexing
     */
    int getReindexQueueDepth();

    /**
     * Performs a full reindex or a missing-pages check against the existing index.
     * Called by the background updater thread on startup.
     *
     * @throws IOException if the index cannot be written
     */
    void doFullLuceneReindex() throws IOException;

    /**
     * Indexes pages on disk that are absent from the Lucene index.
     *
     * @return number of pages indexed
     */
    int indexMissingPages();

    /**
     * Returns the set of page names currently recorded in the Lucene index.
     * Used for missing-page detection.
     *
     * @return set of page names, or empty set if the index cannot be read
     */
    Set<String> getIndexedPageNames();

    /**
     * Updates the Lucene index for a single page (synchronous, serialised).
     *
     * @param page the page to index
     * @param text the raw text content to index
     * @return {@code true} if the page was successfully indexed
     */
    boolean updateLuceneIndex( Page page, String text );

    /**
     * Indexes a single {@link Page} document into the given writer.
     *
     * @param page   the page (or attachment) to index
     * @param text   the content to index
     * @param writer the {@link IndexWriter} to write into
     * @return the created Lucene {@link Document}
     * @throws IOException if the document cannot be written
     */
    Document luceneIndexPage( Page page, String text, IndexWriter writer ) throws IOException;

    /**
     * Returns the text content of an attachment by name + version.
     *
     * @param attachmentName attachment name
     * @param version        attachment version
     * @return content string, or {@code null} on error
     */
    String getAttachmentContent( String attachmentName, int version );

    /**
     * Returns the text content of an attachment.
     *
     * @param att the attachment
     * @return content string (possibly just the filename for unsupported types)
     */
    String getAttachmentContent( Attachment att );

    /**
     * Returns {@code true} if the page name refers to a system page that
     * should be excluded from the index.
     *
     * @param pageName the wiki page name
     * @return {@code true} if the page is a system page
     */
    boolean isSystemPageExcluded( String pageName );

    /**
     * Returns the current number of live (non-deleted) documents in the
     * Lucene index, or {@code 0} if the index directory is empty or cannot
     * be opened.
     *
     * @return live document count, or {@code 0} on error/empty index
     */
    int documentCount();

    /**
     * Stats returned from a single drain of the reindex queue.
     *
     * @param totalQueued total items dequeued (indexed + skipped + failed)
     * @param indexed     items successfully written to the Lucene index
     * @param skipped     items skipped because they are system pages
     * @param failed      items where indexing returned false
     */
    record DrainStats( int totalQueued, int indexed, int skipped, int failed ) {}

    /**
     * Drains the pending reindex queue, writing each queued page to the index.
     *
     * @return stats describing what the drain did
     */
    DrainStats drainUpdateQueue();
}
