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
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.model.StoredUpdate;

import java.util.List;
import java.util.UUID;

/**
 * A read-only snapshot of a session's state for {@link CollabInvariants}. {@code oversize} is the session's oversize
 * state flag (section 7.4) and {@code oversizeCeiling} the byte size it must not exceed while in that state (it ratchets
 * down as the doc shrinks); the two pending flags explain a legitimate gap in invariant 4. {@code poisoned} marks a
 * stub that hit a store fault after a page save: its state is unsupported and invariants 2 to 4 are skipped.
 */
public record InvariantView( UUID sessionId, String doc, int head, String baseBody, ChangeSet local, int basePageVersion,
                             List< StoredUpdate > ring, boolean oversize, long maxPageBytes, CollabStore store,
                             CollabPageWriter writer, String pageName, boolean externalChangePending,
                             boolean publishPending, long oversizeCeiling, boolean poisoned ) {
}
