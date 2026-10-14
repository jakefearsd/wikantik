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
package com.wikantik.auth.identity;

import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;

import java.time.Clock;

/**
 * Keeps an engine's one {@link DefaultPublicIdentity}, so every {@code AuthSubsystem.Services} snapshot built for that engine
 * — including the throwaway ones {@code AuthSubsystemBridge} synthesises while the engine is still initialising, and the
 * rebuilds after a manager hot-swap — shares a single account snapshot instead of each loading its own.
 *
 * <p>The identity is bound to a user database: a manager over a different database gets a fresh identity (a snapshot of
 * another account table must not be reused), a manager over the same one reuses it. Every manager passed in is registered as
 * a source of profile events exactly once.</p>
 */
public final class PublicIdentityHolder {

    private DefaultPublicIdentity identity;
    private UserDatabase boundTo;
    private UserManager listeningTo;

    /** The identity for {@code users}' database, created on first use; also listens to {@code users}' profile events. */
    public synchronized DefaultPublicIdentity forUsers( final UserManager users ) {
        final UserDatabase db = users.getUserDatabase();
        if ( identity == null || db != boundTo ) {
            identity = new DefaultPublicIdentity( db, Clock.systemUTC() );
            boundTo = db;
            listeningTo = null;
        }
        if ( users != listeningTo ) {
            users.addWikiEventListener( identity );   // weakly held by the registry; this holder keeps it alive
            listeningTo = users;
        }
        return identity;
    }
}
