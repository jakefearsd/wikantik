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
package com.wikantik.collab.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable state of one page's collaboration session. {@code status} is {@code "active"} or {@code "frozen"}.
 *
 * <p>{@code baseContentHash} is the content hash of the page version the session is based on, on the ruled basis
 * {@link com.wikantik.collab.text.CollabText#contentHash} (SHA-256 hex of the LF-normalised body; meta is compared
 * separately). The pending-publish triple ({@code pendingPublishVersion}, {@code pendingPublishBaseVersion},
 * {@code pendingPublishAuthor}) is all {@code null} when no publish is pending and all set while one is.
 * {@code lastEditAt} is {@code null} until the first edit.</p>
 *
 * <p>{@code pendingPublishSavedVersion} is the page version the pending publish's save wrote, stored as soon as the save
 * returned (or reported it with its failure), and {@code pendingPublishCanonicalId} the {@code canonical_id} it was
 * saved under (null when it had none, or before the post-save half began); both {@code null} while the save has not
 * returned. Every later claim of the publish matches that version and the marked author, never S_V's content hash, which
 * a save filter's rewrite breaks; and an unclaimed publish's early ledger rows are found by them. Only set while a
 * publish is pending; the canonical id only with the version.</p>
 */
public record SessionRecord( UUID id, String pageName, String status, int basePageVersion, String baseBody,
                             Map< String, Object > baseMeta, String baseContentHash, int headVersion, long metaSeq,
                             boolean hasUnpublished, Integer pendingPublishVersion, Integer pendingPublishBaseVersion,
                             String pendingPublishAuthor, Integer pendingPublishSavedVersion,
                             String pendingPublishCanonicalId, String createdBy, Instant createdAt, Instant lastEditAt ) {

    public static final String ACTIVE = "active";
    public static final String FROZEN = "frozen";

    public SessionRecord {
        Objects.requireNonNull( id, "id" );
        ModelChecks.nonBlank( pageName, "pageName" );
        Objects.requireNonNull( status, "status" );
        if( !ACTIVE.equals( status ) && !FROZEN.equals( status ) ) {
            throw new IllegalArgumentException( "status must be active or frozen: " + status );
        }
        ModelChecks.nonNegative( basePageVersion, "basePageVersion" );
        Objects.requireNonNull( baseBody, "baseBody" );
        baseMeta = ModelChecks.copyMeta( baseMeta, "baseMeta" );
        Objects.requireNonNull( baseContentHash, "baseContentHash" );
        ModelChecks.nonNegative( headVersion, "headVersion" );
        ModelChecks.nonNegative( metaSeq, "metaSeq" );
        if( ( pendingPublishVersion == null ) != ( pendingPublishBaseVersion == null )
                || ( pendingPublishVersion == null ) != ( pendingPublishAuthor == null ) ) {
            throw new IllegalArgumentException( "pendingPublishVersion, pendingPublishBaseVersion and pendingPublishAuthor"
                + " must be all null or all set" );
        }
        if( pendingPublishVersion != null ) {
            ModelChecks.nonNegative( pendingPublishVersion, "pendingPublishVersion" );
            ModelChecks.nonNegative( pendingPublishBaseVersion, "pendingPublishBaseVersion" );
            ModelChecks.nonBlank( pendingPublishAuthor, "pendingPublishAuthor" );
        }
        if( pendingPublishSavedVersion != null ) {
            if( pendingPublishVersion == null ) {
                throw new IllegalArgumentException( "pendingPublishSavedVersion needs a pending publish" );
            }
            ModelChecks.nonNegative( pendingPublishSavedVersion, "pendingPublishSavedVersion" );
        }
        if( pendingPublishCanonicalId != null && pendingPublishSavedVersion == null ) {
            throw new IllegalArgumentException( "pendingPublishCanonicalId needs pendingPublishSavedVersion" );
        }
        ModelChecks.nonBlank( createdBy, "createdBy" );
        Objects.requireNonNull( createdAt, "createdAt" );
    }

    public SessionRecord withHead( final int newHeadVersion ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, baseContentHash,
            newHeadVersion, metaSeq, hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion,
            pendingPublishAuthor, pendingPublishSavedVersion, pendingPublishCanonicalId, createdBy, createdAt,
            lastEditAt );
    }

    public SessionRecord withMetaSeq( final long newMetaSeq ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, baseContentHash,
            headVersion, newMetaSeq, hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion,
            pendingPublishAuthor, pendingPublishSavedVersion, pendingPublishCanonicalId, createdBy, createdAt,
            lastEditAt );
    }

    /**
     * Sets (all three non-null) or clears (all three null) the pending-publish triple; either way a new marker has no
     * saved version yet.
     */
    public SessionRecord withPending( final Integer version, final Integer baseVersion, final String author ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, baseContentHash,
            headVersion, metaSeq, hasUnpublished, version, baseVersion, author, null, null, createdBy, createdAt,
            lastEditAt );
    }

    /** The pending publish's marker with the page version its save wrote and the canonical id it was saved under. */
    public SessionRecord withPublishSaved( final int savedVersion, final String canonicalId ) {
        return new SessionRecord( id, pageName, status, basePageVersion, baseBody, baseMeta, baseContentHash,
            headVersion, metaSeq, hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion, pendingPublishAuthor,
            savedVersion, canonicalId, createdBy, createdAt, lastEditAt );
    }

    public SessionRecord withBase( final int version, final String body, final Map< String, Object > meta,
                                   final String contentHash ) {
        return new SessionRecord( id, pageName, status, version, body, meta, contentHash, headVersion, metaSeq,
            hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion, pendingPublishAuthor,
            pendingPublishSavedVersion, pendingPublishCanonicalId, createdBy, createdAt, lastEditAt );
    }

    /** The record under another page name (a rename, section 7.4 / A1). */
    public SessionRecord withPageName( final String newPageName ) {
        return new SessionRecord( id, newPageName, status, basePageVersion, baseBody, baseMeta, baseContentHash,
            headVersion, metaSeq, hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion,
            pendingPublishAuthor, pendingPublishSavedVersion, pendingPublishCanonicalId, createdBy, createdAt,
            lastEditAt );
    }

    /** The record with another status ({@link #ACTIVE} or {@link #FROZEN}). */
    public SessionRecord withStatus( final String newStatus ) {
        return new SessionRecord( id, pageName, newStatus, basePageVersion, baseBody, baseMeta, baseContentHash,
            headVersion, metaSeq, hasUnpublished, pendingPublishVersion, pendingPublishBaseVersion,
            pendingPublishAuthor, pendingPublishSavedVersion, pendingPublishCanonicalId, createdBy, createdAt,
            lastEditAt );
    }
}
