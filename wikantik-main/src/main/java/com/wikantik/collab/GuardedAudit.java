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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * A {@link CollabAudit} that never throws: a delegate's {@link RuntimeException} is logged at WARN and the edit goes on.
 * The audit calls run among a request's completions, which also answer parked pulls, so one escaping exception would
 * leave pulls unanswered.
 */
final class GuardedAudit implements CollabAudit {

    private static final Logger LOG = LogManager.getLogger( GuardedAudit.class );

    private final CollabAudit delegate;

    private GuardedAudit( final CollabAudit delegate ) {
        this.delegate = delegate;
    }

    /** {@code audit} behind a guard; {@link CollabAudit#NONE} and an already guarded audit are returned as they are. */
    static CollabAudit of( final CollabAudit audit ) {
        return audit == NONE || audit instanceof GuardedAudit ? audit : new GuardedAudit( audit );
    }

    private static void guarded( final String event, final UUID session, final Runnable call ) {
        try {
            call.run();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the {} audit entry was not recorded: {}", session, event, e.toString() );
        }
    }

    @Override
    public void sessionOpened( final UUID session, final String page, final String authorKey ) {
        guarded( "session-open", session, () -> delegate.sessionOpened( session, page, authorKey ) );
    }

    @Override
    public void joined( final UUID session, final String page, final String authorKey, final String participantId ) {
        guarded( "join", session, () -> delegate.joined( session, page, authorKey, participantId ) );
    }

    @Override
    public void published( final UUID session, final String page, final String publisherKey, final int pageVersion,
                           final int collabVersion, final Set< String > contributorKeys ) {
        guarded( "publish", session,
            () -> delegate.published( session, page, publisherKey, pageVersion, collabVersion, contributorKeys ) );
    }

    @Override
    public void discarded( final UUID session, final String page, final String authorKey ) {
        guarded( "discard", session, () -> delegate.discarded( session, page, authorKey ) );
    }

    @Override
    public void evicted( final UUID session, final String page, final String authorKey, final String reason ) {
        guarded( "evict", session, () -> delegate.evicted( session, page, authorKey, reason ) );
    }

    @Override
    public void purged( final UUID session, final String page, final String adminKey, final boolean expired ) {
        guarded( expired ? "expire" : "purge", session, () -> delegate.purged( session, page, adminKey, expired ) );
    }

    @Override
    public void orphaned( final UUID session, final String page ) {
        guarded( "orphaned", session, () -> delegate.orphaned( session, page ) );
    }
}
