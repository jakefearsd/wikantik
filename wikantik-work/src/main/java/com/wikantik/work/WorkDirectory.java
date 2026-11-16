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
package com.wikantik.work;

import java.util.Optional;
import java.util.Set;

/**
 * The wiki's user database as the work module sees it; implemented by the host application. People are shown and
 * named by their public display name; a login is a storage key that only its owner and wiki admins ever see.
 */
public interface WorkDirectory {

    /** The groups the login belongs to; empty when none or unknown. */
    Set< String > groupsOf( String login );

    /** Whether a user with this login exists. Only ever asked on behalf of a wiki admin. */
    boolean exists( String login );

    /** An account as the sponsor rule needs it: its groups and whether it is a wiki admin, as they are now. */
    record Person( String login, Set< String > groups, boolean admin ) {
    }

    /**
     * The account behind a login: empty when there is no such account. A lookup that fails throws
     * {@link WorkUnavailableException}; it is never answered as "no such account" or as an account with no groups,
     * because the caller would then take away, or grant, an agent's access on a guess. The default composes
     * {@link #exists} and {@link #groupsOf} and knows no admins; the host overrides it.
     */
    default Optional< Person > person( final String login ) {
        if ( !exists( login ) ) {
            return Optional.empty();
        }
        final Set< String > groups = groupsOf( login );
        return Optional.of( new Person( login, groups == null ? Set.of() : groups, false ) );
    }

    /** The public display name for the login; a neutral placeholder, never the login, when it has none. */
    String displayName( String login );

    /** The login of the user with this display name (trimmed, case-insensitive); empty when none or ambiguous. */
    default Optional< String > loginForDisplayName( final String displayName ) {
        return Optional.empty();
    }

    /** The login of the user a mention token's opaque id names; empty when unknown. */
    default Optional< String > loginForMentionId( final String id ) {
        return Optional.empty();
    }

    /** The opaque id a mention token carries for the login; empty when the account has none. */
    default Optional< String > mentionId( final String login ) {
        return Optional.empty();
    }
}
