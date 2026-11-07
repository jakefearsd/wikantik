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

/** Port to durable session state (the collab tables of docs/MultiEdit.MD section 10). */
public interface CollabStore {

    /** @throws IllegalStateException if the page already has a session */
    void createSession( SessionRecord s );

    Optional< SessionRecord > session( UUID id );

    Optional< SessionRecord > sessionForPage( String pageName );

    /** Updates base, status, page name, pending marker and last edit; never {@code headVersion} or {@code metaSeq}. */
    void updateSession( SessionRecord s );

    /**
     * Atomically appends updates; the first version must be {@code headVersion + 1} and versions must be contiguous.
     * Bumps {@code headVersion}.
     *
     * @throws IllegalStateException on a gap or overlap
     */
    void appendUpdates( UUID id, List< StoredUpdate > updates );

    /** Updates with {@code version > sinceVersion}, ascending. */
    List< StoredUpdate > updatesSince( UUID id, int sinceVersion );

    /**
     * Appends meta ops under the same contiguity rule, applied to {@code metaSeq}.
     *
     * @throws IllegalStateException on a gap or overlap
     */
    void appendMetaOps( UUID id, List< StoredMetaOp > ops );

    /** Meta ops with {@code seq > sinceSeq}, ascending. */
    List< StoredMetaOp > metaOpsSince( UUID id, long sinceSeq );

    void writeSnapshot( UUID id, Snapshot s );

    Optional< Snapshot > latestSnapshot( UUID id );

    /** Drops updates with {@code version < version} (A10). */
    void truncateUpdatesBelow( UUID id, int version );

    void deleteSession( UUID id );
}
