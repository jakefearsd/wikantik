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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.user.UserDatabase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves and caches the per-user "must change password" state for a request.
 *
 * The flag lives on the user profile ({@code users.password_must_change});
 * lookups are cached on the HttpSession so the enforcement filter does not hit
 * the database per request. The cache is written on login, refreshed lazily on
 * first gated request, and cleared when the user changes their own password.
 * Consequence (accepted trade-off): an admin flagging a user with a live
 * session takes effect at that user's next login, not mid-session.
 */
public final class PasswordChangeGate {

    /** HttpSession attribute caching the Boolean flag for the session's user. */
    public static final String SESSION_ATTRIBUTE = "wikantik.passwordMustChange";

    /** Structured error code emitted by the enforcement filter. */
    public static final String ERROR_CODE = "PASSWORD_CHANGE_REQUIRED";

    private static final Logger LOG = LogManager.getLogger( PasswordChangeGate.class );

    private PasswordChangeGate() {
    }

    /**
     * Returns whether the named user must change their password, consulting the
     * HttpSession cache first and falling back to a profile lookup (which is
     * then cached when a session exists).
     */
    public static boolean mustChangePassword( final Engine engine, final HttpServletRequest request,
                                              final String loginName ) {
        final HttpSession httpSession = request.getSession( false );
        if ( httpSession != null ) {
            final Object cached = httpSession.getAttribute( SESSION_ATTRIBUTE );
            if ( cached instanceof Boolean flag ) {
                return flag;
            }
        }
        boolean flag = false;
        try {
            final UserDatabase db = AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase();
            flag = db.findByLoginName( loginName ).isPasswordMustChange();
        } catch ( final NoSuchPrincipalException e ) {
            // Authenticated principal without a local profile (e.g. container-managed
            // identity) — nothing to flag, but worth a trace if it happens unexpectedly.
            LOG.warn( "No profile for authenticated principal '{}' while checking password gate: {}",
                    loginName, e.getMessage() );
        }
        cache( request, flag );
        return flag;
    }

    /** Caches the flag on the HttpSession when one exists. Package-private: only
     *  the auth wiring in this package may write the cache. */
    static void cache( final HttpServletRequest request, final boolean flag ) {
        final HttpSession httpSession = request.getSession( false );
        if ( httpSession != null ) {
            httpSession.setAttribute( SESSION_ATTRIBUTE, flag );
        }
    }
}
