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
package com.wikantik.collab.adapter;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.collab.CollabIdentity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * {@link CollabIdentity} over {@link PublicIdentity}: a public display name, never the stored login. When no display
 * name resolves the answer is null (ruling R14), so the session's own labels apply: a participant's {@code "Editor <n>"},
 * which keeps several nameless authors apart, and the {@code "Unknown contributor"} of the contributor list. No name
 * resolves when {@link PublicIdentity} answers blank, one of its placeholders ({@link PublicIdentity#UNKNOWN},
 * {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}), the key itself (it passes an unknown principal through unchanged, and
 * that is the login), or fails. {@link PublicIdentity#FORMER_MEMBER} is a public label and is kept.
 */
public final class PublicIdentityCollabIdentity implements CollabIdentity {

    private static final Logger LOG = LogManager.getLogger( PublicIdentityCollabIdentity.class );

    private final PublicIdentity identity;

    public PublicIdentityCollabIdentity( final PublicIdentity identity ) {
        this.identity = Objects.requireNonNull( identity, "identity" );
    }

    /**
     * True when {@code name} is the unresolved key passed through unchanged. An account whose display name happens to
     * equal its login is the account's own public name, so it only counts as an echo when no such account exists.
     */
    private boolean echoedLogin( final String name, final String authorKey ) {
        if( !name.equals( authorKey ) ) {
            return false;
        }
        try {
            return identity.userIdForLogin( authorKey ).isEmpty();
        } catch( final RuntimeException e ) {
            LOG.warn( "Account lookup for a display name failed; treating it as an echoed login: {}", e.getMessage(), e );
            return true;
        }
    }

    @Override
    public String displayName( final String authorKey ) {
        final String name;
        try {
            name = identity.displayName( authorKey );
        } catch( final RuntimeException e ) {
            LOG.warn( "Display-name resolution failed; answering no name: {}", e.getMessage(), e );
            return null;
        }
        if( name == null || name.isBlank() || PublicIdentity.UNKNOWN.equals( name )
                || PublicIdentity.UNKNOWN_CONTRIBUTOR.equals( name ) || echoedLogin( name, authorKey ) ) {
            return null;
        }
        return name;
    }
}
