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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.auth.subsystem.verify.ContainerRoleVerifier;
import com.wikantik.auth.subsystem.verify.JaasVerifier;
import com.wikantik.auth.subsystem.verify.PolicyVerifier;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.Permission;
import java.security.Principal;

/**
 * Facade for verifying Wikantik's security configuration. Invoked by the
 * admin security API at <code>/admin/policy/verify</code>.
 *
 * <p>This class is a thin delegator. The three helpers that do the real
 * work are:</p>
 * <ul>
 *   <li>{@link JaasVerifier} — JAAS login-module check</li>
 *   <li>{@link PolicyVerifier} — policy file, policy-to-container-role
 *       alignment, group DB, user DB</li>
 *   <li>{@link ContainerRoleVerifier} — web-container role/URL-pattern table</li>
 * </ul>
 *
 * <p>All {@code public static final String} message-key constants are
 * declared here so that JSPs and callers that reference them as
 * {@code SecurityVerifier.ERROR_POLICY} etc. continue to compile
 * unchanged.</p>
 *
 * @since 2.4
 */
public final class SecurityVerifier {

    /** Message prefix for errors. */
    public static final String ERROR          = "Error.";

    /** Message prefix for warnings. */
    public static final String WARNING        = "Warning.";

    /** Message prefix for information messages. */
    public static final String INFO           = "Info.";

    /** Message topic for policy errors. */
    public static final String ERROR_POLICY   = "Error.Policy";

    /** Message topic for policy warnings. */
    public static final String WARNING_POLICY = "Warning.Policy";

    /** Message topic for policy information messages. */
    public static final String INFO_POLICY    = "Info.Policy";

    /** Message topic for JAAS errors. */
    public static final String ERROR_JAAS     = "Error.Jaas";

    /** Message topic for JAAS warnings. */
    public static final String WARNING_JAAS   = "Warning.Jaas";

    /** Message topic for role-checking errors. */
    public static final String ERROR_ROLES    = "Error.Roles";

    /** Message topic for role-checking information messages. */
    public static final String INFO_ROLES     = "Info.Roles";

    /** Message topic for user database errors. */
    public static final String ERROR_DB       = "Error.UserDatabase";

    /** Message topic for user database warnings. */
    public static final String WARNING_DB     = "Warning.UserDatabase";

    /** Message topic for user database information messages. */
    public static final String INFO_DB        = "Info.UserDatabase";

    /** Message topic for group database errors. */
    public static final String ERROR_GROUPS   = "Error.GroupDatabase";

    /** Message topic for group database warnings. */
    public static final String WARNING_GROUPS = "Warning.GroupDatabase";

    /** Message topic for group database information messages. */
    public static final String INFO_GROUPS    = "Info.GroupDatabase";

    /** Message topic for JAAS information messages. */
    public static final String INFO_JAAS      = "Info.Jaas";

    // -------------------------------------------------------------------------
    // Delegates and retained seams needed by package-private test surface
    // -------------------------------------------------------------------------

    private final PolicyVerifier        policyVerifier;
    private final ContainerRoleVerifier containerRoleVerifier;

    /** Retained for {@link #verifyStaticPermission} package-private seam used by tests. */
    private final AuthorizationManager  authorizationManager;

    /** Retained for {@link #getFileFromProperty} package-private seam used by tests. */
    private final Session               session;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Constructs a new SecurityVerifier for a supplied Engine and WikiSession.
     *
     * @param engine  the wiki engine
     * @param session the wiki session (typically, that of an administrator)
     */
    public SecurityVerifier( final Engine engine, final Session session ) {
        this( engine, session,
              AuthSubsystemBridge.fromLegacyEngine( engine ).authorization(),
              AuthSubsystemBridge.fromLegacyEngine( engine ).groups(),
              AuthSubsystemBridge.fromLegacyEngine( engine ).users() );
    }

    /**
     * Constructs a new SecurityVerifier with explicitly provided manager
     * dependencies. Useful for testing with mock managers.
     *
     * @param engine               the wiki engine
     * @param session              the wiki session (typically, that of an administrator)
     * @param authorizationManager the authorization manager
     * @param groupManager         the group manager
     * @param userManager          the user manager
     */
    SecurityVerifier( final Engine engine,
                      final Session session,
                      final AuthorizationManager authorizationManager,
                      final GroupManager groupManager,
                      final UserManager userManager ) {
        this.session               = session;
        this.authorizationManager  = authorizationManager;
        session.clearMessages();
        new JaasVerifier( engine, session );
        this.policyVerifier        = new PolicyVerifier( engine, session, authorizationManager, groupManager, userManager );
        this.containerRoleVerifier = new ContainerRoleVerifier( engine, authorizationManager );
    }

    // -------------------------------------------------------------------------
    // Public API — delegates to helpers
    // -------------------------------------------------------------------------

    /**
     * Returns an array of unique Principals from the Wikantik security policy file.
     *
     * @return the array of principals (zero-length if policy was not located or specifies none)
     */
    public Principal[] policyPrincipals() {
        return policyVerifier.policyPrincipals();
    }

    /**
     * Formats and returns an HTML table containing sample permissions and what
     * roles are allowed to have them.
     *
     * @return the formatted HTML table
     */
    public String policyRoleTable() {
        return policyVerifier.policyRoleTable();
    }

    /**
     * Returns {@code true} if the Java security policy is configured correctly.
     *
     * @return the result of the configuration check
     */
    public boolean isSecurityPolicyConfigured() {
        return policyVerifier.isSecurityPolicyConfigured();
    }

    /**
     * Formats and returns an HTML table containing the roles the web container
     * is aware of, and whether each role maps to particular servlet URL patterns.
     *
     * @return the formatted HTML table
     * @throws WikiException if tests fail for unexpected reasons
     */
    public String containerRoleTable() throws WikiException {
        return containerRoleVerifier.containerRoleTable();
    }

    /**
     * If the active Authorizer is the WebContainerAuthorizer, returns the roles
     * it knows about; otherwise, a zero-length array.
     *
     * @return the roles parsed from {@code web.xml}, or a zero-length array
     * @throws WikiException if the web authorizer cannot obtain the list of roles
     */
    public Principal[] webContainerRoles() throws WikiException {
        return containerRoleVerifier.webContainerRoles();
    }

    // -------------------------------------------------------------------------
    // Package-private seams retained for unit-test coverage
    // -------------------------------------------------------------------------

    /**
     * Verifies that a particular Principal possesses a Permission, as defined
     * in the security policy file.
     *
     * @param principal  the principal
     * @param permission the permission
     * @return the result, based on consultation with the active Java security policy
     */
    boolean verifyStaticPermission( final Principal principal, final Permission permission ) {
        final Principal[] principals = { principal };
        return authorizationManager.allowedByLocalPolicy( principals, permission );
    }

    /**
     * Looks up a file name based on a JRE system property and returns the
     * associated File object if it exists. Adds messages to the session.
     *
     * @param property the system property to look up
     * @return the file object, or {@code null} if not found
     */
    File getFileFromProperty( final String property ) {
        String propertyValue;
        try {
            propertyValue = System.getProperty( property );
            if( propertyValue == null ) {
                session.addMessage( "Error." + property, "The system property '" + property + "' is null." );
                return null;
            }

            if( propertyValue.startsWith( "=" ) ) {
                propertyValue = propertyValue.substring( 1 );
            }

            try {
                session.addMessage( "Info." + property, "The system property '" + property + "' is set to: "
                        + propertyValue + "." );

                if( !propertyValue.startsWith( "file:" ) ) {
                    propertyValue = "file:" + propertyValue;
                }
                final URL url = URI.create( propertyValue ).toURL();
                final File file = new File( url.getPath() );
                if( file.exists() ) {
                    session.addMessage( "Info." + property, "File '" + propertyValue + "' exists in the filesystem." );
                    return file;
                }
            } catch( final MalformedURLException e ) {
                // Swallow exception because we can't find it anyway
            }
            session.addMessage( "Error." + property, "File '" + propertyValue
                    + "' doesn't seem to exist. This might be a problem." );
            return null;
        } catch( final SecurityException e ) {
            session.addMessage( "Error." + property, "We could not read system property '" + property
                    + "'. This is probably because you are running with a security manager." );
            return null;
        }
    }
}
