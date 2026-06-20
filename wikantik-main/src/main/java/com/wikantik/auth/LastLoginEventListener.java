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

import com.wikantik.auth.user.UserDatabase;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;
import java.util.Date;

/**
 * Stamps an account's last-login timestamp whenever it successfully authenticates.
 * <p>
 * Listens for {@link WikiSecurityEvent#LOGIN_AUTHENTICATED}, which the authentication
 * manager fires for every real authentication — interactive form login, SSO, and a
 * remember-me cookie re-establishing a session — and records the moment via a targeted
 * {@link UserDatabase#recordLastLogin(String, Date)} write that does not touch any other
 * profile field. Failures are logged and swallowed: recording a last-login is telemetry
 * and must never abort the login it is observing.
 */
public final class LastLoginEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( LastLoginEventListener.class );

    private final UserDatabase userDatabase;

    public LastLoginEventListener( final UserDatabase userDatabase ) {
        this.userDatabase = userDatabase;
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( !( event instanceof WikiSecurityEvent se ) || se.getType() != WikiSecurityEvent.LOGIN_AUTHENTICATED ) {
            return;
        }
        final String loginName = principalName( se );
        if ( loginName == null ) {
            LOG.debug( "LOGIN_AUTHENTICATED with no resolvable principal name; not recording last login" );
            return;
        }
        try {
            userDatabase.recordLastLogin( loginName, new Date() );
        } catch ( final Exception e ) {
            // Best-effort telemetry — never let a stamping failure break authentication.
            LOG.warn( "Could not record last login for '{}': {}", loginName, e.getMessage(), e );
        }
    }

    /**
     * Resolves the login name from a security event. The authentication manager fires
     * LOGIN_AUTHENTICATED with the login principal in the dedicated {@code principal} slot,
     * but the 3-arg event constructor stores a Principal as the {@code target} instead, so
     * both slots are checked (mirrors {@code AuditEventListener}).
     */
    private static String principalName( final WikiSecurityEvent se ) {
        final Object principal = se.getPrincipal();
        if ( principal instanceof Principal p ) {
            return p.getName();
        }
        final Object target = se.getTarget();
        if ( target instanceof Principal p ) {
            return p.getName();
        }
        return null;
    }
}
