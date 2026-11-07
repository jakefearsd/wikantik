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
import java.util.Optional;
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
     * May change status, pageName, basePageVersion, baseBody, baseMeta, hasUnpublished, pendingPublishVersion,
     * pendingPublishBaseVersion and lastEditAt. Never changes id, headVersion, metaSeq, createdBy or createdAt: the
     * stored values are kept.
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

    /** Retained meta ops with {@code seq > sinceSeq}, ascending; a gap is detected as for {@link #updatesSince}. */
    List< StoredMetaOp > metaOpsSince( UUID id, long sinceSeq );

    /** Writing at an existing version replaces that snapshot. */
    void writeSnapshot( UUID id, Snapshot s );

    /** The snapshot with the highest version. */
    Optional< Snapshot > latestSnapshot( UUID id );

    /**
     * Deletes updates with {@code version < version} and snapshots older than the newest snapshot at or below
     * {@code version} (at least one snapshot covering the retained log is kept). Never changes headVersion (A10).
     */
    void truncateUpdatesBelow( UUID id, int version );

    /** Removes the session and its updates, meta ops and snapshots, and frees the page name for createSession. */
    void deleteSession( UUID id );
}
