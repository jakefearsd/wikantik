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
package com.wikantik.collab.testing;

import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.model.StoredUpdate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A read-only snapshot of a session's state for {@link CollabInvariants}. {@code oversize} is the session's oversize
 * state flag (section 7.4) and {@code oversizeCeiling} the byte size it must not exceed while in that state (it ratchets
 * down as the doc shrinks); the two pending flags explain a legitimate gap in invariant 4. {@code poisoned} marks a
 * stub that hit a store fault after a page save: its state is unsupported and invariants 2 to 4 are skipped.
 *
 * <p>Phase 2: {@code selections} are the server-held selections of the live participants (invariant 8);
 * {@code editGrants} maps every live participant to the result of its last {@code edit} check, true when it was never
 * re-checked since its join passed one (invariant 7); {@code meta}/{@code metaSeq} are the session's metadata; and
 * {@code frozen} is true while the draft's page is deleted (invariant 4 then wants no page). A server without them (the
 * stub) reports them empty.</p>
 */
public record InvariantView( UUID sessionId, String doc, int head, String baseBody, ChangeSet local, int basePageVersion,
                             List< StoredUpdate > ring, boolean oversize, long maxPageBytes, CollabStore store,
                             CollabPageWriter writer, String pageName, boolean externalChangePending,
                             boolean publishPending, long oversizeCeiling, boolean poisoned,
                             Map< String, Selection > selections, Map< String, Boolean > editGrants,
                             Map< String, Object > meta, long metaSeq, boolean frozen ) {

    public InvariantView {
        selections = selections == null ? Map.of() : selections;
        editGrants = editGrants == null ? Map.of() : editGrants;
        meta = meta == null ? Map.of() : meta;
    }

    /** A Phase 0/1 view: no presence, grants or metadata, never frozen. */
    public InvariantView( final UUID sessionId, final String doc, final int head, final String baseBody,
                          final ChangeSet local, final int basePageVersion, final List< StoredUpdate > ring,
                          final boolean oversize, final long maxPageBytes, final CollabStore store,
                          final CollabPageWriter writer, final String pageName, final boolean externalChangePending,
                          final boolean publishPending, final long oversizeCeiling, final boolean poisoned ) {
        this( sessionId, doc, head, baseBody, local, basePageVersion, ring, oversize, maxPageBytes, store, writer, pageName,
            externalChangePending, publishPending, oversizeCeiling, poisoned, Map.of(), Map.of(), Map.of(), 0, false );
    }

    /** This view with {@code externalChangePending} set (the scheduler's own flag, invariant 4). */
    public InvariantView withExternalChangePending() {
        return new InvariantView( sessionId, doc, head, baseBody, local, basePageVersion, ring, oversize, maxPageBytes, store,
            writer, pageName, true, publishPending, oversizeCeiling, poisoned, selections, editGrants, meta, metaSeq,
            frozen );
    }
}
