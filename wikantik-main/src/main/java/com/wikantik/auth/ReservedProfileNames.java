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
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a user display name (full name or wiki name) is reserved because it equals the
 * name of a role or group. Display names are user-editable, so keeping them distinct from role and
 * group names avoids any confusion between a person and a permission-bearing principal.
 *
 * <p>A name is reserved when it — or its whitespace-stripped form, which is how a wiki name is
 * derived from a full name — equals, ignoring case, a built-in role, the {@code Admin} role, a
 * name carrying a {@code role}/{@code group} policy grant, an existing wiki group, or a role the
 * configured authorizer knows.</p>
 */
public final class ReservedProfileNames {

    private static final Logger LOG = LogManager.getLogger( ReservedProfileNames.class );

    /** Built-in roles plus the conventional administrator role, lower-cased. */
    private static final Set< String > FIXED = Set.of( "all", "anonymous", "asserted", "authenticated", "admin" );

    private ReservedProfileNames() { }

    /**
     * @param engine the wiki engine (for group, policy and authorizer lookups)
     * @param name   a proposed full name or wiki name; {@code null} or blank is never reserved
     * @return {@code true} if the name must not be used as a user display name
     */
    public static boolean isReserved( final Engine engine, final String name ) {
        if ( name == null || name.isBlank() ) {
            return false;
        }
        final String trimmed = name.trim();
        final String stripped = trimmed.replaceAll( "\\s+", "" );
        return matchesReserved( engine, trimmed ) || ( !stripped.equals( trimmed ) && matchesReserved( engine, stripped ) );
    }

    private static boolean matchesReserved( final Engine engine, final String candidate ) {
        if ( FIXED.contains( candidate.toLowerCase( Locale.ROOT ) ) ) {
            return true;
        }
        try {
            final AuthorizationManager authz = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
            if ( authz != null && AuthenticationManager.isRolePrincipal( authz.resolvePrincipal( candidate ) ) ) {
                return true;
            }
            if ( authz instanceof DefaultAuthorizationManager dam && dam.getDatabasePolicy() != null
                    && containsIgnoreCase( dam.getDatabasePolicy().roleAndGroupGrantNames(), candidate ) ) {
                return true;
            }
            final GroupManager groups = AuthSubsystemBridge.fromLegacyEngine( engine ).groups();
            if ( groups != null ) {
                for ( final Principal group : groups.getRoles() ) {
                    if ( group.getName().equalsIgnoreCase( candidate ) ) {
                        return true;
                    }
                }
            }
        } catch ( final RuntimeException e ) {
            // Lookups are best-effort hardening on top of type-aware policy matching; the fixed
            // reserved set above still applies when the auth subsystem cannot be consulted.
            LOG.warn( "Could not check display name '{}' against role and group names: {}", candidate, e.getMessage() );
        }
        return false;
    }

    private static boolean containsIgnoreCase( final Set< String > names, final String candidate ) {
        for ( final String n : names ) {
            if ( n.equalsIgnoreCase( candidate ) ) {
                return true;
            }
        }
        return false;
    }
}
