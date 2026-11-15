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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    @Override
    public boolean exists( final String login ) {
        if ( login == null || login.isBlank() ) {
            return false;
        }
        try {
            AuthSubsystemBridge.fromLegacyEngine( engine ).users().getUserDatabase().findByLoginName( login );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            return false;
        } catch ( final RuntimeException e ) {
            LOG.warn( "User lookup failed while checking that a login exists; treating it as unknown: {}",
                e.getMessage(), e );
            return false;
        }
    }

    @Override
    public String displayName( final String login ) {
        final PublicIdentity identity = PublicIdentities.forEngine( engine );
        return identity.displayName( login );
    }
}
