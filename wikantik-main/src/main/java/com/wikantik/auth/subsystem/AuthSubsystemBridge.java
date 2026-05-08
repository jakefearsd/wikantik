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
package com.wikantik.auth.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.authorize.GroupManager;

/**
 * Adapter that synthesises a sparse {@link AuthSubsystem.Services} record
 * from {@link Engine#getManager(Class)} lookups, mirroring
 * {@code KnowledgeSubsystemBridge} / {@code CoreSubsystemBridge}.
 *
 * <p>Used by non-servlet callers (filters, plugins, MCP / tools
 * initializers) and by test fixtures that build the engine via
 * {@code TestEngine.setManager(...)} rather than a full
 * {@code WikiEngine.initialize()} cycle. Production servlet code uses
 * the typed bundle stashed on the {@link jakarta.servlet.ServletContext}.</p>
 *
 * <p>Fields whose corresponding manager is not registered come back as
 * {@code null}, mirroring the legacy {@code getManager()} behavior.</p>
 */
public final class AuthSubsystemBridge {

    private AuthSubsystemBridge() {}

    public static AuthSubsystem.Services fromLegacyEngine( final Engine engine ) {
        if ( !( engine instanceof com.wikantik.WikiEngine wikiEngine ) ) {
            // Non-WikiEngine callers cannot reach getManager — return a fully-null record.
            return new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null );
        }
        final AuthSubsystem.Services typed = wikiEngine.getAuthSubsystem();
        if ( typed != null ) return typed;
        // Snapshot not yet built (mid-initialize path) — synthesise from registry.
        // Post-initialize paths (setManager hot-swaps) rebuild the snapshot directly,
        // so tests reaching this branch return a coherent record.
        return rebuildFromManagers( wikiEngine );
    }

    /**
     * Synthesises an {@link AuthSubsystem.Services} record directly from the
     * {@code WikiEngine}'s manager registry. Called by
     * {@link com.wikantik.WikiEngine#setManager} whenever an auth-layer manager
     * is hot-swapped (e.g. by a unit test installing a mock) so that the typed
     * snapshot stays coherent without requiring a full re-initialization cycle.
     */
    public static AuthSubsystem.Services rebuildFromManagers( final com.wikantik.WikiEngine engine ) {
        final AuthorizationManager authorization = engine.getManager( AuthorizationManager.class );
        Authorizer webAuthorizer = null;
        if ( authorization != null ) {
            try {
                webAuthorizer = authorization.getAuthorizer();
            } catch ( final WikiSecurityException ignored ) {
                // best-effort — leave webAuthorizer null
            }
        }

        final ApiKeyService apiKeys = engine.getWikiProperties() != null
            ? ApiKeyServiceHolder.get( engine.getWikiProperties() )
            : null;

        return new AuthSubsystem.Services(
            engine.getManager( AuthenticationManager.class ),
            authorization,
            engine.getManager( UserManager.class ),
            engine.getManager( GroupManager.class ),
            webAuthorizer,
            apiKeys,
            /* securityVerifier */ null,
            engine.getManager( AclManager.class )
        );
    }
}
