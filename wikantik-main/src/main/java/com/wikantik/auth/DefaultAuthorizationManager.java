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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionChecks;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.pages.PageManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.util.ClassUtil;
import org.freshcookies.security.policy.LocalPolicy;

import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.WeakHashMap;


/**
 * <p>Default implementation for {@link AuthorizationManager}</p>
 * {@inheritDoc}
 *
 * <p>See the {@link #checkPermission(Session, Permission)} and {@link #hasRoleOrPrincipal(Session, Principal)} methods for more
 * information on the authorization logic.</p>
 * @since 2.3
 * @see AuthenticationManager
 */
public class DefaultAuthorizationManager implements AuthorizationManager {

    private static final Logger LOG = LogManager.getLogger( DefaultAuthorizationManager.class );

    /** Property name for the JNDI DataSource used by DatabasePolicy. When set, the database-backed policy is used instead of file-based. */
    public static final String PROP_POLICY_DATASOURCE = "wikantik.policy.datasource";

    /** Property name for the database table holding policy grants. */
    public static final String PROP_POLICY_TABLE = "wikantik.policy.table";

    /** Default table name for database-backed policy grants. */
    public static final String DEFAULT_POLICY_TABLE = "policy_grants";

    /** Property name for the bootstrap admin override. When set, the named user bypasses all policy checks. */
    public static final String PROP_BOOTSTRAP_ADMIN = "wikantik.admin.bootstrap";

    private Authorizer authorizer;

    /** Cache for storing ProtectionDomains used to evaluate the local policy. */
    private final Map< Principal, ProtectionDomain > cachedPds = new WeakHashMap<>();

    private Engine engine;

    private volatile LocalPolicy localPolicy;

    private DatabasePolicy databasePolicy;
    private String bootstrapAdmin;

    /**
     * Constructs a new DefaultAuthorizationManager instance.
     */
    public DefaultAuthorizationManager() {
    }

    /** {@inheritDoc} */
    @Override
    public boolean checkPermission( final Session session, final Permission permission ) {
        // A slight sanity check.
        if( session == null || permission == null ) {
            fireEvent( WikiSecurityEvent.ACCESS_DENIED, null, permission );
            return false;
        }

        // Bootstrap admin override — bypasses all policy checks for the configured user
        if ( bootstrapAdmin != null ) {
            for ( final Principal p : session.getPrincipals() ) {
                if ( bootstrapAdmin.equals( p.getName() ) ) {
                    fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, session.getLoginPrincipal(), permission );
                    return true;
                }
            }
        }

        final Principal user = session.getLoginPrincipal();

        // Always allow the action if user has AllPermission
        final Permission allPermission = new AllPermission( engine.getApplicationName() );
        final boolean hasAllPermission = checkStaticPermission( session, allPermission );
        if( hasAllPermission ) {
            fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
            return true;
        }

        // If the user doesn't have *at least* the permission granted by policy, return false.
        final boolean hasPolicyPermission = checkStaticPermission( session, permission );
        if( !hasPolicyPermission ) {
            fireEvent( WikiSecurityEvent.ACCESS_DENIED, user, permission );
            return false;
        }

        // If this isn't a PagePermission, it's allowed
        if( !( permission instanceof PagePermission pagePerm ) ) {
            fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
            return true;
        }

        // If the page or ACL is null, it's allowed.
        final String pageName = pagePerm.getPage();
        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Acl acl = ( page == null) ? null : engine.getManager( AclManager.class ).getPermissions( page );
        if( page == null ||  acl == null || acl.isEmpty() ) {
            fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
            return true;
        }

        // Next, iterate through the Principal objects assigned this permission. If the context's subject possesses
        // any of these, the action is allowed.
        final Principal[] aclPrincipals = acl.findPrincipals( permission );

        LOG.debug( "Checking ACL entries..." );
        LOG.debug( "Acl for this page is: {}", acl );
        LOG.debug( "Checking for principal: {}", Arrays.toString( aclPrincipals ) );
        LOG.debug( "Permission: {}", permission );

        for( Principal aclPrincipal : aclPrincipals ) {
            // If the ACL principal we're looking at is unresolved, try to resolve it here & correct the Acl
            if ( aclPrincipal instanceof UnresolvedPrincipal unresolvedPrincipal ) {
                final AclEntry aclEntry = acl.getAclEntry( aclPrincipal );
                aclPrincipal = resolvePrincipal( unresolvedPrincipal.getName() );
                if ( aclEntry != null && !( aclPrincipal instanceof UnresolvedPrincipal ) ) {
                    aclEntry.setPrincipal( aclPrincipal );
                }
            }

            if ( hasRoleOrPrincipal( session, aclPrincipal ) ) {
                fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
                return true;
            }
        }
        fireEvent( WikiSecurityEvent.ACCESS_DENIED, user, permission );
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public Authorizer getAuthorizer() throws WikiSecurityException {
        if ( authorizer != null ) {
            return authorizer;
        }
        throw new WikiSecurityException( "Authorizer did not initialize properly. Check the logs." );
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasRoleOrPrincipal( final Session session, final Principal principal ) {
        // If either parameter is null, always deny
        if( session == null || principal == null ) {
            return false;
        }

        // If principal is role, delegate to isUserInRole
        if( AuthenticationManager.isRolePrincipal( principal ) ) {
            return isUserInRole( session, principal );
        }

        // We must be looking for a user principal, assuming that the user has been properly logged in. So just look for a name match.
        if( session.isAuthenticated() && AuthenticationManager.isUserPrincipal( principal ) ) {
            final String principalName = principal.getName();
            final Principal[] userPrincipals = session.getPrincipals();
            return Arrays.stream(userPrincipals).anyMatch(userPrincipal -> userPrincipal.getName().equals(principalName));
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasAccess( final Context context, final HttpServletResponse response, final boolean redirect ) throws IOException {
        final boolean allowed = checkPermission( context.getWikiSession(), context.requiredPermission() );

        // Stash the wiki context
        if ( context.getHttpRequest() != null && context.getHttpRequest().getAttribute( Context.ATTR_CONTEXT ) == null ) {
            context.getHttpRequest().setAttribute( Context.ATTR_CONTEXT, context );
        }

        // If access not allowed, redirect
        if( !allowed && redirect ) {
            final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
            final Principal currentUser  = context.getWikiSession().getUserPrincipal();
            final String pageurl = context.getPage().getName();
            if( context.getWikiSession().isAuthenticated() ) {
                LOG.info( "User {} has no access - forbidden (permission={})", currentUser.getName(), context.requiredPermission() );
                context.getWikiSession().addMessage( MessageFormat.format( rb.getString( "security.error.noaccess.logged" ), context.getName()) );
            } else {
                LOG.info( "User {} has no access - redirecting (permission={})", currentUser.getName(), context.requiredPermission() );
                context.getWikiSession().addMessage( MessageFormat.format( rb.getString( "security.error.noaccess" ), context.getName() ) );
            }
            response.sendRedirect( engine.getURL( ContextEnum.WIKI_LOGIN.getRequestContext(), pageurl, null ) );
        }
        return allowed;
    }

    /**
     * {@inheritDoc}
     *
     * Expects to find property 'wikantik.authorizer' with a valid Authorizer implementation name to take care of role lookup operations.
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws WikiException {
        this.engine = engine;
        LOG.info( "Initializing AuthorizationManager - anonymous access will be blocked until initialization completes" );

        //  JAAS authorization continues
        authorizer = getAuthorizerImplementation( properties );
        authorizer.initialize( engine, properties );

        // Initialize security policy — database or file-based
        final String policyDatasource = properties.getProperty( PROP_POLICY_DATASOURCE );
        if ( policyDatasource != null && !policyDatasource.isBlank() ) {
            // Database-backed policy
            try {
                final String tableName = properties.getProperty( PROP_POLICY_TABLE, DEFAULT_POLICY_TABLE );
                final javax.naming.Context initCtx = new javax.naming.InitialContext();
                final javax.naming.Context ctx = (javax.naming.Context) initCtx.lookup( "java:comp/env" );
                final DataSource policyDs = (DataSource) ctx.lookup( policyDatasource );
                databasePolicy = new DatabasePolicy( policyDs, tableName );
                LOG.info( "Initialized database-backed security policy from JNDI DataSource: {}", policyDatasource );
            } catch ( final Exception e ) {
                LOG.error( "Could not initialize database security policy: {}", e.getMessage() );  // Error justified: startup failure is fatal
                throw new WikiException( "Could not initialize database security policy: " + e.getMessage(), e );
            }
        } else {
            // File-based policy (existing behavior)
            try {
                final String policyFileName = properties.getProperty( POLICY, DEFAULT_POLICY );
                final URL policyURL = engine.findConfigFile( policyFileName );
                if (policyURL != null) {
                    final File policyFile = new File( policyURL.toURI().getPath() );
                    LOG.info("We found security policy URL: {} and transformed it to file {}", policyURL, policyFile.getAbsolutePath());
                    final LocalPolicy newLocalPolicy = new LocalPolicy( policyFile, engine.getContentEncoding().displayName() );
                    newLocalPolicy.refresh();
                    localPolicy = newLocalPolicy;
                    LOG.info( "Initialized default security policy: {} - anonymous access now permitted", policyFile.getAbsolutePath() );
                } else {
                    final String sb = "JSPWiki was unable to initialize the default security policy (WEB-INF/wikantik.policy) file. "
                            + "Please ensure that the wikantik.policy file exists in the default location. "
                            + "This file should exist regardless of the existance of a global policy file. "
                            + "The global policy file is identified by the java.security.policy variable. ";
                    final WikiSecurityException wse = new WikiSecurityException( sb );
                    LOG.fatal( sb, wse );
                    throw wse;
                }
            } catch ( final Exception e) {
                LOG.error("Could not initialize local security policy: {}", e.getMessage() );  // Error justified: startup failure
                throw new WikiException( "Could not initialize local security policy: " + e.getMessage(), e );
            }
        }

        // Bootstrap admin override
        bootstrapAdmin = properties.getProperty( PROP_BOOTSTRAP_ADMIN );
        if ( bootstrapAdmin != null && !bootstrapAdmin.isBlank() ) {
            LOG.warn( "BOOTSTRAP ADMIN OVERRIDE IS ACTIVE — user '{}' has AllPermission regardless of "
                    + "database grants. Remove the wikantik.admin.bootstrap property for production use.",
                    bootstrapAdmin );
        } else {
            bootstrapAdmin = null;  // normalize blank to null
        }
    }

    /**
     * Attempts to locate and initialize an Authorizer to use with this manager. Throws a WikiException if no entry is found, or if one
     * fails to initialize.
     *
     * @param props wikantik.properties, containing a 'wikantik.authorization.provider' class name.
     * @return an Authorizer used to get page authorization information.
     * @throws WikiException if there are problems finding the authorizer implementation.
     */
    private Authorizer getAuthorizerImplementation( final Properties props ) throws WikiException {
        final String authClassName = props.getProperty( PROP_AUTHORIZER, DEFAULT_AUTHORIZER );
        return locateImplementation( authClassName );
    }

    private Authorizer locateImplementation( final String clazz ) throws WikiException {
        if ( clazz != null ) {
            try {
                return ClassUtil.buildInstance( "com.wikantik.auth.authorize", clazz );
            } catch( final ReflectiveOperationException e ) {
                LOG.fatal( "Authorizer {} cannot be instantiated", clazz, e );
                throw new WikiException( "Authorizer " + clazz + " cannot be instantiated", e );
            }
        }

        throw new NoRequiredPropertyException( "Unable to find a " + PROP_AUTHORIZER + " entry in the properties.", PROP_AUTHORIZER );
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowedByLocalPolicy( final Principal[] principals, final Permission permission ) {
        // Database-backed policy path
        if ( databasePolicy != null ) {
            for ( final Principal principal : principals ) {
                if ( databasePolicy.implies( principal, permission ) ) {
                    return true;
                }
            }
            return false;
        }

        // File-based policy path (existing behavior)
        final LocalPolicy currentPolicy = localPolicy;
        if ( currentPolicy == null ) {
            LOG.warn( "Local security policy not yet initialized - denying access for permission: {}", permission );
            return false;
        }

        for ( final Principal principal : principals ) {
            // Get ProtectionDomain for this Principal from cache, or create new one
            ProtectionDomain pd = cachedPds.get( principal );
            if ( pd == null ) {
                final ClassLoader cl = this.getClass().getClassLoader();
                final CodeSource cs = new CodeSource( null, (Certificate[])null );
                pd = new ProtectionDomain( cs, null, cl, new Principal[]{ principal } );
                cachedPds.put( principal, pd );
            }

            // Consult the local policy and get the answer
            if ( currentPolicy.implies( pd, permission ) ) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean checkStaticPermission( final Session session, final Permission permission ) {
        // Non-JSPWiki permissions (like PropertyPermission used as DUMMY_PERMISSION in WikiContext)
        // are always allowed. JSPWiki's local policy only handles wiki-specific permissions.
        if ( !PermissionChecks.isJSPWikiPermission( permission ) ) {
            return true;
        }
        return Session.doPrivileged( session, ( PrivilegedAction< Boolean > )() -> {
            // Check the local policy - check each Role/Group and User Principal
            // Note: JVM-wide security policy via AccessController is deprecated and removed.
            // JSPWiki now relies solely on its local policy for authorization.
            if ( allowedByLocalPolicy( session.getRoles(), permission ) || allowedByLocalPolicy( session.getPrincipals(), permission ) ) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } );
    }

    /** {@inheritDoc} */
    @Override
    public Principal resolvePrincipal( final String name ) {
        // Check built-in Roles first
        final Role role = new Role(name);
        if ( Role.isBuiltInRole( role ) ) {
            return role;
        }

        // Check Authorizer Roles
        Principal principal = authorizer.findRole( name );
        if ( principal != null ) {
            return principal;
        }

        // Check Groups
        principal = engine.getManager( GroupManager.class ).findRole( name );
        if ( principal != null ) {
            return principal;
        }

        // Ok, no luck---this must be a user principal
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        try {
            final UserProfile profile = db.find( name );
            final Principal[] principals = db.getPrincipals( profile.getLoginName() );
            return Arrays.stream( principals )
                    .filter( p -> p.getName().equals( name ) )
                    .findFirst()
                    .orElseGet( () -> new UnresolvedPrincipal( name ) );
        } catch( final NoSuchPrincipalException e ) {
            // We couldn't find the user - mark as unresolved
            return new UnresolvedPrincipal( name );
        }
    }


    /** Returns the DatabasePolicy instance, or null if using file-based policy. */
    public DatabasePolicy getDatabasePolicy() {
        return databasePolicy;
    }

    // events processing .......................................................

    /** {@inheritDoc} */
    @Override
    public synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

}
