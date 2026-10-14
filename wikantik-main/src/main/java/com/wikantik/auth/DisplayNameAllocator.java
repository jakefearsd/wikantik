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
import com.wikantik.auth.ProfileNameRules.NameViolation;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Picks a display name that satisfies {@link DisplayNameRules} for SSO and SCIM provisioning, where a conflict must never fail
 * the sign-in. {@link ProfileNameRules#availableDisplayName} is the public face.
 */
final class DisplayNameAllocator {

    private static final Logger LOG = LogManager.getLogger( DisplayNameAllocator.class );

    /** Upper bound on numeric suffixes tried before falling back to a random placeholder. */
    private static final int MAX_SUFFIX = 1000;

    private DisplayNameAllocator() { }

    /** See {@link ProfileNameRules#availableDisplayName}. */
    static String allocate( final Engine engine, final UserDatabase db, final String login, final String preferred ) {
        final String base = preferred == null ? "" : preferred.trim();
        if ( !base.isEmpty() ) {
            final Optional< NameViolation > first = DisplayNameRules.violation( engine, db, login, base );
            if ( first.isEmpty() ) {
                return base;
            }
            if ( first.get().isConflict() ) {
                final String numbered = firstFreeNumbered( engine, db, login, base, 2 );
                if ( numbered != null ) {
                    return numbered;
                }
            }
        }
        final String placeholder = firstFreeNumbered( engine, db, login, "Member", 1 );
        if ( placeholder != null ) {
            return placeholder;
        }
        final String lastResort = "Member " + UUID.randomUUID().toString().substring( 0, 8 );
        LOG.warn( "No free numbered display name found for account '{}' after {} tries; using '{}'",
                login, MAX_SUFFIX, lastResort );
        return lastResort;
    }

    /** {@code stem + " " + n} for the lowest n &ge; {@code from} that passes the rules, the stem shortened to fit the limit. */
    private static String firstFreeNumbered( final Engine engine, final UserDatabase db, final String login,
            final String stem, final int from ) {
        for ( int n = from; n < from + MAX_SUFFIX; n++ ) {
            final String suffix = " " + n;
            final String head = stem.length() + suffix.length() > ProfileNameRules.DISPLAY_NAME_MAX
                    ? stem.substring( 0, ProfileNameRules.DISPLAY_NAME_MAX - suffix.length() ).stripTrailing()
                    : stem;
            final String candidate = head + suffix;
            if ( DisplayNameRules.violation( engine, db, login, candidate ).isEmpty() ) {
                return candidate;
            }
        }
        return null;
    }
}
