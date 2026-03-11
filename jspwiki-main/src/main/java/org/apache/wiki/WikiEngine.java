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
package org.apache.wiki;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.Release;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.engine.Initializable;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.exceptions.WikiException;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.auth.AuthenticationManager;
import org.apache.wiki.auth.AuthorizationManager;
import org.apache.wiki.auth.UserManager;
import org.apache.wiki.auth.acl.AclManager;
import org.apache.wiki.auth.authorize.GroupManager;
import org.apache.wiki.cache.CachingManager;
import org.apache.wiki.content.PageRenamer;
import org.apache.wiki.content.RecentArticlesManager;
import org.apache.wiki.diff.DifferenceManager;
import org.apache.wiki.event.WikiEngineEvent;
import org.apache.wiki.event.WikiEventListener;
import org.apache.wiki.event.WikiEventManager;
import org.apache.wiki.event.WikiPageEvent;
import org.apache.wiki.filters.FilterManager;
import org.apache.wiki.i18n.InternationalizationManager;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.plugin.PluginManager;
import org.apache.wiki.references.ReferenceManager;
import org.apache.wiki.render.RenderingManager;
import org.apache.wiki.search.SearchManager;
import org.apache.wiki.ui.CommandResolver;
import org.apache.wiki.ui.EditorManager;
import org.apache.wiki.ui.TemplateManager;
import org.apache.wiki.ui.admin.AdminBeanManager;
import org.apache.wiki.ui.progress.ProgressManager;
import org.apache.wiki.url.URLConstructor;
import org.apache.wiki.util.ClassUtil;
import org.apache.wiki.util.PropertyReader;
import org.apache.wiki.util.TextUtil;
import org.apache.wiki.variables.VariableManager;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 *  Main implementation for {@link Engine}.
 *
 *  <P>
 *  Using this class:  Always get yourself an instance from JSP page by using the {@code WikiEngine.getInstance(..)} method.  Never create
 *  a new WikiEngine() from scratch, unless you're writing tests.
 *
 *  <p>
 *  {@inheritDoc}
 */
public class WikiEngine implements Engine {

    private static final String ATTR_WIKIENGINE = "org.apache.wiki.WikiEngine";
    private static final Logger LOG = LogManager.getLogger( WikiEngine.class );

    /** Stores properties. */
    private Properties properties;

    /** Should the user info be saved with the page data as well? */
    private boolean saveUserInfo = true;

    /** If true, uses UTF8 encoding for all data */
    private boolean useUTF8 = true;

    /** Store the file path to the basic URL.  When we're not running as a servlet, it defaults to the user's current directory. */
    private String rootPath = System.getProperty( "user.dir" );

    /** Store the ServletContext that we're in.  This may be null if WikiEngine is not running inside a servlet container (i.e. when testing). */
    private ServletContext   servletContext;

    /** Stores the template path.  This is relative to "templates". */
    private String           templateDir;

    /** The default front page name.  Defaults to "Main". */
    private String           frontPage;

    /** The time when this engine was started. */
    private Date             startTime;

    /** The location where the work directory is. */
    private String           workDir;

    /** Each engine has their own application id. */
    private String           appid = "";

    /** engine is up and running or not */
    private boolean          isConfigured;

    /** Stores wikiengine attributes. */
    private final Map< String, Object > attributes = new ConcurrentHashMap<>();

    /** Stores WikiEngine's associated managers. */
    protected final Map< Class< ? >, Object > managers = new ConcurrentHashMap<>();

    /**
     *  Gets a WikiEngine related to this servlet.  Since this method is only called from JSP pages (and JspInit()) to be specific,
     *  we throw a RuntimeException if things don't work.
     *
     *  @param config The ServletConfig object for this servlet.
     *  @return A WikiEngine instance.
     *  @throws InternalWikiException in case something fails. This is a RuntimeException, so be prepared for it.
     */
    public static synchronized WikiEngine getInstance( final ServletConfig config ) throws InternalWikiException {
        return getInstance( config.getServletContext(), null );
    }

    /**
     *  Gets a WikiEngine related to the servlet. Works like getInstance(ServletConfig), but does not force the Properties object.
     *  This method is just an optional way of initializing a WikiEngine for embedded JSPWiki applications; normally, you
     *  should use getInstance(ServletConfig).
     *
     *  @param config The ServletConfig of the webapp servlet/JSP calling this method.
     *  @param props  A set of properties, or null, if we are to load JSPWiki's default jspwiki.properties (this is the usual case).
     *
     *  @return One well-behaving WikiEngine instance.
     */
    public static synchronized WikiEngine getInstance( final ServletConfig config, final Properties props ) {
        return getInstance( config.getServletContext(), props );
    }

    /**
     *  Gets a WikiEngine related to the servlet. Works just like getInstance( ServletConfig )
     *
     *  @param context The ServletContext of the webapp servlet/JSP calling this method.
     *  @param props  A set of properties, or null, if we are to load JSPWiki's default jspwiki.properties (this is the usual case).
     *  @return One fully functional, properly behaving WikiEngine.
     *  @throws InternalWikiException If the WikiEngine instantiation fails.
     */
    public static synchronized WikiEngine getInstance( final ServletContext context, Properties props ) throws InternalWikiException {
        WikiEngine engine = ( WikiEngine )context.getAttribute( ATTR_WIKIENGINE );
        if( engine == null ) {
            final String appid = Integer.toString( context.hashCode() );
            context.log( " Assigning new engine to " + appid );
            try {
                if( props == null ) {
                    props = PropertyReader.loadWebAppProps( context );
                }

                engine = new WikiEngine( context, appid );
                try {
                    //  Note: May be null, if JSPWiki has been deployed in a WAR file.
                    engine.start( props );
                    LOG.info( "Root path for this Wiki is: '{}'", engine.getRootPath() );
                } catch( final Exception e ) {
                    final String msg = Release.APPNAME + ": Unable to load and setup properties from jspwiki.properties. " + e.getMessage();
                    context.log( msg );
                    LOG.error( msg, e );
                    throw new WikiException( msg, e );
                }
                context.setAttribute( ATTR_WIKIENGINE, engine );
            } catch( final Exception e ) {
                context.log( "ERROR: Failed to create a Wiki engine: " + e.getMessage() );
                LOG.error( "ERROR: Failed to create a Wiki engine, stacktrace follows ", e );
                throw new InternalWikiException( "No wiki engine, check logs.", e );
            }
        }
        return engine;
    }

    /**
     *  Instantiate the WikiEngine using a given set of properties. Use this constructor for testing purposes only.
     *
     *  @param properties A set of properties to use to initialize this WikiEngine.
     *  @throws WikiException If the initialization fails.
     */
    public WikiEngine( final Properties properties ) throws WikiException {
        start( properties );
    }

    /**
     *  Instantiate using this method when you're running as a servlet and WikiEngine will figure out where to look for the property file.
     *  Do not use this method - use WikiEngine.getInstance() instead.
     *
     *  @param context A ServletContext.
     *  @param appid   An Application ID.  This application is a unique random string which is used to recognize this WikiEngine.
     *  @throws WikiException If the WikiEngine construction fails.
     */
    protected WikiEngine( final ServletContext context, final String appid ) throws WikiException {
        this.servletContext = context;
        this.appid          = appid;

        // Stash the WikiEngine in the servlet context
        if ( context != null ) {
            context.setAttribute( ATTR_WIKIENGINE,  this );
            rootPath = context.getRealPath( "/" );
        }
    }

    /**
     *  Does all the real initialization.
     */
    @Override
    public void initialize( final Properties props ) throws WikiException {
        startTime  = new Date();
        properties = props;

        LOG.info( "*******************************************" );
        LOG.info( "{} {} starting. Whee!", Release.APPNAME, Release.getVersionString() );
        LOG.debug( "Java version: {}", System.getProperty( "java.runtime.version" ) );
        LOG.debug( "Java vendor: {}", System.getProperty( "java.vm.vendor" ) );
        LOG.debug( "OS: {} {} {}", System.getProperty( "os.name" ), System.getProperty( "os.version" ), System.getProperty( "os.arch" ) );
        LOG.debug( "Default server locale: {}", Locale.getDefault() );
        LOG.debug( "Default server timezone: {}", TimeZone.getDefault().getDisplayName( true, TimeZone.LONG ) );

        if( servletContext != null ) {
            LOG.info( "Servlet container: {}", servletContext.getServerInfo() );
            if( servletContext.getMajorVersion() < 3 || ( servletContext.getMajorVersion() == 3 && servletContext.getMinorVersion() < 1 ) ) {
                throw new InternalWikiException( "JSPWiki requires a container which supports at least version 3.1 of Servlet specification" );
            }
        }

        fireEvent( WikiEngineEvent.INITIALIZING ); // begin initialization

        LOG.debug( "Configuring WikiEngine..." );

        createAndFindWorkingDirectory( props );

        useUTF8        = StandardCharsets.UTF_8.name().equals( TextUtil.getStringProperty( props, PROP_ENCODING, StandardCharsets.ISO_8859_1.name() ) );
        saveUserInfo   = TextUtil.getBooleanProperty( props, PROP_STOREUSERNAME, saveUserInfo );
        frontPage      = TextUtil.getStringProperty( props, PROP_FRONTPAGE, "Main" );
        templateDir    = TextUtil.getStringProperty( props, PROP_TEMPLATEDIR, "default" );
        enforceValidTemplateDirectory();

        //
        //  Initialize the important modules.  Any exception thrown by the managers means that we will not start up.
        //
        //  Initialization order matters due to dependencies between managers:
        //  - Phase 1: Core infrastructure (CommandResolver, URLConstructor, CachingManager)
        //  - Phase 2: Storage providers (PageManager, AttachmentManager) - depend on CachingManager
        //  - Phase 3: Utility and security managers - all run on main thread because:
        //      * Security managers (Auth*, UserManager, GroupManager) require JNDI context
        //      * UserManager eagerly initializes UserDatabase which needs JNDI for JDBCUserDatabase
        //      * Utility managers (Plugin, Difference, Variable, Search) are fast and don't benefit from parallelization
        //  - Phase 4: Dependent managers (Editor, Progress, Acl, Workflow, etc.)
        //  - Phase 5: RenderingManager (depends on FilterManager)
        //  - Phase 6: ReferenceManager - initialized asynchronously in background thread
        //      * This is the key optimization: ReferenceManager scans all pages which is expensive
        //      * Running it async allows the wiki to start serving requests immediately
        //
        try {
            final String aclClassName = properties.getProperty( PROP_ACL_MANAGER_IMPL, ClassUtil.getMappedClass( AclManager.class.getName() ).getName() );
            final String urlConstructorClassName = TextUtil.getStringProperty( props, PROP_URLCONSTRUCTOR, "DefaultURLConstructor" );
            final Class< URLConstructor > urlclass = ClassUtil.findClass( "org.apache.wiki.url", urlConstructorClassName );

            // Phase 1: Core infrastructure
            initComponent( CommandResolver.class, this, props );
            initComponent( urlclass.getName(), URLConstructor.class );
            initComponent( CachingManager.class, this, props );

            // Phase 2: Storage providers
            initComponent( PageManager.class, this, props );
            initComponent( AttachmentManager.class, this, props );

            // Phase 3: Utility managers and security managers (all on main thread)
            initComponent( PluginManager.class, this, props );
            initComponent( DifferenceManager.class, this, props );
            initComponent( VariableManager.class, props );
            initComponent( SearchManager.class, this, props );
            initComponent( AuthenticationManager.class );
            initComponent( AuthorizationManager.class );
            initComponent( UserManager.class );
            initComponent( GroupManager.class );

            // Phase 4: Managers that depend on earlier phases
            initComponent( EditorManager.class, this );
            initComponent( ProgressManager.class, this );
            initComponent( aclClassName, AclManager.class );
            initComponent( InternationalizationManager.class, this );
            initComponent( TemplateManager.class, this, props );
            initComponent( FilterManager.class, this, props );
            initComponent( AdminBeanManager.class, this );
            initComponent( PageRenamer.class, this, props );

            // Phase 5: RenderingManager depends on FilterManager events.
            initComponent( RenderingManager.class );

            // Phase 6: RecentArticlesManager for article listing APIs and plugins.
            initComponent( RecentArticlesManager.class );

            // Phase 7: ReferenceManager initialization is deferred to a background thread.
            // This significantly reduces startup time for large wikis. The ReferenceManager
            // will initialize lazily when first accessed, or in the background if not needed immediately.
            initReferenceManagerAsync();

            //  Hook the different manager routines into the system.
            getManager( FilterManager.class ).addPageFilter( getManager( ReferenceManager.class ), -1001 );
            getManager( FilterManager.class ).addPageFilter( getManager( SearchManager.class ), -1002 );
        } catch( final RuntimeException e ) {
            // RuntimeExceptions may occur here, even if they shouldn't.
            LOG.fatal( "Failed to start managers.", e );
            throw new WikiException( "Failed to start managers: " + e.getMessage(), e );
        } catch( final ClassNotFoundException e ) {
            LOG.fatal( "JSPWiki could not start, URLConstructor was not found: {}", e.getMessage(), e );
            throw new WikiException( e.getMessage(), e );
        } catch( final InstantiationException e ) {
            LOG.fatal( "JSPWiki could not start, URLConstructor could not be instantiated: {}", e.getMessage(), e );
            throw new WikiException( e.getMessage(), e );
        } catch( final IllegalAccessException e ) {
            LOG.fatal( "JSPWiki could not start, URLConstructor cannot be accessed: {}", e.getMessage(), e );
            throw new WikiException( e.getMessage(), e );
        } catch( final Exception e ) {
            // Final catch-all for everything
            LOG.fatal( "JSPWiki could not start, due to an unknown exception when starting.", e );
            throw new WikiException( "Failed to start. Caused by: " + e.getMessage() + "; please check log files for better information.", e );
        }

        final Map< String, String > extraComponents = ClassUtil.getExtraClassMappings();
        initExtraComponents( extraComponents );

        fireEvent( WikiEngineEvent.INITIALIZED ); // initialization complete

        LOG.info( "WikiEngine configured." );
        isConfigured = true;
    }

    void createAndFindWorkingDirectory( final Properties props ) throws WikiException {
        workDir = TextUtil.getStringProperty( props, PROP_WORKDIR, null );

        final File f = new File( workDir );
        try {
            f.mkdirs();
        } catch( final SecurityException e ) {
            LOG.fatal( "Unable to find or create the working directory: {}", workDir, e );
            throw new WikiException( "Unable to find or create the working dir: " + workDir, e );
        }

        //  A bunch of sanity checks
        checkWorkingDirectory( !f.exists(), "Work directory does not exist: " + workDir );
        checkWorkingDirectory( !f.canRead(), "No permission to read work directory: " + workDir );
        checkWorkingDirectory( !f.canWrite(), "No permission to write to work directory: " + workDir );
        checkWorkingDirectory( !f.isDirectory(), "jspwiki.workDir does not point to a directory: " + workDir );

        LOG.info( "JSPWiki working directory is '{}'", workDir );
    }

    void checkWorkingDirectory( final boolean condition, final String errMsg ) throws WikiException {
        if( condition ) {
            throw new WikiException( errMsg );
        }
    }

    void initExtraComponents( final Map< String, String > extraComponents ) {
        for( final Map.Entry< String, String > extraComponent : extraComponents.entrySet() ) {
            try {
                LOG.info( "Registering on WikiEngine {} as {}", extraComponent.getKey(), extraComponent.getValue() );
                initComponent( extraComponent.getKey(), Class.forName( extraComponent.getValue() ) );
            } catch( final Exception e ) {
                LOG.error( "Unable to start {}", extraComponent.getKey(), e );
            }
        }
    }

    < T > void initComponent( final Class< T > componentClass, final Object... initArgs ) throws Exception {
        initComponent( componentClass.getName(), componentClass, initArgs );
    }

    < T > void initComponent( final String componentInitClass, final Class< T > componentClass, final Object... initArgs ) throws Exception {
        final T component;
        if( initArgs == null || initArgs.length == 0 ) {
            component = ClassUtil.getMappedObject( componentInitClass );
        } else {
            component = ClassUtil.getMappedObject( componentInitClass, initArgs );
        }
        managers.put( componentClass, component );
        if( Initializable.class.isAssignableFrom( component.getClass() ) ) {
            ( ( Initializable )component ).initialize( this, properties );
        }
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T getManager( final Class< T > manager ) {
        return ( T )managers.entrySet().stream()
                                       .filter( e -> manager.isAssignableFrom( e.getKey() ) )
                                       .map( Map.Entry::getValue )
                                       .findFirst().orElse( null );
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > List< T > getManagers( final Class< T > manager ) {
        return ( List< T > )managers.entrySet().stream()
                                               .filter( e -> manager.isAssignableFrom( e.getKey() ) )
                                               .map( Map.Entry::getValue )
                                               .toList();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isConfigured() {
        return isConfigured;
    }

    /**
     * Checks if the template directory specified in the wiki's properties actually exists. If it doesn't, then {@code templateDir} is
     * set to {@link #DEFAULT_TEMPLATE_NAME}.
     * <p>
     * This checks the existence of the <tt>ViewTemplate.jsp</tt> file, which exists in every template using {@code servletContext.getRealPath("/")}.
     * <p>
     * {@code servletContext.getRealPath("/")} can return {@code null} on certain servers/conditions (f.ex, packed wars), an extra check
     * against {@code servletContext.getResource} is made.
     */
    void enforceValidTemplateDirectory() {
        if( servletContext != null ) {
            final String viewTemplate = "templates" + File.separator + getTemplateDir() + File.separator + "ViewTemplate.jsp";
            boolean exists = new File( servletContext.getRealPath( "/" ) + viewTemplate ).exists();
            if( !exists ) {
                try {
                    final URL url = servletContext.getResource( viewTemplate );
                    exists = url != null && StringUtils.isNotEmpty( url.getFile() );
                } catch( final MalformedURLException e ) {
                    LOG.warn( "template not found with viewTemplate {}", viewTemplate );
                }
            }
            if( !exists ) {
                LOG.warn( "{} template not found, updating WikiEngine's default template to {}", getTemplateDir(), DEFAULT_TEMPLATE_NAME );
                templateDir = DEFAULT_TEMPLATE_NAME;
            }
        }
    }

    /**
     *  Initializes the reference manager. Scans all existing WikiPages for
     *  internal links and adds them to the ReferenceManager object.
     *
     *  @throws WikiException If the reference manager initialization fails.
     */
    public void initReferenceManager() throws WikiException {
        try {
            // Build a new manager with default key lists.
            if( getManager( ReferenceManager.class ) == null ) {
                final var pages = new ArrayList< Page >();
                pages.addAll( getManager( PageManager.class ).getAllPages() );
                pages.addAll( getManager( AttachmentManager.class ).getAllAttachments() );
                final String refMgrClassName = properties.getProperty( PROP_REF_MANAGER_IMPL, ClassUtil.getMappedClass( ReferenceManager.class.getName() ).getName() );

                initComponent( refMgrClassName, ReferenceManager.class, this );

                getManager( ReferenceManager.class ).initialize( pages );
            }

        } catch( final ProviderException e ) {
            LOG.fatal( "PageProvider is unable to list pages: ", e );
        } catch( final Exception e ) {
            throw new WikiException( "Could not instantiate ReferenceManager: " + e.getMessage(), e );
        }
    }

    /**
     *  Initializes the reference manager asynchronously in a background thread.
     *  This allows the wiki to start serving requests immediately while the
     *  potentially time-consuming reference scan completes in the background.
     *  <p>
     *  The ReferenceManager is created immediately but its initialization
     *  (scanning all pages for references) is deferred. The manager handles
     *  this gracefully by returning empty/partial results until initialization
     *  completes.
     *
     *  @throws WikiException If the reference manager instantiation fails.
     */
    void initReferenceManagerAsync() throws WikiException {
        try {
            if( getManager( ReferenceManager.class ) == null ) {
                final String refMgrClassName = properties.getProperty( PROP_REF_MANAGER_IMPL, ClassUtil.getMappedClass( ReferenceManager.class.getName() ).getName() );

                // Create the ReferenceManager instance immediately so it can be registered
                initComponent( refMgrClassName, ReferenceManager.class, this );

                // Defer the expensive initialization to a background thread
                CompletableFuture.runAsync( () -> {
                    try {
                        final var pages = new ArrayList< Page >();
                        pages.addAll( getManager( PageManager.class ).getAllPages() );
                        pages.addAll( getManager( AttachmentManager.class ).getAllAttachments() );
                        getManager( ReferenceManager.class ).initialize( pages );
                    } catch( final ProviderException e ) {
                        LOG.error( "Background ReferenceManager initialization failed: {}", e.getMessage(), e );
                    }
                } );
            }
        } catch( final Exception e ) {
            throw new WikiException( "Could not instantiate ReferenceManager: " + e.getMessage(), e );
        }
    }

    /** {@inheritDoc} */
    @Override
    public Properties getWikiProperties() {
        return properties;
    }

    /** {@inheritDoc} */
    @Override
    public String getWorkDir() {
        return workDir;
    }

    /** {@inheritDoc} */
    @Override
    public String getTemplateDir() {
        return templateDir;
    }

    /** {@inheritDoc} */
    @Override
    public Date getStartTime() {
        return ( Date )startTime.clone();
    }

    /** {@inheritDoc} */
    @Override
    public String getBaseURL() {
        return servletContext.getContextPath();
    }

    /** {@inheritDoc} */
    @Override
    public String getInterWikiURL( final String wikiName ) {
        return TextUtil.getStringProperty( properties,PROP_INTERWIKIREF + wikiName,null );
    }

    /** {@inheritDoc} */
    @Override
    public String getURL( final String context, String pageName, final String params ) {
        if( pageName == null ) {
            pageName = getFrontPage();
        }
        final URLConstructor urlConstructor = getManager( URLConstructor.class );
        return urlConstructor.makeURL( context, pageName, params );
    }

    /** {@inheritDoc} */
    @Override
    public String getFrontPage() {
        return frontPage;
    }

    /** {@inheritDoc} */
    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    /** {@inheritDoc} */
    @Override
    public Collection< String > getAllInterWikiLinks() {
        final var list = new ArrayList< String >();
        for( final Enumeration< ? > i = properties.propertyNames(); i.hasMoreElements(); ) {
            final String prop = ( String )i.nextElement();
            if( prop.startsWith( PROP_INTERWIKIREF ) ) {
                list.add( prop.substring( prop.lastIndexOf( "." ) + 1 ) );
            }
        }

        return list;
    }

    /** {@inheritDoc} */
    @Override
    public Collection< String > getAllInlinedImagePatterns() {
        final var ptrnlist = new ArrayList< String >();
        for( final Enumeration< ? > e = properties.propertyNames(); e.hasMoreElements(); ) {
            final String name = ( String )e.nextElement();
            if( name.startsWith( PROP_INLINEIMAGEPTRN ) ) {
                ptrnlist.add( TextUtil.getStringProperty( properties, name, null ) );
            }
        }

        if( ptrnlist.isEmpty() ) {
            ptrnlist.add( DEFAULT_INLINEPATTERN );
        }

        return ptrnlist;
    }

    /** {@inheritDoc} */
    @Override
    public String getSpecialPageReference( final String original ) {
        return getManager( CommandResolver.class ).getSpecialPageReference( original );
    }

    /** {@inheritDoc} */
    @Override
    public String getApplicationName() {
        final String appName = TextUtil.getStringProperty( properties, PROP_APPNAME, Release.APPNAME );
        return TextUtil.cleanString( appName, TextUtil.PUNCTUATION_CHARS_ALLOWED );
    }

    /** {@inheritDoc} */
    @Override
    public String getFinalPageName( final String page ) throws ProviderException {
        return getManager( CommandResolver.class ).getFinalPageName( page );
    }

    /** {@inheritDoc} */
    @Override
    public String encodeName( final String pagename ) {
        try {
            return URLEncoder.encode( pagename, useUTF8 ? StandardCharsets.UTF_8.name() : StandardCharsets.ISO_8859_1.name() );
        } catch( final UnsupportedEncodingException e ) {
            throw new InternalWikiException( "ISO-8859-1 not a supported encoding!?!  Your platform is borked." , e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String decodeName( final String pagerequest ) {
        try {
            return URLDecoder.decode( pagerequest, useUTF8 ? StandardCharsets.UTF_8.name() : StandardCharsets.ISO_8859_1.name() );
        } catch( final UnsupportedEncodingException e ) {
            throw new InternalWikiException("ISO-8859-1 not a supported encoding!?!  Your platform is borked.", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Charset getContentEncoding() {
        if( useUTF8 ) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.ISO_8859_1;
    }

    /**
     * {@inheritDoc}
     * <p>It is called by {@link WikiServlet#destroy()}. When this method is called, it fires a "shutdown" WikiEngineEvent to
     * all registered listeners.
     */
    @Override
    public void shutdown() {
        fireEvent( WikiEngineEvent.SHUTDOWN );
        getManager( CachingManager.class ).shutdown();
        getManager( FilterManager.class ).destroy();
        WikiEventManager.shutdown();
    }

    /** {@inheritDoc} */
    @Override
    public String getRootPath() {
        return rootPath;
    }

    /** {@inheritDoc} */
    @Override
    public final synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /** {@inheritDoc} */
    @Override
    public final synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

    /**
     * Fires a WikiEngineEvent to all registered listeners.
     *
     * @param type  the event type
     */
    protected final void fireEvent( final int type ) {
        if( WikiEventManager.isListening(this ) ) {
            WikiEventManager.fireEvent( this, new WikiEngineEvent(this, type ) );
        }
    }

    /**
     * Fires a WikiPageEvent to all registered listeners.
     *
     * @param type  the event type
     */
    protected final void firePageEvent( final int type, final String pageName ) {
        if( WikiEventManager.isListening(this ) ) {
            WikiEventManager.fireEvent(this,new WikiPageEvent(this, type, pageName ) );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setAttribute( final String key, final Object value ) {
        attributes.put( key, value );
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T getAttribute( final String key ) {
        return ( T )attributes.get( key );
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T removeAttribute( final String key ) {
        return ( T )attributes.remove( key );
    }

}
