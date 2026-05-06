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
package com.wikantik.auth.subsystem.verify;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SecurityVerifier;
import com.wikantik.core.subsystem.CoreSubsystemBridge;

import javax.security.auth.spi.LoginModule;

/**
 * Verifies the JAAS login-module configuration. Extracted from
 * {@link SecurityVerifier} as part of Phase 4 Checkpoint 3.
 *
 * <p>{@link #verifyJaas()} runs eagerly in the constructor, posting
 * messages to the supplied {@link Session} exactly as the original
 * monolith did.</p>
 */
public final class JaasVerifier {

    private final Engine  engine;
    private final Session session;

    /**
     * Constructs a JaasVerifier and immediately runs {@link #verifyJaas()}.
     *
     * @param engine  the wiki engine
     * @param session the wiki session (receives diagnostic messages)
     */
    public JaasVerifier( final Engine engine, final Session session ) {
        this.engine  = engine;
        this.session = session;
        verifyJaas();
    }

    // -------------------------------------------------------------------------
    // Package-private verify method (same as original SecurityVerifier)
    // -------------------------------------------------------------------------

    void verifyJaas() {
        final String jaasClass = CoreSubsystemBridge.fromLegacyEngine( engine )
                .properties().asProperties()
                .getProperty( AuthenticationManager.PROP_LOGIN_MODULE );
        if( jaasClass == null || jaasClass.isEmpty() ) {
            session.addMessage( SecurityVerifier.ERROR_JAAS,
                    "The value of the '" + AuthenticationManager.PROP_LOGIN_MODULE
                    + "' property was null or blank. This is a fatal error. This value should be set to a valid LoginModule implementation "
                    + "on the classpath." );
            return;
        }

        Class<?> c = null;
        try {
            session.addMessage( SecurityVerifier.INFO_JAAS,
                    "The property '" + AuthenticationManager.PROP_LOGIN_MODULE + "' specified the class '" + jaasClass + ".'" );
            c = Class.forName( jaasClass );
        } catch( final ClassNotFoundException e ) {
            session.addMessage( SecurityVerifier.ERROR_JAAS,
                    "We could not find the the class '" + jaasClass + "' on the classpath. This is fatal error." );
        }

        if( LoginModule.class.isAssignableFrom( c ) ) {
            session.addMessage( SecurityVerifier.INFO_JAAS,
                    "We found the the class '" + jaasClass + "' on the classpath, and it is a LoginModule implementation. Good!" );
        } else {
            session.addMessage( SecurityVerifier.ERROR_JAAS,
                    "We found the the class '" + jaasClass + "' on the classpath, but it does not seem to be LoginModule implementation! This is fatal error." );
        }
    }
}
