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
package com.wikantik.api.identity;

import java.util.Optional;

/**
 * Turns the principals Wikantik <em>stores</em> (logins, legacy full names, agent labels) into the names it may <em>show</em>.
 *
 * <p>Storage keys are logins — page-version authors, comment authors, mention keys, owners. A login is an email address for
 * SSO users, so no non-admin surface may show a raw stored principal; it resolves through this service instead. A rename
 * therefore applies everywhere, including old history. Admin surfaces ({@code /admin/*}, the admin MCP server) are exempt
 * and keep showing logins.</p>
 *
 * <p>Implementations never throw to callers and never return {@code null}. Obtain the service from
 * {@code AuthSubsystem.Services#publicIdentity()} (REST: {@code getSubsystems().auth().publicIdentity()}); other code uses
 * {@code AuthSubsystemBridge.fromLegacyEngine(engine).publicIdentity()} or, when it must not see {@code null}, the
 * {@code PublicIdentities.forEngine(engine)} accessor in wikantik-main.</p>
 */
public interface PublicIdentity {

    /** Shown when a stored principal is null or blank, or cannot be resolved because the user database failed. */
    String UNKNOWN = "unknown";

    /** Shown in place of an email-shaped value (or an account that has no display name) so a login can never leak. */
    String UNKNOWN_CONTRIBUTOR = "unknown contributor";

    /**
     * Stored principal (a login, a legacy full name, an agent label) → the name shown to non-admins.
     * A login → that account's current display name (full name); any other value unchanged, except an email-shaped value
     * (contains {@code '@'}) → {@link #UNKNOWN_CONTRIBUTOR}; null/blank → {@link #UNKNOWN}. Never null, never throws.
     */
    String displayName( String storedPrincipal );

    /** The opaque {@code users.uid} of the account with this login, if any (used by mention tokens and the picker). */
    Optional< String > userIdForLogin( String login );

    /** The login of the account with this {@code users.uid}, if any. */
    Optional< String > loginForUserId( String uid );

    /** The login of the account whose display name is this, comparing case-insensitively and trimmed; empty if none or ambiguous. */
    Optional< String > loginForDisplayName( String displayName );

    /** Drops cached entries for {@code login}; {@code null} drops everything. */
    void invalidate( String login );
}
