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
package com.wikantik.auth.authorize;

import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup check run by {@link DefaultGroupManager#initialize}: reports group members that are not
 * the login name of an existing account. Membership is matched by login name only, so such a member
 * (typically a full name stored by an older release) grants nothing. The {@code Admin} group is
 * reported at ERROR because it can leave a wiki without a working administrator. The check is
 * advisory and never fails startup.
 */
final class GroupMemberLoginCheck {

    private static final Logger LOG = LogManager.getLogger( GroupMemberLoginCheck.class );

    /** Where operators find the queries that fix members stored by full or wiki name. */
    static final String UPGRADE_DOC = "docs/admin/Security.md#upgrading-to-2453-authorization-checks";

    private static final String ADMIN_GROUP = "Admin";

    private GroupMemberLoginCheck() { }

    /** Logs one WARN for ordinary groups and one ERROR for the {@code Admin} group, each only when needed. */
    static void report( final Group[] groups, final UserManager users ) {
        final UserDatabase db = users == null ? null : users.getUserDatabase();
        if ( db == null ) {
            LOG.debug( "No user database; skipping the startup check for non-login group members" );
            return;
        }
        final List< String > others = new ArrayList<>();
        final List< String > admins = new ArrayList<>();
        for ( final Group group : groups ) {
            for ( final Principal member : group.members() ) {
                if ( isLoginName( db, member.getName() ) ) {
                    continue;
                }
                if ( ADMIN_GROUP.equals( group.getName() ) ) {
                    admins.add( member.getName() );
                } else {
                    others.add( group.getName() + ": " + member.getName() );
                }
            }
        }
        if ( !others.isEmpty() ) {
            LOG.warn( "Group members that are not login names never match and grant nothing: {}. "
                    + "Replace each with the account's login name; see {}", String.join( ", ", others ), UPGRADE_DOC );
        }
        if ( !admins.isEmpty() ) {
            // LOG.error justified: the wiki may have no working administrator until an operator fixes the Admin group.
            LOG.error( "Admin group members that are not login names never match, so they have no admin access: {}. "
                    + "Replace each with the account's login name; see {}", String.join( ", ", admins ), UPGRADE_DOC );
        }
    }

    private static boolean isLoginName( final UserDatabase db, final String name ) {
        try {
            return db.findByLoginName( name ) != null;
        } catch ( final NoSuchPrincipalException e ) {
            // Expected for a member stored by full or wiki name; reported by the caller.
            LOG.debug( "Group member '{}' is not a login name: {}", name, e.getMessage() );
            return false;
        } catch ( final RuntimeException e ) {
            // The check is advisory: a failing lookup must not fail startup or report a member as bad.
            LOG.warn( "Could not check whether group member '{}' is a login name: {}", name, e.getMessage() );
            return true;
        }
    }
}
