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

import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.acl.DefaultAclManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.collab.CollabPermissions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Permission;
import java.security.Principal;
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

    /**
     * {@link Session#isAuthenticated()}: a logout invalidates the wiki session (its principals become the guest's)
     * before {@code LOGOUT} fires, so its participants read as signed out. Anything but a wiki session is signed out.
     */
    @Override
    public boolean signedIn( final Object participantSession ) {
        final Session session = asSession( participantSession, "signed-in check", "(any page)" );
        return session != null && session.isAuthenticated();
    }

    /**
     * The author key the wiki session resolves to now ({@link AuthorKeys#authorKeyFor}, the key the REST layer joins
     * with), or null for anything but a wiki session.
     */
    @Override
    public String authorKeyOf( final Object participantSession ) {
        return participantSession instanceof Session session ? AuthorKeys.authorKeyFor( session ) : null;
    }

    /** {@code AllPermission} (the Admin group, or the time-boxed bootstrap administrator). */
    @Override
    public boolean isAdmin( final Object participantSession ) {
        final Session session = asSession( participantSession, "admin check", "(any page)" );
        return session != null && authorization().isPermitted( session,
                new AllPermission( engine.getApplicationName() ) );
    }

    /**
     * Ruling F1: the policy's {@code view} and {@code edit} on {@code pageName} (the page is missing, so no stored rules
     * apply), then the rules of {@code baseText} as {@link DefaultAclManager#aclForText} reads them, each granting both
     * actions to a principal the session holds, as the authorizer decides a stored page's rules. Refuses when the wiki's
     * ACL manager cannot read a text it is handed, and when reading the rules fails.
     */
    @Override
    public boolean mayAccessAsIf( final Object participantSession, final String pageName, final String baseText ) {
        final Session session = asSession( participantSession, "view/edit under the last access rules", pageName );
        if( session == null ) {
            return false;
        }
        final AuthorizationManager auth = authorization();
        final Page page = Wiki.contents().page( engine, pageName );
        final Permission view = PermissionFactory.getPagePermission( page, "view" );
        final Permission edit = PermissionFactory.getPagePermission( page, "edit" );
        if( !auth.isPermitted( session, view ) || !auth.isPermitted( session, edit ) ) {
            return false;
        }
        final AclManager acls = AuthSubsystemBridge.fromLegacyEngine( engine ).aclManager();
        if( !( acls instanceof DefaultAclManager reader ) ) {
            LOG.warn( "Denying '{}': the ACL manager ({}) cannot read the access rules of a deleted page's text", pageName,
                    acls == null ? "none" : acls.getClass().getName() );
            return false;
        }
        final Acl acl = reader.aclForText( page, baseText );
        if( acl.isEmpty() || auth.isPermitted( session, new AllPermission( engine.getApplicationName() ) ) ) {
            return true;
        }
        return grants( auth, session, acl, view ) && grants( auth, session, acl, edit );
    }

    /**
     * Ruling F3': the rules {@link DefaultAclManager#aclForText} reads from {@code text}: a non-empty ACL (rules, or the
     * lock of rules it cannot read) restricts. True (fail closed, logged) when the ACL manager cannot read a text it is
     * handed or the parse fails.
     */
    @Override
    public boolean restricts( final String pageName, final String text ) {
        final AclManager acls = AuthSubsystemBridge.fromLegacyEngine( engine ).aclManager();
        if( !( acls instanceof DefaultAclManager reader ) ) {
            LOG.warn( "Treating the guard of '{}' as restricting: the ACL manager ({}) cannot read a text", pageName,
                    acls == null ? "none" : acls.getClass().getName() );
            return true;
        }
        try {
            return !reader.aclForText( Wiki.contents().page( engine, pageName ), text ).isEmpty();
        } catch( final RuntimeException e ) {
            LOG.warn( "Treating the guard of '{}' as restricting: its access rules could not be read: {}", pageName,
                    e.toString() );
            return true;
        }
    }

    /** True when the session holds a principal {@code acl} lists for {@code permission} (an unresolved one resolved now). */
    private static boolean grants( final AuthorizationManager auth, final Session session, final Acl acl,
                                   final Permission permission ) {
        for( final Principal listed : acl.findPrincipals( permission ) ) {
            final Principal principal = listed instanceof UnresolvedPrincipal unresolved
                    ? auth.resolvePrincipal( unresolved.getName() ) : listed;
            if( auth.hasRoleOrPrincipal( session, principal ) ) {
                return true;
            }
        }
        return false;
    }

    private AuthorizationManager authorization() {
        return AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
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
