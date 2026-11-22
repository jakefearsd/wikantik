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

import com.wikantik.work.WorkScopes.ItemScope;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

/**
 * Files attached to an item, reached through {@link WorkFeatures#attachments()}. An attachment is reachable only
 * through its own item: every read takes the item key and the attachment id, and an id that belongs to another item,
 * another project or a removed attachment is the same "not found" as an unknown one. An item the caller cannot see
 * is "not found" for every call, so {@link #list} is also the cheap "may this caller see this item's attachments"
 * check. The stored type comes from the bytes ({@link FileKinds}), never from the client's claim; the file name is
 * data and never a path. Removal deletes the bytes and keeps the row and its events. A file on a comment that has been
 * removed is no longer reachable by anyone (listed, counted, downloaded or removed: the same "not found"); its row and
 * bytes stay.
 *
 * <p>A person who only reports (Part 8) reaches the attachments of the items they filed, and of no other item, through
 * {@link WorkScopes#ownItemScope}: they list and download every live file on their item (members' too, as part of its
 * discussion), upload within the same limits while the project is not archived, and remove only what they uploaded.
 * Their scope already proved the item is theirs, so the policy (which gives a reporter nothing) is not asked for them;
 * for everyone else every check is as before.
 */
public final class WorkAttachmentsService {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkSettings settings;

    WorkAttachmentsService( final WorkStore store, final WorkScopes scopes, final WorkSettings settings ) {
        this.store = store;
        this.scopes = scopes;
        this.settings = settings;
    }

    /**
     * Attaches the file to the item (and to one of its comments when {@code commentId} is given). The per-file limit
     * is checked here; the per-item limit in the store, under the item's row lock.
     */
    public Attachment upload( final Actor a, final String itemKey, final Long commentId, final String filename,
                              final byte[] content ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireAttach( is );
        is.scope().requireWritable();
        final String name = FileKinds.safeName( filename );
        if ( name.isEmpty() ) {
            throw new WorkValidationException( "file", "Attach a file with a name." );
        }
        if ( content == null || content.length == 0 ) {
            throw new WorkValidationException( "file", "The file is empty." );
        }
        if ( content.length > settings.maxAttachmentBytes() ) {
            throw new WorkConflictException( "This file is larger than the limit of " + settings.maxAttachmentBytes()
                + " bytes.", WorkConflictException.TOO_LARGE );
        }
        final WorkAttachmentStore.NewAttachment file = new WorkAttachmentStore.NewAttachment( name,
            FileKinds.detect( content ).orElse( FileKinds.OCTET_STREAM ), content, sha256( content ) );
        final Long own = is.scope().reporterOnly() ? Long.valueOf( settings.reporterItemAttachmentBytes() ) : null;
        return fenced( () -> store.notifyStores().attachments().addCapped( is.item(), commentId, file,
            settings.maxItemAttachmentBytes(), own, a ) );
    }

    /**
     * Whether this caller may attach to the item, without attaching: {@code true}, or the refusal {@link #upload}
     * would give at the same point (not found for an item the caller cannot see, denied for a viewer or an agent the
     * policy refuses, archived). Read-only; the REST layer asks it before it reads a request body.
     */
    public boolean mayAttach( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireAttach( is );
        is.scope().requireWritable();
        return true;
    }

    /** The item's live attachments, oldest first. */
    public List< Attachment > list( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireSee( is );
        return store.notifyStores().attachments().forItem( is.item().id() );
    }

    /**
     * The item's live attachments with the limits and whether this caller may attach, in one read. A caller who can
     * see the item but may not attach (a viewer, an agent the policy refuses, an archived project) gets
     * {@code mayAttach=false}; one who cannot see it gets the same "not found" as {@link #list}.
     */
    public AttachmentListing listing( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireSee( is );
        final List< Attachment > live = store.notifyStores().attachments().forItem( is.item().id() );
        final boolean may = is.scope().project().archivedAt() == null
            && ( is.scope().reporterOnly() || WorkPolicy.may( a, is.scope().role(), Action.ATTACH, is.item() ).allowed() );
        return new AttachmentListing( live, may, settings.maxAttachmentBytes(), settings.maxItemAttachmentBytes(),
            live.stream().mapToLong( Attachment::sizeBytes ).sum() );
    }

    /** One attachment with its bytes; one that is not a live attachment of this item is "not found". */
    public AttachmentContent content( final Actor a, final String itemKey, final long attachmentId )
            throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireSee( is );
        return store.notifyStores().attachments().content( is.item().id(), attachmentId )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
    }

    /** How much of a file is read, and held in heap, at a time when it is downloaded. */
    static final int CHUNK_BYTES = 256 * 1024;

    /** What a download is handed: the file's details first, then its bytes in pieces. */
    public interface Download {
        /** Called once, before any bytes, with the attachment being sent. */
        void begin( Attachment attachment ) throws java.io.IOException;

        /** Called for each piece, in order. */
        void chunk( byte[] buffer, int length ) throws java.io.IOException;
    }

    /**
     * Streams one attachment to {@code out}: the same visibility and "not found" as {@link #content}, but the bytes are
     * read {@value #CHUNK_BYTES} at a time so a large file is never held whole in heap. {@code begin} runs once before
     * the first piece, while nothing has been sent.
     */
    public void download( final Actor a, final String itemKey, final long attachmentId, final Download out )
            throws SQLException, java.io.IOException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireSee( is );
        final WorkAttachmentStore attachments = store.notifyStores().attachments();
        final Attachment att = attachments.attachment( is.item().id(), attachmentId )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        out.begin( att );
        if ( !attachments.stream( is.item().id(), attachmentId, CHUNK_BYTES, out::chunk ) ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
    }

    /**
     * Removes the attachment: its uploader or a project lead (a wiki admin), never another member; a person who only
     * reports removes only what they uploaded.
     */
    public Attachment remove( final Actor a, final String itemKey, final long attachmentId ) throws SQLException {
        final ItemScope is = scopes.ownItemScope( a, itemKey );
        requireAttach( is );
        is.scope().requireWritable();
        final Attachment att = store.notifyStores().attachments().attachment( is.item().id(), attachmentId )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        if ( is.scope().reporterOnly() ) {
            if ( !uploader( a, att ) ) {
                scopes.denied( Action.ATTACH );
                throw new WorkDeniedException( "Only the uploader may remove this file." );
            }
        } else if ( !WorkPolicy.may( a, is.scope().role(), Action.MANAGE_PROJECT, is.item() ).allowed() && !uploader( a, att ) ) {
            scopes.denied( Action.ATTACH );
            throw new WorkDeniedException( "Only the uploader or a project lead may remove an attachment." );
        }
        return fenced( () -> store.notifyStores().attachments().remove( is.item(), attachmentId, a ) );
    }

    /** VIEW, except for a person who only reports, whose scope already proved the item is theirs. */
    private static void requireSee( final ItemScope is ) {
        if ( !is.scope().reporterOnly() ) {
            is.scope().require( Action.VIEW, is.item() );
        }
    }

    /** ATTACH, except for a person who only reports, whose scope already proved the item is theirs. */
    private static void requireAttach( final ItemScope is ) {
        if ( !is.scope().reporterOnly() ) {
            is.scope().require( Action.ATTACH, is.item() );
        }
    }

    /** The per-file limit in bytes; takes no actor (the REST layer caps its read with it). */
    public long maxFileBytes() {
        return settings.maxAttachmentBytes();
    }

    /** An agent uploaded by id, a person by login with no agent id (as comments name their author). */
    private static boolean uploader( final Actor a, final Attachment att ) {
        return a.kind() == Actor.Kind.AGENT
            ? a.agentId() != null && a.agentId().equals( att.uploadedByAgentId() )
            : att.uploadedByAgentId() == null && att.uploadedBy().equals( a.login() );
    }

    private static String sha256( final byte[] content ) {
        try {
            return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( content ) );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 is required of every Java platform", e );
        }
    }

    /** A store write of an attachment. */
    @FunctionalInterface
    private interface Write {
        Attachment run() throws SQLException;
    }

    /**
     * Runs the write; a claim by another agent that landed after this service read the item, and was found on the
     * locked row, is the same refusal as one seen on the read.
     */
    private Attachment fenced( final Write write ) throws SQLException {
        try {
            return write.run();
        } catch ( final ClaimFenceException e ) {
            scopes.denied( Action.ATTACH );
            throw new WorkDeniedException( e.getMessage() );
        }
    }
}
