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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.collab.CollabPermissions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * {@link CollabPermissions} over the wiki's authorization, through the same {@link PermissionFilter} path as
 * {@code RestServletBase.checkPagePermission} (so ACLs and policy grants both apply). Nothing is cached: every call
 * asks again, so a publish re-checks with the participant's live session (A5).
 */
public final class WikiCollabPermissions implements CollabPermissions {

    private static final Logger LOG = LogManager.getLogger( WikiCollabPermissions.class );

    private final Engine engine;
    private final PermissionFilter filter;

    public WikiCollabPermissions( final Engine engine ) {
        this.engine = Objects.requireNonNull( engine, "engine" );
        this.filter = new PermissionFilter( engine );
    }

    @Override
    public boolean canEdit( final Object participantSession, final String pageName ) {
        final Session session = asSession( participantSession, "edit", pageName );
        return session != null && filter.canAccess( session, pageName, "edit" );
    }

    @Override
    public boolean canCreate( final Object participantSession, final String pageName ) {
        final Session session = asSession( participantSession, "createPages", pageName );
        return session != null && AuthSubsystemBridge.fromLegacyEngine( engine ).authorization()
                .checkPermission( session, new WikiPermission( engine.getApplicationName(),
                        WikiPermission.CREATE_PAGES_ACTION ) );
    }

    private static Session asSession( final Object candidate, final String action, final String pageName ) {
        if( candidate instanceof Session session ) {
            return session;
        }
        LOG.warn( "Denying {} on '{}': participant session is not a wiki Session ({})", action, pageName,
                candidate == null ? "null" : candidate.getClass().getName() );
        return null;
    }
}
