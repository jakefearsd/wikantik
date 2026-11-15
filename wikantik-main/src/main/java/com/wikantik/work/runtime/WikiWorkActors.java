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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.work.Actor;

import java.security.Principal;

/** Turns a wiki {@link Session} into the work-management {@link Actor}. */
public final class WikiWorkActors {

    private WikiWorkActors() {
    }

    /**
     * The actor for an authenticated session: its login, the wiki groups it belongs to right now (resolved on every
     * call), and {@code admin} when it holds {@code AllPermission}.
     *
     * @return the actor, or {@code null} for an anonymous or asserted-only session
     */
    public static Actor actorFor( final Engine engine, final Session session ) {
        if ( session == null || !session.isAuthenticated() ) {
            return null;
        }
        final Principal principal = session.getLoginPrincipal();
        if ( principal == null || principal.getName() == null || principal.getName().isBlank() ) {
            return null;
        }
        final AuthSubsystem.Services auth = AuthSubsystemBridge.fromLegacyEngine( engine );
        final boolean admin = auth.authorization().isPermitted( session,
            new AllPermission( engine.getApplicationName() ) );
        return Actor.human( principal.getName(), WikiWorkGroups.groupsOf( auth.groups(), principal.getName() ), admin );
    }
}
