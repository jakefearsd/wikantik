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
package com.wikantik.collab;

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port to durable session state (the collab tables of docs/MultiEdit.MD section 10).
 *
 * <p>Contract common to all methods: mutators on an unknown session id throw
 * {@code IllegalArgumentException("no collab session " + id)}; readers return {@code Optional.empty()} or an empty
 * list. Frozen sessions count as sessions: {@link #sessionForPage} returns them and {@link #createSession} refuses a
 * second session for that page.</p>
 */
public interface CollabStore {

    /** @throws IllegalStateException if the page (frozen or active) already has a session */
    void createSession( SessionRecord s );

    Optional< SessionRecord > session( UUID id );

    Optional< SessionRecord > sessionForPage( String pageName );

    /**
     * May change status, pageName, basePageVersion, baseBody, baseMeta, baseContentHash, hasUnpublished, the
     * pending-publish triple (pendingPublishVersion, pendingPublishBaseVersion, pendingPublishAuthor) and lastEditAt.
     * Never changes id, headVersion, metaSeq, createdBy or createdAt: the stored values are kept. A base change
     * (basePageVersion, baseBody, baseMeta, baseContentHash) belongs in {@link #commitBaseChange}, which writes the
     * snapshot with it.
     *
     * @throws IllegalStateException if renaming to a page that already has a session
     */
    void updateSession( SessionRecord s );

    /**
     * An empty list is a no-op. Otherwise the first version must be {@code headVersion + 1} and the list contiguous,
     * else {@code IllegalStateException} with nothing stored; on success the stored headVersion advances by the list
     * size. The check-and-insert is one atomic step: of two concurrent appends at the same version exactly one
     * succeeds and the other throws, with no partial rows.
     */
    void appendUpdates( UUID id, List< StoredUpdate > updates );

    /**
     * The retained updates with {@code version > sinceVersion}, ascending. If truncation removed some of them the
     * result starts later than {@code sinceVersion + 1} and the caller detects the gap (the 410 case in A10).
     */
    List< StoredUpdate > updatesSince( UUID id, int sinceVersion );

    /** As {@link #appendUpdates}, applied to {@code seq} and {@code metaSeq}. */
    void appendMetaOps( UUID id, List< StoredMetaOp > ops );

    /**
     * A push's text updates and meta ops (A14) as one atomic step: {@code updates} follow the rules of
     * {@link #appendUpdates} and {@code ops} those of {@link #appendMetaOps}; if either list is not contiguous, or any
     * write fails, nothing is stored ({@code IllegalStateException} for a contiguity failure). Either list may be empty;
     * both empty is a no-op (on an unknown session it still throws {@code IllegalArgumentException}).
     */
    void appendBatch( UUID id, List< StoredUpdate > updates, List< StoredMetaOp > ops );

    /** Retained meta ops with {@code seq > sinceSeq}, ascending; a gap is detected as for {@link #updatesSince}. */
    List< StoredMetaOp > metaOpsSince( UUID id, long sinceSeq );

    /**
     * Commits a change of the session's base page version as one atomic step: appends {@code serverUpdates} (each of
     * kind {@link StoredUpdate#SERVER}; contiguity rules as {@link #appendUpdates}, an empty list appends nothing),
     * writes {@code snapshot}, and applies {@code updated} with the field rules of {@link #updateSession}. Used by
     * publish completion and external merges: every base change writes a snapshot in the same transaction, so a
     * reload never pairs a new base with an old snapshot.
     *
     * <p>{@code snapshot.version()} must equal the head after the append ({@code headVersion + serverUpdates.size()})
     * and {@code snapshot.basePageVersion()} must equal {@code updated.basePageVersion()}. All-or-nothing: on any
     * failure (a contiguity or snapshot mismatch, a rename clash, a storage fault) nothing is changed.</p>
     *
     * @throws IllegalArgumentException for an unknown session, a non-server update, an {@code updated} record for
     *                                  another session, or a snapshot base version that is not {@code updated}'s
     * @throws IllegalStateException    when the updates are not contiguous to the head, the snapshot is not at the new
     *                                  head, or {@code updated} renames onto a page that already has a session
     */
    void commitBaseChange( UUID id, List< StoredUpdate > serverUpdates, Snapshot snapshot, SessionRecord updated );

    /** Writing at an existing version replaces that snapshot. */
    void writeSnapshot( UUID id, Snapshot s );

    /** The snapshot with the highest version. */
    Optional< Snapshot > latestSnapshot( UUID id );

    /**
     * The snapshot with the highest version that is {@code <= version}, or empty when there is none (or no such
     * session). With the log after it, it rebuilds the document at {@code version}: E39 recovery rebuilds the published
     * text S_V this way, which A10's truncation keeps possible by never cutting above a pending publish's version.
     */
    Optional< Snapshot > snapshotAtOrBelow( UUID id, int version );

    /**
     * Truncation clamped to a snapshot. Let {@code S} be the newest snapshot whose version is {@code <= cut}: deletes
     * the updates with {@code version <= S.version()} (they are folded into {@code S}) and the snapshots older than
     * {@code S}; {@code S}, every newer snapshot and every update after {@code S} are kept, so snapshot {@code S} plus
     * the retained log always reloads the head. If no snapshot is at or below {@code cut} nothing is deleted. Never
     * changes headVersion (A10).
     */
    void truncateUpdatesBelow( UUID id, int cut );

    /**
     * Removes the session and its updates, meta ops and snapshots, and frees the page name for createSession. The
     * page-version contributor ledger ({@link #recordContributors}) is not session state and is kept.
     */
    void deleteSession( UUID id );

    /**
     * Every session, active and frozen, oldest edit first: ordered by {@code lastEditAt}, a session never edited by its
     * {@code createdAt} (ties by page name).
     */
    List< SessionRecord > sessions();

    // ---- page-version contributor ledger (page_version_contributor; docs/MultiEdit.MD section 7.3) -----------

    /**
     * Replaces the contributors credited to page version {@code pageVersion} of the page whose {@code canonical_id} is
     * {@code canonicalId}: deletes that version's rows and inserts one per author key. With {@code created} (the
     * publish created the page, so version numbers restart) every earlier row of {@code canonicalId} is deleted first.
     * One atomic step and idempotent; an empty {@code authorKeys} still performs the deletes. Unlike the session state
     * the ledger outlives the draft.
     *
     * @throws IllegalArgumentException for a null or blank {@code canonicalId}, a {@code pageVersion < 1}, or a null or
     *                                  blank author key
     */
    void recordContributors( String canonicalId, int pageVersion, Set< String > authorKeys, boolean created );

    /**
     * The ledger of {@code canonicalId}: page version (ascending) to its contributors' author keys, each list sorted
     * ({@link String#compareTo}). An empty map when there are none.
     */
    Map< Integer, List< String > > contributorsByVersion( String canonicalId );
}
