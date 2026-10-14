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

import com.wikantik.api.core.Session;

import java.security.Principal;

/**
 * The storage key for "who did this": an account's <em>login</em>, not its display name.
 *
 * <p>{@link Session#getUserPrincipal()} is the profile's full name, which is user-editable and (before display names became
 * unique) not even distinct, so authorship stored from it could be reassigned by a rename and could not be told apart for two
 * people sharing a name. Every authorship writer — page saves, renames, attachments, group creator/modifier — stores this key
 * instead; {@code PublicIdentity#displayName} resolves it to the current display name when it is shown.</p>
 *
 * <p>Anonymous and asserted sessions have no login: their user principal (an IP address or the asserted name) is returned
 * unchanged, exactly as before.</p>
 */
public final class AuthorKeys {

    private AuthorKeys() { }

    /**
     * @return the login principal's name when {@code session} is authenticated; otherwise the user principal's name;
     *         {@code "Guest"} for a {@code null} session; {@code null} only for a session with no principals at all
     */
    public static String authorKeyFor( final Session session ) {
        if ( session == null ) {
            return WikiPrincipal.GUEST.getName();
        }
        if ( session.isAuthenticated() ) {
            final Principal login = session.getLoginPrincipal();
            if ( login != null && login.getName() != null && !login.getName().isBlank() ) {
                return login.getName();
            }
        }
        final Principal user = session.getUserPrincipal();
        return user == null ? null : user.getName();
    }
}
