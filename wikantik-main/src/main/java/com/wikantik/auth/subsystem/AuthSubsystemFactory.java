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

import com.wikantik.WikiEngine;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import com.wikantik.auth.authorize.GroupManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Builds {@link AuthSubsystem.Services} from {@link AuthSubsystem.Deps}.
 *
 * <p>Phase 4 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>The four core auth managers are still constructed by
 * {@code WikiEngine.initialize} via {@code initComponent} — this factory
 * locates them on the engine's legacy registry. Once Phase 4 lands the
 * remaining auth migration, the engine-internal construction will move
 * here.</p>
 */
public final class AuthSubsystemFactory {

    private static final Logger LOG = LogManager.getLogger( AuthSubsystemFactory.class );

    private AuthSubsystemFactory() {}

    public static AuthSubsystem.Services create( final AuthSubsystem.Deps deps ) {
        Objects.requireNonNull( deps, "deps" );
        Objects.requireNonNull( deps.core(), "core" );
        // persistence is optional — engines that boot without a datasource
        // (test fixtures) still need the four auth managers wired up via the
        // legacy registry path, which doesn't touch PersistenceSubsystem.
        final WikiEngine engine = ( WikiEngine ) Objects.requireNonNull( deps.engine(), "engine" );

        final AuthenticationManager authentication = engine.getManager( AuthenticationManager.class );
        final AuthorizationManager  authorization  = engine.getManager( AuthorizationManager.class );
        final UserManager           users          = engine.getManager( UserManager.class );
        final GroupManager          groups         = engine.getManager( GroupManager.class );

        Authorizer webAuthorizer = null;
        if ( authorization != null ) {
            try {
                webAuthorizer = authorization.getAuthorizer();
            } catch ( final WikiSecurityException e ) {
                LOG.warn( "Authorizer not initialized: {}", e.getMessage() );
            }
        }

        final ApiKeyService apiKeys = ApiKeyServiceHolder.get(
            deps.core().properties().asProperties() );

        final AclManager aclManager = engine.getManager( AclManager.class );

        return new AuthSubsystem.Services(
            authentication,
            authorization,
            users,
            groups,
            webAuthorizer,
            apiKeys,
            /* securityVerifier — wired in Ckpt 3 */ null,
            aclManager
        );
    }
}
