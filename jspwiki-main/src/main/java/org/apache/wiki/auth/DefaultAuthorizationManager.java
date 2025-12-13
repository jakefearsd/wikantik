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
package org.apache.wiki.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Acl;
import org.apache.wiki.api.core.AclEntry;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.core.Session;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.api.exceptions.WikiException;
import org.apache.wiki.auth.acl.AclManager;
import org.apache.wiki.auth.acl.UnresolvedPrincipal;
import org.apache.wiki.auth.authorize.GroupManager;
import org.apache.wiki.auth.authorize.Role;
import org.apache.wiki.auth.permissions.AllPermission;
import org.apache.wiki.auth.permissions.PagePermission;
import org.apache.wiki.auth.permissions.PermissionChecks;
import org.apache.wiki.auth.user.UserDatabase;
import org.apache.wiki.auth.user.UserProfile;
import org.apache.wiki.event.WikiEventListener;
import org.apache.wiki.event.WikiEventManager;
import org.apache.wiki.event.WikiSecurityEvent;
import org.apache.wiki.i18n.InternationalizationManager;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.preferences.Preferences;
import org.apache.wiki.util.ClassUtil;
import org.freshcookies.security.policy.LocalPolicy;

import jakarta.servlet.http.HttpServletResponse;
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

    private Authorizer authorizer;

    /** Cache for storing ProtectionDomains used to evaluate the local policy. */
    private final Map< Principal, ProtectionDomain > cachedPds = new WeakHashMap<>();

    private Engine engine;

    private volatile LocalPolicy localPolicy;

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
     * Expects to find property 'jspwiki.authorizer' with a valid Authorizer implementation name to take care of role lookup operations.
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws WikiException {
        this.engine = engine;
        LOG.info( "Initializing AuthorizationManager - anonymous access will be blocked until initialization completes" );

        //  JAAS authorization continues
        authorizer = getAuthorizerImplementation( properties );
        authorizer.initialize( engine, properties );

        // Initialize local security policy
        try {
            final String policyFileName = properties.getProperty( POLICY, DEFAULT_POLICY );
            final URL policyURL = engine.findConfigFile( policyFileName );

            if (policyURL != null) {
                final File policyFile = new File( policyURL.toURI().getPath() );
                LOG.info("We found security policy URL: {} and transformed it to file {}",policyURL, policyFile.getAbsolutePath());
                final LocalPolicy newLocalPolicy = new LocalPolicy( policyFile, engine.getContentEncoding().displayName() );
                newLocalPolicy.refresh();
                localPolicy = newLocalPolicy;  // Assign after refresh completes for thread safety
                LOG.info( "Initialized default security policy: {} - anonymous access now permitted", policyFile.getAbsolutePath() );
            } else {
                final String sb = "JSPWiki was unable to initialize the default security policy (WEB-INF/jspwiki.policy) file. " +
                                  "Please ensure that the jspwiki.policy file exists in the default location. " +
                		          "This file should exist regardless of the existance of a global policy file. " +
                                  "The global policy file is identified by the java.security.policy variable. ";
                final WikiSecurityException wse = new WikiSecurityException( sb );
                LOG.fatal( sb, wse );
                throw wse;
            }
        } catch ( final Exception e) {
            LOG.error("Could not initialize local security policy: {}", e.getMessage() );
            throw new WikiException( "Could not initialize local security policy: " + e.getMessage(), e );
        }
    }

    /**
     * Attempts to locate and initialize an Authorizer to use with this manager. Throws a WikiException if no entry is found, or if one
     * fails to initialize.
     *
     * @param props jspwiki.properties, containing a 'jspwiki.authorization.provider' class name.
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
                return ClassUtil.buildInstance( "org.apache.wiki.auth.authorize", clazz );
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
        // Check if local policy is initialized - deny access during startup window
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
