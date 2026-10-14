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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The one place that reacts to a user profile having been written outside the user's own session. Every path that writes
 * a profile through {@code UserDatabase#save} directly — the self-service and admin REST endpoints, SCIM and SSO
 * provisioning — calls {@link #profileSaved} afterwards (the {@code PROFILE_SAVE} events only exist for the legacy
 * {@code UserManager#setUserProfile} path).
 *
 * <p>The reactions are best-effort: the profile is already stored, so a failure here is logged at WARN and never fails the
 * request that made the change.</p>
 */
public final class ProfileChangeNotifier {

    private static final Logger LOG = LogManager.getLogger( ProfileChangeNotifier.class );

    private ProfileChangeNotifier() { }

    /**
     * Call after any write that may have changed a profile's display (full) or wiki name: refreshes the live sessions of
     * {@code loginName} so the new name applies immediately.
     *
     * @param engine    the wiki engine; {@code null} is ignored
     * @param loginName the account whose profile was saved; {@code null}/blank is ignored
     */
    public static void profileSaved( final Engine engine, final String loginName ) {
        if ( engine == null || loginName == null || loginName.isBlank() ) {
            return;
        }
        refreshLiveSessions( engine, loginName );
    }

    private static void refreshLiveSessions( final Engine engine, final String loginName ) {
        try {
            final int refreshed = SessionMonitor.getInstance( engine ).refreshProfile( loginName );
            LOG.debug( "Refreshed {} live session(s) after a profile change for '{}'", refreshed, loginName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not refresh the live sessions of '{}' after a profile change; they pick up the new name at next "
                    + "login: {}", loginName, e.getMessage(), e );
        }
    }
}
