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
package com.wikantik.auth.identity;

import com.wikantik.api.core.Engine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;

/**
 * Never-null access to the engine's {@link PublicIdentity} for code that has only an {@link Engine} and may run against a bare
 * or mocked one. Reading the typed snapshot through {@link AuthSubsystemBridge} is not a {@code WikiEngine#getManager} call
 * (DecompositionArchTest R-2/R-4 are unaffected).
 */
public final class PublicIdentities {

    private static final Logger LOG = LogManager.getLogger( PublicIdentities.class );

    /** Knows no accounts: values pass through, email-shaped ones are masked. */
    private static final PublicIdentity NO_ACCOUNTS = new DefaultPublicIdentity( null, Clock.systemUTC() );

    private PublicIdentities() { }

    /** The wired service for {@code engine}, else a database-less fallback that still never leaks an email-shaped value. */
    public static PublicIdentity forEngine( final Engine engine ) {
        if ( engine != null ) {
            try {
                final PublicIdentity wired = AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity();
                if ( wired != null ) {
                    return wired;
                }
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not reach the PublicIdentity service; falling back to masking only: {}", e.getMessage(), e );
            }
        }
        return NO_ACCOUNTS;
    }
}
