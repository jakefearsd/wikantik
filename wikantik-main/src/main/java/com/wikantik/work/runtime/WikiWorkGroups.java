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

import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.work.WorkUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one definition of "the groups a login belongs to" for work management, shared by {@link WikiWorkActors} and
 * {@link WikiWorkDirectory}. Membership is by login name, as in {@code GroupManager#isUserInRole}; it is resolved
 * against the live group manager on every call, never cached.
 */
final class WikiWorkGroups {

    private static final Logger LOG = LogManager.getLogger( WikiWorkGroups.class );

    private WikiWorkGroups() {
    }

    static Set< String > groupsOf( final GroupManager groups, final String login ) {
        final Set< String > names = new TreeSet<>();
        if ( login == null || login.isBlank() ) {
            return names;
        }
        final Principal member = new WikiPrincipal( login );
        for ( final Principal role : groups.getRoles() ) {
            try {
                final Group group = groups.getGroup( role.getName() );
                if ( group != null && group.isMember( member ) ) {
                    names.add( group.getName() );
                }
            } catch ( final NoSuchPrincipalException e ) {
                LOG.warn( "Group '{}' vanished while resolving the groups of a login; skipping it: {}",
                    role.getName(), e.getMessage() );
            }
        }
        return names;
    }

    /** The member names of the named group; an unknown group is the empty set, with a DEBUG line. */
    static Set< String > membersOf( final GroupManager groups, final String groupName ) {
        final Set< String > names = new TreeSet<>();
        try {
            final Group group = groups.getGroup( groupName );
            if ( group != null ) {
                for ( final Principal p : group.members() ) {
                    names.add( p.getName() );
                }
            }
        } catch ( final NoSuchPrincipalException e ) {
            LOG.debug( "Work management: group '{}' is unknown, so it has no members: {}", groupName, e.getMessage() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "The group manager could not be read for group '{}': {}", groupName, e.getMessage(), e );
            throw new WorkUnavailableException( "The group database could not be read.", e );
        }
        return names;
    }
}
