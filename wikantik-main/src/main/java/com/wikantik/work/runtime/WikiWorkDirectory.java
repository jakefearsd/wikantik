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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Engine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.Set;

/** The wiki's user and group directory, as work management sees it. Nothing here is cached. */
public final class WikiWorkDirectory implements WorkDirectory {

    private static final Logger LOG = LogManager.getLogger( WikiWorkDirectory.class );

    private final Engine engine;

    public WikiWorkDirectory( final Engine engine ) {
        this.engine = engine;
    }

    @Override
    public Set< String > groupsOf( final String login ) {
        return WikiWorkGroups.groupsOf( AuthSubsystemBridge.fromLegacyEngine( engine ).groups(), login );
    }

    /**
     * Asked only on behalf of a wiki admin who typed a login. The user database has no non-throwing exact lookup, so
     * the account snapshot answers first (no exception for any account that has a mention id); only a login it does
     * not know reaches {@code findByLoginName}, and an unknown login is then logged, at WARN, as a failed lookup.
     */
    @Override
    public boolean exists( final String login ) {
        if ( login == null || login.isBlank() ) {
            return false;
        }
        if ( identity().userIdForLogin( login ).isPresent() ) {
            return true;
        }
        try {
            AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase().findByLoginName( login );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            LOG.warn( "Work management: an admin named a login that no account has: {}", e.getMessage() );
            return false;
        } catch ( final RuntimeException e ) {
            LOG.warn( "User lookup failed while checking that a login exists; treating it as unknown: {}",
                e.getMessage(), e );
            return false;
        }
    }

    /**
     * Whether the login is a current account, for a decision that must not mistake an outage for an answer: the key
     * port's "the agent's key owner no longer exists" refusal, whose advice (name a new owner) moves ownership for
     * good. Unlike {@link #exists}, a failed lookup is not "no": it throws {@link WorkUnavailableException} (logged
     * at WARN), which the REST layer answers with 503 and nothing is minted.
     */
    public boolean ownerExists( final String login ) {
        if ( login == null || login.isBlank() ) {
            return false;
        }
        if ( identity().userIdForLogin( login ).isPresent() ) {
            return true;
        }
        try {
            AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase().findByLoginName( login );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            LOG.debug( "Work management: the key owner {} is no longer an account: {}", login, e.getMessage() );
            return false;
        } catch ( final RuntimeException e ) {
            LOG.warn( "User lookup failed while checking an agent key's owner; the rotation is answered as "
                + "unavailable: {}", e.getMessage(), e );
            throw new WorkUnavailableException( "The user database could not be read.", e );
        }
    }

    /**
     * The public display name. A value that names no account at all (the wiki's resolver would hand it back as it is)
     * is shown as {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}: a login never reaches a reader.
     */
    @Override
    public String displayName( final String login ) {
        final PublicIdentity identity = identity();
        final String name = identity.displayName( login );
        if ( login != null && name.equals( login.trim() ) && identity.userIdForLogin( login ).isEmpty() ) {
            return PublicIdentity.UNKNOWN_CONTRIBUTOR;
        }
        return name;
    }

    @Override
    public Optional< String > loginForDisplayName( final String displayName ) {
        return identity().loginForDisplayName( displayName );
    }

    @Override
    public Optional< String > loginForMentionId( final String id ) {
        return identity().loginForUserId( id );
    }

    @Override
    public Optional< String > mentionId( final String login ) {
        return identity().userIdForLogin( login );
    }

    private PublicIdentity identity() {
        return PublicIdentities.forEngine( engine );
    }
}
