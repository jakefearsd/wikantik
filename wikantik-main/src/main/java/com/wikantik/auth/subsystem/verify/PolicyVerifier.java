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

import org.apache.commons.lang3.ArrayUtils;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.SecurityVerifier;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupDatabase;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.user.DummyUserDatabase;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.freshcookies.security.policy.PolicyReader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.KeyStore;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies the Java security policy, policy-to-container-role alignment,
 * the group database, and the user database. Extracted from
 * {@link SecurityVerifier} as part of Phase 4 Checkpoint 3.
 *
 * <p>All {@code verify*} methods run eagerly in the constructor, posting
 * messages to the supplied {@link Session} exactly as the original
 * monolith did.</p>
 */
public final class PolicyVerifier {

    private final Engine               engine;
    private final Session              session;
    private final AuthorizationManager authorizationManager;
    private final GroupManager         groupManager;
    private final UserManager          userManager;

    private boolean     isSecurityPolicyConfigured;
    private Principal[] policyPrincipals = new Principal[0];

    /**
     * Constructs a PolicyVerifier and immediately runs all verify* methods.
     *
     * @param engine               the wiki engine
     * @param session              the wiki session (receives diagnostic messages)
     * @param authorizationManager the authorization manager
     * @param groupManager         the group manager
     * @param userManager          the user manager
     */
    public PolicyVerifier( final Engine engine,
                           final Session session,
                           final AuthorizationManager authorizationManager,
                           final GroupManager groupManager,
                           final UserManager userManager ) {
        this.engine               = engine;
        this.session              = session;
        this.authorizationManager = authorizationManager;
        this.groupManager         = groupManager;
        this.userManager          = userManager;

        verifyPolicy();
        try {
            verifyPolicyAndContainerRoles();
        } catch( final WikiException e ) {
            session.addMessage( SecurityVerifier.ERROR_ROLES, e.getMessage() );
        }
        verifyGroupDatabase();
        verifyUserDatabase();
    }

    /** Returns a defensive copy of the principals found in the security policy file (may be zero-length). */
    public Principal[] policyPrincipals() {
        return policyPrincipals.clone();
    }

    /** Returns {@code true} if the Java security policy is configured correctly. */
    public boolean isSecurityPolicyConfigured() {
        return isSecurityPolicyConfigured;
    }

    /**
     * Formats and returns an HTML table containing sample permissions and what
     * roles are allowed to have them. Delegates to {@link PolicyRoleTable} — split out
     * (2026-09, complexity burn-down) so the table-rendering helpers don't count against this
     * class's own WMC/cohesion.
     *
     * @return the formatted HTML table
     */
    public String policyRoleTable() {
        return new PolicyRoleTable( authorizationManager ).render( policyPrincipals, engine.getApplicationName() );
    }

    // -------------------------------------------------------------------------
    // Package-private verify* methods (same as original SecurityVerifier)
    // -------------------------------------------------------------------------

    void verifyPolicyAndContainerRoles() throws WikiException {
        final Authorizer authorizer = authorizationManager.getAuthorizer();
        final Principal[] containerRoles = authorizer.getRoles();
        boolean missing = false;
        for( final Principal principal : policyPrincipals ) {
            if( principal instanceof final Role role ) {
                final boolean isContainerRole = ArrayUtils.contains( containerRoles, role );
                if( !Role.isBuiltInRole( role ) && !isContainerRole ) {
                    session.addMessage( SecurityVerifier.ERROR_ROLES, "Role '" + role.getName() + "' is defined in security policy but not in web.xml." );
                    missing = true;
                }
            }
        }
        if( !missing ) {
            session.addMessage( SecurityVerifier.INFO_ROLES, "Every non-standard role defined in the security policy was also found in web.xml." );
        }
    }

    void verifyGroupDatabase() {
        final GroupManager mgr = groupManager;
        GroupDatabase db = null;
        try {
            db = groupManager.getGroupDatabase();
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not retrieve GroupManager: " + e.getMessage() );
        }

        if( mgr == null || db == null ) {
            if( mgr == null ) {
                session.addMessage( SecurityVerifier.ERROR_GROUPS, "GroupManager is null; JSPWiki could not initialize it. Check the error logs." );
            }
            if( db == null ) {
                session.addMessage( SecurityVerifier.ERROR_GROUPS, "GroupDatabase is null; JSPWiki could not initialize it. Check the error logs." );
            }
            return;
        }

        session.addMessage( SecurityVerifier.INFO_GROUPS, "GroupDatabase is of type '" + db.getClass().getName() + "'. It appears to be initialized properly." );

        final int oldGroupCount;
        try {
            final Group[] groups = db.groups();
            oldGroupCount = groups.length;
            session.addMessage( SecurityVerifier.INFO_GROUPS, "The group database contains " + oldGroupCount + " groups." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not obtain a list of current groups: " + e.getMessage() );
            return;
        }

        final String name = "TestGroup" + System.currentTimeMillis();
        final Group group;
        try {
            group = mgr.parseGroup( name, "", true );
            final Principal user = new WikiPrincipal( "TestUser" );
            group.add( user );
            db.save( group, new WikiPrincipal( "SecurityVerifier" ) );

            if( db.groups().length == oldGroupCount ) {
                session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not add a test group to the database." );
                return;
            }
            session.addMessage( SecurityVerifier.INFO_GROUPS, "The group database allows new groups to be created, as it should." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not add a group to the database: " + e.getMessage() );
            return;
        }

        try {
            db.delete( group );
            if( db.groups().length != oldGroupCount ) {
                session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not delete a test group from the database." );
                return;
            }
            session.addMessage( SecurityVerifier.INFO_GROUPS, "The group database allows groups to be deleted, as it should." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_GROUPS, "Could not delete a test group from the database: " + e.getMessage() );
            return;
        }

        session.addMessage( SecurityVerifier.INFO_GROUPS, "The group database configuration looks fine." );
    }

    void verifyUserDatabase() {
        final UserDatabase db = userManager.getUserDatabase();

        if( db == null ) {
            session.addMessage( SecurityVerifier.ERROR_DB, "UserDatabase is null; JSPWiki could not initialize it. Check the error logs." );
            return;
        }

        if( db instanceof DummyUserDatabase ) {
            session.addMessage( SecurityVerifier.ERROR_DB, "UserDatabase is DummyUserDatabase; JSPWiki " +
                    "may not have been able to initialize the database you supplied in " +
                    "wikantik.properties, or you left the 'wikantik.userdatabase' property " +
                    "blank. Check the error logs." );
        }

        session.addMessage( SecurityVerifier.INFO_DB, "UserDatabase is of type '" + db.getClass().getName() +
                "'. It appears to be initialized properly." );

        final int oldUserCount;
        try {
            final Principal[] users = db.getWikiNames();
            oldUserCount = users.length;
            session.addMessage( SecurityVerifier.INFO_DB, "The user database contains " + oldUserCount + " users." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_DB, "Could not obtain a list of current users: " + e.getMessage() );
            return;
        }

        final String loginName = "TestUser" + System.currentTimeMillis();
        try {
            final UserProfile profile = db.newProfile();
            profile.setEmail( "wikantik.tests@mailinator.com" );
            profile.setLoginName( loginName );
            profile.setFullname( "FullName" + loginName );
            profile.setPassword( "password" );
            db.save( profile );

            if( db.getWikiNames().length == oldUserCount ) {
                session.addMessage( SecurityVerifier.ERROR_DB, "Could not add a test user to the database." );
                return;
            }
            session.addMessage( SecurityVerifier.INFO_DB, "The user database allows new users to be created, as it should." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_DB, "Could not add a test user to the database: " + e.getMessage() );
            return;
        }

        try {
            db.deleteByLoginName( loginName );
            if( db.getWikiNames().length != oldUserCount ) {
                session.addMessage( SecurityVerifier.ERROR_DB, "Could not delete a test user from the database." );
                return;
            }
            session.addMessage( SecurityVerifier.INFO_DB, "The user database allows users to be deleted, as it should." );
        } catch( final WikiSecurityException e ) {
            session.addMessage( SecurityVerifier.ERROR_DB, "Could not delete a test user to the database: " + e.getMessage() );
            return;
        }

        session.addMessage( SecurityVerifier.INFO_DB, "The user database configuration looks fine." );
    }

    @SuppressWarnings( "unchecked" )
    void verifyPolicy() {
        final URL policyURL = engine.findConfigFile( AuthorizationManager.DEFAULT_POLICY );
        String path = policyURL.getPath();
        if( path.startsWith( "file:" ) ) {
            path = path.substring( 5 );
        }
        final File policyFile = new File( path );

        try {
            final PolicyReader policy = new PolicyReader( policyFile );
            session.addMessage( SecurityVerifier.INFO_POLICY, "The security policy '" + policy.getFile() + "' exists." );

            final KeyStore ks = policy.getKeyStore();
            if( ks == null ) {
                session.addMessage( SecurityVerifier.WARNING_POLICY,
                        "Policy file does not have a keystore... at least not one that we can locate. If your policy file " +
                        "does not contain any 'signedBy' blocks, this is probably ok." );
            } else {
                session.addMessage( SecurityVerifier.INFO_POLICY,
                        "The security policy specifies a keystore, and we were able to locate it in the filesystem." );
            }

            policy.read();
            final List<Exception> errors = policy.getMessages();
            if( !errors.isEmpty() ) {
                for( final Exception e : errors ) {
                    session.addMessage( SecurityVerifier.ERROR_POLICY, e.getMessage() );
                }
            } else {
                session.addMessage( SecurityVerifier.INFO_POLICY, "The security policy looks fine." );
                isSecurityPolicyConfigured = true;
            }

            final Set<Principal> principals = new LinkedHashSet<>();
            principals.add( Role.ALL );
            principals.add( Role.ANONYMOUS );
            principals.add( Role.ASSERTED );
            principals.add( Role.AUTHENTICATED );
            final ProtectionDomain[] domains = policy.getProtectionDomains();
            for( final ProtectionDomain domain : domains ) {
                principals.addAll( Arrays.asList( domain.getPrincipals() ) );
            }
            policyPrincipals = principals.toArray( new Principal[0] );
        } catch( final IOException e ) {
            session.addMessage( SecurityVerifier.ERROR_POLICY, e.getMessage() );
        }
    }
}
