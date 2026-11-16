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
import com.wikantik.auth.apikeys.AgentTokenAuth;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkService;

import java.security.Principal;
import java.sql.SQLException;

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

    /**
     * The actor for a verified personal API key: the key's owner as a person, never a wiki admin. Only a key whose
     * scope covers {@code WORK} is an actor at all. An API key is never an agent: agents carry their own token
     * ({@link #actorForAgent}).
     *
     * @return the actor, or {@code null} when the key is not a work key
     */
    public static Actor actorForKey( final WorkService service, final ApiKeyService.Record record ) {
        if ( !record.scope().matches( ApiKeyService.Scope.WORK ) ) {
            return null;
        }
        return service.registry().actorForApiKey( record.id(), record.principalLogin() );
    }

    /**
     * The verifier the access filters use for a work agent's own token: the work module's credential store decides.
     * A store failure becomes {@link ApiKeyService.LookupFailedException}, which the filters answer with 503.
     */
    public static AgentTokenAuth.Verifier agentVerifier( final WorkService service ) {
        return token -> {
            try {
                return service.registry().agentForToken( token )
                    .map( login -> new AgentTokenAuth.AgentRef( login.credentialId(), login.agentId() ) );
            } catch ( final SQLException e ) {
                throw new ApiKeyService.LookupFailedException( e );
            }
        };
    }

    /**
     * The actor for a verified agent token, resolved for this request with its sponsor as they are now.
     *
     * @return the actor, or {@code null} when the agent is retired, has no sponsor, or its sponsor's account is gone
     */
    public static Actor actorForAgent( final WorkService service, final AgentTokenAuth.AgentRef agent )
            throws SQLException {
        return service.registry().actorForAgent( agent.agentId() );
    }
}
