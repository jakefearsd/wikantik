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
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;


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

    /** Table name for database-backed policy grants — matches the DDL in postgresql-permissions.ddl. */
    static final String POLICY_TABLE = "policy_grants";

    /** Property name for the bootstrap admin override. When set, the named user bypasses all policy checks. */
    public static final String PROP_BOOTSTRAP_ADMIN = "wikantik.admin.bootstrap";

    /** Property name for the bootstrap admin override TTL in seconds. Default: 24 hours. */
    public static final String PROP_BOOTSTRAP_MAX_AGE = "wikantik.admin.bootstrap.maxAgeSeconds";

    /** Default lifetime for the bootstrap admin override — 24 hours. */
    public static final long DEFAULT_BOOTSTRAP_MAX_AGE_SECONDS = 86400L;

    private Authorizer authorizer;

    /** Cache for storing ProtectionDomains used to evaluate the local policy. */
    private final Map< Principal, ProtectionDomain > cachedPds = new WeakHashMap<>();

    private Engine engine;

    private volatile LocalPolicy localPolicy;

    private DatabasePolicy databasePolicy;
    private String bootstrapAdmin;
    private volatile long bootstrapExpiresAt;
    private LongSupplier clock = System::currentTimeMillis;

    // Manager dependencies — populated in initialize() (production) or via test constructor
    private PageManager pageManager;
    private AclManager aclManager;
    private GroupManager groupManager;
    private UserManager userManager;

    /**
     * Constructs a new DefaultAuthorizationManager instance.
     * Required for SPI/reflection instantiation.
     */
    public DefaultAuthorizationManager() {
    }

    /**
     * Package-private constructor for unit testing — injects manager dependencies directly,
     * bypassing the SPI {@link #initialize(Engine, Properties)} path.
     */
    DefaultAuthorizationManager( final Engine engine,
                                 final PageManager pageManager,
                                 final AclManager aclManager,
                                 final GroupManager groupManager,
                                 final UserManager userManager ) {
        this.engine = engine;
        this.pageManager = pageManager;
        this.aclManager = aclManager;
        this.groupManager = groupManager;
        this.userManager = userManager;
    }

    /** Outcome of a pure (event-free) authorization evaluation. */
    private record Decision( boolean allowed, String reason ) {}

    /** {@inheritDoc} */
    @Override
    public boolean checkPermission( final Session session, final Permission permission ) {
        final Decision d = decide( session, permission );
        final Principal user = ( session == null ) ? null : session.getLoginPrincipal();
        if ( d.allowed() ) {
            fireEvent( WikiSecurityEvent.ACCESS_ALLOWED, user, permission );
        } else {
            fireEvent( WikiSecurityEvent.ACCESS_DENIED, user, permission, deniedAttributes( session, d.reason() ) );
        }
        return d.allowed();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPermitted( final Session session, final Permission permission ) {
        return decide( session, permission ).allowed();
    }

    /** {@inheritDoc}
     * <p>Fast path: one blanket {@code <wiki>:*} view check per call; pages with
     * no ACL are then viewable without any per-page policy evaluation. Pages
     * carrying an ACL (and callers without the blanket grant) fall through to
     * the exact same per-page {@link #isPermitted} decision as before.
     * <p><b>Correctness argument:</b> {@link #decide} allows a page iff
     * (AllPermission &or; bootstrap &or; static-grant) &and; (no-ACL &or; ACL-match).
     * {@code blanketView == true} means the session statically holds view on
     * {@code <wiki>:*}, which implies the static grant for every concrete page
     * (PagePermission implication), so for ACL-less pages the outcome is
     * "allowed" — identical to {@code decide()}. Every other combination falls
     * through to the unmodified per-page path. */
    @Override
    public Set< String > filterViewable( final Session session, final Collection< String > pageNames ) {
        final Set< String > out = new HashSet<>();
        if ( session == null ) {
            return out;
        }
        final boolean blanketView = isPermitted( session,
            new PagePermission( engine.getApplicationName() + ":*", "view" ) );
        for ( final String name : pageNames ) {
            final Page page = pageManager().getPage( name );
            if ( blanketView && page != null ) {
                final Acl acl = aclManager().getPermissions( page );
                if ( acl == null || acl.isEmpty() ) {
                    out.add( name );
                    continue;
                }
            }
            final Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, "view" )
                : new PagePermission( engine.getApplicationName() + ":" + name, "view" );
            if ( isPermitted( session, perm ) ) {
                out.add( name );
            }
        }
        return out;
    }

    /**
     * Pure authorization evaluation — no events fired. Single source of truth for both
     * {@link #checkPermission} (which fires + audits) and {@link #isPermitted} (silent).
     * Resolving unresolved ACL principals here is an intentional, idempotent side effect
     * preserved from the original checkPermission logic.
     */
    private Decision decide( final Session session, final Permission permission ) {
        if( session == null || permission == null ) {
            return new Decision( false, "no-session" );
        }
        if ( bootstrapAdmin != null && clock.getAsLong() < bootstrapExpiresAt ) {
            for ( final Principal p : session.getPrincipals() ) {
                if ( bootstrapAdmin.equals( p.getName() ) ) {
                    return new Decision( true, null );
                }
            }
        }
        final Permission allPermission = new AllPermission( engine.getApplicationName() );
        if( checkStaticPermission( session, allPermission ) ) {
            return new Decision( true, null );
        }
        if( !checkStaticPermission( session, permission ) ) {
            return new Decision( false, "policy-denied" );
        }
        if( !( permission instanceof PagePermission pagePerm ) ) {
            return new Decision( true, null );
        }
        final String pageName = pagePerm.getPage();
        final Page page = pageManager().getPage( pageName );
        final Acl acl = ( page == null ) ? null : aclManager().getPermissions( page );
        if( page == null || acl == null || acl.isEmpty() ) {
            return new Decision( true, null );
        }
        final Principal[] aclPrincipals = acl.findPrincipals( permission );
        for( Principal aclPrincipal : aclPrincipals ) {
            if ( aclPrincipal instanceof UnresolvedPrincipal unresolvedPrincipal ) {
                final AclEntry aclEntry = acl.getAclEntry( aclPrincipal );
                aclPrincipal = resolvePrincipal( unresolvedPrincipal.getName() );
                if ( aclEntry != null && !( aclPrincipal instanceof UnresolvedPrincipal ) ) {
                    aclEntry.setPrincipal( aclPrincipal );
                }
            }
            if ( hasRoleOrPrincipal( session, aclPrincipal ) ) {
                return new Decision( true, null );
            }
        }
        return new Decision( false, "acl-denied" );
    }

    /** Builds the {reason, authStatus, roles} attribute map attached to a denied event. */
    private static Map<String, String> deniedAttributes( final Session session, final String reason ) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put( "reason", reason );
        if ( session == null ) {
            m.put( "authStatus", "none" );
            m.put( "roles", "" );
        } else {
            m.put( "authStatus", session.getStatus() );
            m.put( "roles", Arrays.stream( session.getRoles() )
                .map( Principal::getName ).collect( Collectors.joining( "," ) ) );
        }
        return m;
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
        // Note: manager fields are populated lazily via getXxxManager() helpers
        // because AuthorizationManager is initialized before UserManager, GroupManager,
        // and AclManager in the WikiEngine boot sequence.
        LOG.info( "Initializing AuthorizationManager - anonymous access will be blocked until initialization completes" );

        //  JAAS authorization continues
        authorizer = getAuthorizerImplementation( properties );
        authorizer.initialize( engine, properties );

        // Initialize security policy — database-backed when wikantik.datasource is configured
        final String datasource = properties.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE );
        if ( datasource != null && !datasource.isBlank() ) {
            try {
                final javax.naming.Context initCtx = new javax.naming.InitialContext();
                final javax.naming.Context ctx = (javax.naming.Context) initCtx.lookup( "java:comp/env" );
                final DataSource policyDs = (DataSource) ctx.lookup( datasource );
                databasePolicy = new DatabasePolicy( policyDs, POLICY_TABLE );
                LOG.info( "Initialized database-backed security policy from JNDI DataSource: {}", datasource );
            } catch ( final Exception e ) {
                LOG.error( "Could not initialize database security policy: {}", e.getMessage() );
                throw new WikiException( "Could not initialize database security policy: " + e.getMessage(), e );
            }
        } else {
            // File-based policy fallback (used by unit tests without JNDI)
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
                LOG.error("Could not initialize local security policy: {}", e.getMessage() );
                throw new WikiException( "Could not initialize local security policy: " + e.getMessage(), e );
            }
        }

        // Bootstrap admin override
        final String adminProp = properties.getProperty( PROP_BOOTSTRAP_ADMIN );
        if ( adminProp != null && !adminProp.isBlank() ) {
            long maxAgeSeconds = DEFAULT_BOOTSTRAP_MAX_AGE_SECONDS;
            final String maxAgeStr = properties.getProperty( PROP_BOOTSTRAP_MAX_AGE );
            if ( maxAgeStr != null ) {
                try {
                    maxAgeSeconds = Long.parseLong( maxAgeStr.trim() );
                } catch ( final NumberFormatException e ) {
                    LOG.warn( "Invalid {} value '{}', using default {}",
                            PROP_BOOTSTRAP_MAX_AGE, maxAgeStr, DEFAULT_BOOTSTRAP_MAX_AGE_SECONDS );
                }
            }
            configureBootstrap( adminProp, maxAgeSeconds );
        } else {
            bootstrapAdmin = null;  // normalize blank to null
        }
    }

    /**
     * Activates the bootstrap admin override for {@code admin} for the next
     * {@code maxAgeSeconds}. A blank or null {@code admin} clears the override.
     * Package-private so tests can configure it without going through
     * {@link #initialize(Engine, Properties)}.
     */
    void configureBootstrap( final String admin, final long maxAgeSeconds ) {
        if ( admin == null || admin.isBlank() ) {
            this.bootstrapAdmin = null;
            this.bootstrapExpiresAt = 0L;
            return;
        }
        this.bootstrapAdmin = admin;
        this.bootstrapExpiresAt = clock.getAsLong() + maxAgeSeconds * 1000L;
        LOG.error( "CRITICAL: BOOTSTRAP ADMIN OVERRIDE IS ACTIVE — user '{}' has AllPermission "
                + "regardless of database grants. Override expires in {} seconds. "
                + "Remove the {} property for production use.",
                admin, maxAgeSeconds, PROP_BOOTSTRAP_ADMIN );
    }

    /**
     * Installs a custom clock source for deterministic tests of the bootstrap
     * override expiry. Package-private by design.
     */
    void setClock( final LongSupplier clock ) {
        this.clock = clock;
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
        return !PermissionChecks.isJSPWikiPermission( permission ) || Session.doPrivileged( session, ( PrivilegedAction< Boolean > )() -> {
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
        principal = groupManager().findRole( name );
        if ( principal != null ) {
            return principal;
        }

        // Ok, no luck---this must be a user principal
        final UserDatabase db = userManager().getUserDatabase();
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


    // --- Lazy accessors for manager dependencies ---
    // In the production SPI path, AuthorizationManager is initialized before
    // UserManager, GroupManager, and AclManager. These helpers resolve the
    // dependency lazily on first use and cache it for subsequent calls.
    // The test constructor populates the fields eagerly so the engine is never consulted.

    private PageManager pageManager() {
        if ( pageManager == null ) {
            pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
        }
        return pageManager;
    }

    private AclManager aclManager() {
        if ( aclManager == null ) {
            aclManager = com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).aclManager();
        }
        return aclManager;
    }

    private GroupManager groupManager() {
        if ( groupManager == null ) {
            groupManager = com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).groups();
        }
        return groupManager;
    }

    private UserManager userManager() {
        if ( userManager == null ) {
            userManager = com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).users();
        }
        return userManager;
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
