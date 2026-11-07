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

import java.util.Set;
import java.util.UUID;

/**
 * Port recording collaborative editing's audit entries (docs/MultiEdit.MD section 9): session open, join, publish (with
 * the credited contributors' author keys), discard, eviction, an administrator's purge, retention's expiry and an
 * orphaned draft (plan decision 7). Keystrokes are never audited. Keys are author keys (logins): the audit log is an
 * administrator surface.
 *
 * <p>The core calls these after the session lock is released, through a guard ({@link GuardedAudit}), so a failing
 * audit never fails an edit. Every method defaults to doing nothing.</p>
 */
public interface CollabAudit {

    /** Records nothing. */
    CollabAudit NONE = new CollabAudit() {
    };

    /** A session was created for {@code page} by its first joiner (a reload from the store is not an open). */
    default void sessionOpened( final UUID session, final String page, final String authorKey ) {
    }

    /** {@code authorKey} joined as {@code participantId}. */
    default void joined( final UUID session, final String page, final String authorKey, final String participantId ) {
    }

    /**
     * {@code publisherKey} published collab version {@code collabVersion} as page version {@code pageVersion}, crediting
     * {@code contributorKeys}; also when the pending-publish rule finished it (E39).
     */
    default void published( final UUID session, final String page, final String publisherKey, final int pageVersion,
                            final int collabVersion, final Set< String > contributorKeys ) {
    }

    /** {@code authorKey} discarded the draft. */
    default void discarded( final UUID session, final String page, final String authorKey ) {
    }

    /** {@code authorKey}'s participant was evicted for {@code reason} (lost-edit, signed-out, account-deleted, purged). */
    default void evicted( final UUID session, final String page, final String authorKey, final String reason ) {
    }

    /**
     * The draft was removed without publishing: by administrator {@code adminKey}, or by draft retention when
     * {@code expired} (then {@code adminKey} is null).
     */
    default void purged( final UUID session, final String page, final String adminKey, final boolean expired ) {
    }

    /** A publish left no participant with {@code edit} while unpublished changes remain (plan decision 7). */
    default void orphaned( final UUID session, final String page ) {
    }
}
