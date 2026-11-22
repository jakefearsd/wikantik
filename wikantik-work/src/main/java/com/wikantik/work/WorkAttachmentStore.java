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
package com.wikantik.work;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Attachments of items. Every read takes the item AND the attachment id: an id that belongs to another item, or to a
 * removed attachment, answers empty. No visibility check lives here (see {@link WorkAttachmentsService}).
 */
public interface WorkAttachmentStore {

    /** The file to store: its cleaned name, the type decided from its bytes, the bytes and their SHA-256 (hex). */
    record NewAttachment( String filename, String contentType, byte[] content, String sha256 ) {
    }

    /**
     * Stores the file in one transaction: locks the item row ({@code FOR NO KEY UPDATE}, so concurrent uploads to one
     * item are serialised and cannot pass the cap; an agent other than the claim holder is fenced with
     * {@link ClaimFenceException}), checks the comment (field {@code comment} when it is not a live comment of this
     * item), checks the live total plus this file against {@code maxItemBytes} ({@link WorkConflictException#TOO_LARGE}),
     * inserts the row and the bytes, and writes one {@code attached} event.
     */
    default Attachment add( final Item item, final Long commentId, final NewAttachment file, final long maxItemBytes,
                            final Actor by ) throws SQLException {
        return addCapped( item, commentId, file, maxItemBytes, null, by );
    }

    /**
     * {@link #add} with one more limit: when {@code uploaderBytes} is not null, the files this same uploader, removed ones included, (a
     * person by login, an agent by id) has on the item, plus this one, may not pass it
     * ({@link WorkConflictException#REPORTER_FULL}); checked under the same item lock, after the item total.
     */
    Attachment addCapped( Item item, Long commentId, NewAttachment file, long maxItemBytes, Long uploaderBytes, Actor by )
        throws SQLException;

    /**
     * Passes the stored bytes of a live attachment of {@code itemId} to {@code sink} in pieces of at most
     * {@code chunkBytes}, each read on its own, so the heap held is one piece whatever the file's size. Returns false
     * (nothing passed) when it is not a live attachment of this item; a file removed (soft: the row is
     * stamped and the bytes are deleted) between pieces ends the read with an {@link SQLException}; one that is
     * removed before the first piece is read gives {@code false}.
     */
    boolean stream( long itemId, long attachmentId, int chunkBytes, ChunkSink sink ) throws SQLException, java.io.IOException;

    /** Receives the pieces of {@link #stream}. */
    @FunctionalInterface
    interface ChunkSink {
        void accept( byte[] buffer, int length ) throws java.io.IOException;
    }

    /** The live attachment {@code attachmentId} of item {@code itemId}. */
    Optional< Attachment > attachment( long itemId, long attachmentId ) throws SQLException;

    /** The live attachment with its bytes, only when it belongs to {@code itemId}. */
    Optional< AttachmentContent > content( long itemId, long attachmentId ) throws SQLException;

    /**
     * Marks the attachment removed, deletes its bytes and writes one {@code attachment_removed} event, in one
     * transaction (same lock and fence as {@link #add}); {@link WorkNotFoundException} when it is not a live
     * attachment of this item. The metadata row and the events are kept.
     */
    Attachment remove( Item item, long attachmentId, Actor by ) throws SQLException;

    /** The item's live attachments, oldest first. */
    List< Attachment > forItem( long itemId ) throws SQLException;
}
