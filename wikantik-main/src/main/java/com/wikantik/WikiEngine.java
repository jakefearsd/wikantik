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
package com.wikantik;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.Release;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.engine.Initializable;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.blog.BlogManager;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.diff.DifferenceManager;
import com.wikantik.event.WikiEngineEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.plugin.PluginManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.render.RenderingManager;
import com.wikantik.search.LuceneSearchProvider;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.HubDiscoveryRepository;
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.structure.ConfidenceComputer;
import com.wikantik.knowledge.structure.DefaultStructuralIndexService;
import com.wikantik.knowledge.structure.PageCanonicalIdsDao;
import com.wikantik.knowledge.structure.PageRelationsDao;
import com.wikantik.knowledge.structure.PageVerificationDao;
import com.wikantik.knowledge.structure.StructuralIndexEventListener;
import com.wikantik.knowledge.structure.StructuralIndexMetrics;
import com.wikantik.knowledge.structure.StructuralSpinePageFilter;
import com.wikantik.knowledge.structure.TrustedAuthorsDao;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.knowledge.HubProposalRepository;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.knowledge.KnowledgeGraphServiceFactory;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.progress.ProgressManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.PropertyReader;
import com.wikantik.util.TextUtil;
import com.wikantik.variables.VariableManager;

import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.google.inject.ProvisionException;
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
import java.util.concurrent.ConcurrentHashMap;


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
@SuppressWarnings( { "PMD.SingleMethodSingleton", "PMD.SingletonClassReturningNewInstance" } )
// WikiEngine is one-per-ServletContext, not a global singleton: getInstance(...) overloads look up or create the
// engine keyed on the context, and the "new" call PMD flags only runs when no engine is cached for that context.
public class WikiEngine implements Engine {

    private static final String ATTR_WIKIENGINE = "com.wikantik.WikiEngine";
    private static final Logger LOG = LogManager.getLogger( WikiEngine.class );

    /** Stores properties. */
    private Properties properties;

    /** Should the user info be saved with the page data as well? */
    private boolean saveUserInfo = true;

    /** If true, uses UTF8 encoding for all data */
    private volatile boolean useUTF8 = true;

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

    /** engine is up and running or not */
    private volatile boolean isConfigured;

    /** Stores wikiengine attributes. */
    private final Map< String, Object > attributes = new ConcurrentHashMap<>();

    /** Stores WikiEngine's associated managers. */
    protected final Map< Class< ? >, Object > managers = new ConcurrentHashMap<>();

    /** Guice injector for modern dependency management. */
    private Injector injector;

    /** Hybrid-retrieval lifecycle handles; null when hybrid is disabled. */
    private com.wikantik.search.embedding.AsyncEmbeddingIndexListener hybridIndexListener;
    private com.wikantik.search.hybrid.QueryEmbedder hybridQueryEmbedder;
    private com.wikantik.search.embedding.BootstrapEmbeddingIndexer hybridBootstrapIndexer;

    /** Entity-extraction lifecycle handle; null when the extractor is disabled. */
    private com.wikantik.knowledge.extraction.AsyncEntityExtractionListener entityExtractionListener;

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
     *  @param props  A set of properties, or null, if we are to load JSPWiki's default wikantik.properties (this is the usual case).
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
     *  @param props  A set of properties, or null, if we are to load JSPWiki's default wikantik.properties (this is the usual case).
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
                    final String msg = Release.APPNAME + ": Unable to load and setup properties from wikantik.properties. " + e.getMessage();
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
    public final void initialize( final Properties props ) throws WikiException {
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
            final Class< URLConstructor > urlclass = ClassUtil.findClass( "com.wikantik.url", urlConstructorClassName );

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
            initComponent( ProgressManager.class, this );
            initComponent( aclClassName, AclManager.class );
            initComponent( InternationalizationManager.class, this );
            initComponent( FilterManager.class, this, props );
            initComponent( PageRenamer.class, this, props );

            // Phase 5: RenderingManager depends on FilterManager events.
            initComponent( RenderingManager.class );

            // Phase 6: SystemPageRegistry discovers template/system pages from classpath.
            initComponent( SystemPageRegistry.class );

            // Phase 7: RecentArticlesManager for article listing APIs and plugins.
            initComponent( RecentArticlesManager.class );

            // Phase 7b: BlogManager for user blog lifecycle and plugins.
            initComponent( BlogManager.class );

            // Phase 8: ReferenceManager scans all pages for cross-references.
            initReferenceManager();

            // Frontmatter metadata cache used by the search response path so we
            // don't re-read and re-parse every result on every /api/search call.
            // Keyed on (pageName, lastModified) so a page edit naturally invalidates.
            managers.put( com.wikantik.search.FrontmatterMetadataCache.class,
                new com.wikantik.search.FrontmatterMetadataCache( getManager( PageManager.class ) ) );

            //  Hook the different manager routines into the system.
            getManager( FilterManager.class ).addPageFilter( getManager( ReferenceManager.class ), -1001 );
            getManager( FilterManager.class ).addPageFilter( getManager( SearchManager.class ), -1002 );

            // Phase 9: Knowledge graph (optional — requires datasource configuration)
            initKnowledgeGraph( props );
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

        final File workDirFile = new File( workDir );
        try {
            workDirFile.mkdirs();
        } catch( final SecurityException e ) {
            LOG.fatal( "Unable to find or create the working directory: {}", workDir, e );
            throw new WikiException( "Unable to find or create the working dir: " + workDir, e );
        }

        //  A bunch of sanity checks
        checkWorkingDirectory( !workDirFile.exists(), "Work directory does not exist: " + workDir );
        checkWorkingDirectory( !workDirFile.canRead(), "No permission to read work directory: " + workDir );
        checkWorkingDirectory( !workDirFile.canWrite(), "No permission to write to work directory: " + workDir );
        checkWorkingDirectory( !workDirFile.isDirectory(), "wikantik.workDir does not point to a directory: " + workDir );

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
        // 1. Try Guice first
        try {
            if( injector != null ) {
                return injector.getInstance( manager );
            }
        } catch( final ConfigurationException | ProvisionException e ) {
            // Not bound in Guice or failed to provision, fall back to legacy map
            LOG.trace( "Manager {} not found in Guice, falling back to legacy map", manager.getName() );
        }

        // 2. Fallback to legacy manual map
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
    public < T > void setManager( final Class< T > clazz, final T manager ) {
        managers.put( clazz, manager );
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
     * Initialises the knowledge-graph subsystem when a JNDI datasource is configured.
     *
     * @param props engine properties
     */
    private void initKnowledgeGraph( final Properties props ) {
        final String datasource = props.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE,
                AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        try {
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
            final javax.sql.DataSource ds = ( javax.sql.DataSource ) ctx.lookup( datasource );

            // Resolve the Lucene MoreLikeThis seam if SearchManager is using a Lucene
            // provider. Otherwise the factory falls back to a no-op MLT and the
            // hub-overview drilldown's MLT section stays empty.
            HubOverviewService.LuceneMlt luceneMlt = null;
            final SearchManager searchMgr = getManager( SearchManager.class );
            if ( searchMgr != null ) {
                final SearchProvider sp = searchMgr.getSearchEngine();
                if ( sp instanceof LuceneSearchProvider lsp ) {
                    luceneMlt = ( seed, max, excludes ) -> {
                        final var hits = lsp.moreLikeThis( seed, max, excludes );
                        final java.util.List< HubOverviewService.MoreLikeThisLucene > out =
                            new java.util.ArrayList<>( hits.size() );
                        for ( final var h : hits ) {
                            out.add( new HubOverviewService.MoreLikeThisLucene( h.name(), h.score() ) );
                        }
                        return out;
                    };
                }
            }

            // Resolve the Prometheus MeterRegistry installed by the observability
            // extension in its onInit phase. When present (the production path),
            // chunker / rebuild metrics flow to /observability/metrics. When
            // absent (unusual — e.g. test harnesses that skip Engine.start()),
            // the chunker and rebuild service each fall back to an in-process
            // SimpleMeterRegistry and we log a WARN so the gap is visible.
            final io.micrometer.core.instrument.MeterRegistry meterRegistry =
                com.wikantik.api.observability.MeterRegistryHolder.get();
            if ( meterRegistry == null ) {
                LOG.warn( "No shared MeterRegistry installed — ChunkProjector and "
                        + "ContentIndexRebuildService will publish metrics to a "
                        + "local SimpleMeterRegistry that is NOT scraped at "
                        + "/observability/metrics. Check that ObservabilityLifecycleExtension "
                        + "is on the classpath and that onInit has run." );
            }

            final KnowledgeGraphServiceFactory.Services svcs = KnowledgeGraphServiceFactory.create(
                ds, props,
                getManager( SystemPageRegistry.class ),
                getManager( PageManager.class ),
                new PageSaveHelper( this ),
                luceneMlt,
                meterRegistry );

            // Inject engine reference for graph visualization ACL checks.
            if ( svcs.kgService() instanceof DefaultKnowledgeGraphService dkgs ) {
                dkgs.setEngine( this );
            }

            // Register services with the engine's manager map.
            managers.put( KnowledgeGraphService.class, svcs.kgService() );
            managers.put( NodeMentionSimilarity.class, svcs.nodeMentionSimilarity() );
            managers.put( com.wikantik.knowledge.MentionIndex.class, svcs.mentionIndex() );
            managers.put( HubProposalRepository.class, svcs.hubProposalRepo() );
            managers.put( HubProposalService.class, svcs.hubProposalService() );
            managers.put( HubDiscoveryRepository.class, svcs.hubDiscoveryRepo() );
            managers.put( HubDiscoveryService.class, svcs.hubDiscoveryService() );
            managers.put( HubOverviewService.class, svcs.hubOverviewService() );
            managers.put( com.wikantik.knowledge.chunking.ChunkProjector.class, svcs.chunkProjector() );
            managers.put( com.wikantik.knowledge.chunking.ContentChunkRepository.class, svcs.contentChunkRepo() );

            // Structural spine — observe-only Phase 1. Builds an in-memory projection
            // of wiki shape (clusters, tags, types, canonical_ids) over every page.
            // Page-save events trigger incremental rebuilds; bootstrap rebuild runs
            // in the background so Engine.start() does not block on a ~1000-page scan.
            final PageCanonicalIdsDao canonicalIdsDao = new PageCanonicalIdsDao( ds );
            final PageRelationsDao pageRelationsDao = new PageRelationsDao( ds );
            final PageVerificationDao pageVerificationDao = new PageVerificationDao( ds );
            final TrustedAuthorsDao trustedAuthorsDao = new TrustedAuthorsDao( ds );
            final int staleDays = TextUtil.getIntegerProperty( props,
                "wikantik.verification.stale_days", ConfidenceComputer.DEFAULT_STALE_DAYS );
            final ConfidenceComputer confidenceComputer =
                new ConfidenceComputer( trustedAuthorsDao::contains, staleDays );
            final StructuralIndexMetrics structuralMetrics = StructuralIndexMetrics.resolveAndBind();
            final DefaultStructuralIndexService structuralIndex =
                new DefaultStructuralIndexService(
                    getManager( PageManager.class ), canonicalIdsDao, pageRelationsDao,
                    pageVerificationDao, confidenceComputer, structuralMetrics );
            managers.put( PageVerificationDao.class, pageVerificationDao );
            managers.put( TrustedAuthorsDao.class, trustedAuthorsDao );
            managers.put( StructuralIndexService.class, structuralIndex );
            new StructuralIndexEventListener( structuralIndex )
                .register( getManager( PageManager.class ) );
            new Thread( structuralIndex::rebuild, "structural-index-bootstrap" ).start();
            LOG.info( "StructuralIndexService registered; initial rebuild dispatched" );

            // Agent-Grade Content Phase 2: token-budgeted /for-agent projection.
            // Reads from the structural index + PageManager, memoises in CACHE_FOR_AGENT,
            // emits wikantik_for_agent_response_bytes histogram.
            final com.wikantik.knowledge.agent.ForAgentMetrics forAgentMetrics =
                com.wikantik.knowledge.agent.ForAgentMetrics.resolveAndBind();
            final com.wikantik.knowledge.agent.DefaultForAgentProjectionService forAgentService =
                new com.wikantik.knowledge.agent.DefaultForAgentProjectionService(
                    structuralIndex,
                    getManager( PageManager.class ),
                    getManager( CachingManager.class ),
                    forAgentMetrics );
            managers.put( com.wikantik.api.agent.ForAgentProjectionService.class, forAgentService );
            LOG.info( "ForAgentProjectionService registered" );

            // Content rebuild orchestrator — singleton wired against the live Lucene
            // provider (if any) so an admin-triggered rebuild can enqueue pages,
            // observe queue depth, and wipe the index without leaving the filesystem
            // in a half-broken state. When Lucene is disabled/unsupported we skip
            // wiring the service so the admin UI surfaces a clean "not available"
            // instead of NPE'ing; the rest of the knowledge graph still starts.
            com.wikantik.admin.ContentIndexRebuildService rebuildService = null;
            if ( searchMgr != null && searchMgr.getSearchEngine() instanceof LuceneSearchProvider lsp ) {
                final com.wikantik.admin.LuceneReindexQueue queue =
                    new com.wikantik.admin.LuceneSearchProviderAdapter( lsp );
                final com.wikantik.knowledge.chunking.ContentChunker rebuildChunker =
                    new com.wikantik.knowledge.chunking.ContentChunker(
                        new com.wikantik.knowledge.chunking.ContentChunker.Config(
                            TextUtil.getIntegerProperty( props, "wikantik.chunker.max_tokens", 512 ),
                            TextUtil.getIntegerProperty( props, "wikantik.chunker.merge_forward_tokens", 150 ) ) );
                rebuildService =
                    meterRegistry != null
                        ? new com.wikantik.admin.ContentIndexRebuildService(
                            getManager( PageManager.class ),
                            getManager( SystemPageRegistry.class ),
                            queue,
                            svcs.contentChunkRepo(),
                            rebuildChunker,
                            () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                            TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ),
                            meterRegistry )
                        : new com.wikantik.admin.ContentIndexRebuildService(
                            getManager( PageManager.class ),
                            getManager( SystemPageRegistry.class ),
                            queue,
                            svcs.contentChunkRepo(),
                            rebuildChunker,
                            () -> TextUtil.getBooleanProperty( props, "wikantik.rebuild.enabled", true ),
                            TextUtil.getIntegerProperty( props, "wikantik.rebuild.lucene_drain_poll_ms", 2000 ) );
                managers.put( com.wikantik.admin.ContentIndexRebuildService.class, rebuildService );
                LOG.info( "ContentIndexRebuildService registered" );
            } else {
                LOG.info( "ContentIndexRebuildService NOT registered — no LuceneSearchProvider in use" );
            }

            wireHybridRetrieval( props, ds, svcs.chunkProjector(), rebuildService );
            wireEntityExtraction( props, ds, svcs.chunkProjector(), svcs.contentChunkRepo() );
            wireGraphRerank( props, ds );
            wireRetrievalQualityRunner( props, ds, structuralIndex );

            // Register filters (priority order preserved; higher priority runs first).
            // ChunkProjector at -1005 is the active save-time chunker for the
            // embedding / entity-extraction pipelines.
            final FilterManager filterManager = getManager( FilterManager.class );
            filterManager.addPageFilter( svcs.chunkProjector(), -1005 );
            filterManager.addPageFilter( svcs.frontmatterDefaultsFilter(), -1004 );
            // -1003 — runs after frontmatter defaulting so the page already has a
            // frontmatter block, but before chunking and hub sync so the
            // canonical_id this filter assigns is visible to downstream filters.
            filterManager.addPageFilter(
                new StructuralSpinePageFilter( structuralIndex,
                    name -> {
                        final SystemPageRegistry sys = getManager( SystemPageRegistry.class );
                        return sys != null && sys.isSystemPage( name );
                    },
                    props ),
                -1003 );
            // Phase 3: schema-validate type: runbook frontmatter at save time.
            // Same priority band as the structural-spine filter — both validate
            // frontmatter; both reject invalid saves with FilterException.
            filterManager.addPageFilter(
                new com.wikantik.knowledge.agent.RunbookValidationPageFilter(
                    structuralIndex, getManager( PageManager.class ), props ),
                -1003 );
            filterManager.addPageFilter( svcs.hubSyncFilter(), -999 );

            LOG.info( "HubProposalService registered (reviewPercentile property='{}')",
                props.getProperty( HubProposalService.PROP_REVIEW_PERCENTILE, "default" ) );
            LOG.info( "HubDiscoveryService registered (minClusterSize property='{}', minPts='{}')",
                props.getProperty( HubDiscoveryService.PROP_MIN_CLUSTER_SIZE, "default" ),
                props.getProperty( HubDiscoveryService.PROP_MIN_PTS, "default" ) );
            LOG.info( "Knowledge graph initialized with datasource '{}'", datasource );
        } catch ( final javax.naming.NamingException | RuntimeException e ) {
            // Log with the throwable so the stack trace is visible — partial init failures
            // previously hid behind a one-line warn and caused downstream managers like
            // HubProposalService to be silently null. Checked JNDI NamingException + any
            // runtime failure from service construction are both recoverable (the engine
            // continues to start; only KG-dependent features go offline).
            LOG.warn( "Knowledge graph initialization failed: {}", e.getMessage(), e );
        }
    }

    /**
     * Wires the Phase 5 hybrid-retrieval infrastructure: embedding client,
     * batch indexer, async listener on {@code ChunkProjector}, in-memory vector
     * index, and the query-side {@link com.wikantik.search.hybrid.QueryEmbedder}.
     * Every wiring step is flag-gated — when
     * {@link com.wikantik.search.embedding.EmbeddingConfig#PROP_ENABLED} is
     * {@code false} the factory returns {@link java.util.Optional#empty()} and
     * this method is a no-op, guaranteeing zero background cost for deployments
     * that have not opted in.
     */
    @SuppressWarnings( "PMD.CloseResource" ) // AsyncEmbeddingIndexListener / BootstrapEmbeddingIndexer are stored as managers; their lifecycles follow the engine shutdown.
    private void wireHybridRetrieval( final Properties props,
                                      final javax.sql.DataSource ds,
                                      final com.wikantik.knowledge.chunking.ChunkProjector chunkProjector,
                                      final com.wikantik.admin.ContentIndexRebuildService rebuildService ) {
        final com.wikantik.search.embedding.EmbeddingConfig cfg;
        try {
            cfg = com.wikantik.search.embedding.EmbeddingConfig.fromProperties( props );
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "Invalid embedding configuration; hybrid retrieval disabled: {}", e.getMessage() );
            return;
        }
        final java.util.Optional< com.wikantik.search.embedding.TextEmbeddingClient > clientOpt =
            com.wikantik.search.embedding.EmbeddingClientFactory.create( cfg );
        if ( clientOpt.isEmpty() ) {
            // Master flag off — nothing to wire.
            return;
        }
        final com.wikantik.search.embedding.TextEmbeddingClient client = clientOpt.get();
        final String modelCode = cfg.model().code();

        final com.wikantik.search.embedding.EmbeddingIndexService indexService =
            new com.wikantik.search.embedding.EmbeddingIndexService( ds, client, cfg.batchSize() );
        managers.put( com.wikantik.search.embedding.EmbeddingIndexService.class, indexService );

        // In-memory vector index loads current embeddings at construction time.
        // An empty table is fine — the index reloads after each indexing batch.
        final com.wikantik.search.hybrid.InMemoryChunkVectorIndex vectorIndex;
        try {
            vectorIndex = new com.wikantik.search.hybrid.InMemoryChunkVectorIndex( ds, modelCode );
        } catch( final RuntimeException e ) {
            LOG.warn( "Failed to initialize InMemoryChunkVectorIndex (model={}); "
                + "hybrid retrieval disabled: {}", modelCode, e.getMessage(), e );
            return;
        }
        managers.put( com.wikantik.search.hybrid.ChunkVectorIndex.class, vectorIndex );
        managers.put( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class, vectorIndex );

        // Async listener that reindexes per-page saves and refreshes the vector
        // index snapshot afterward. postIndexCallback failures must not cascade.
        final com.wikantik.search.embedding.AsyncEmbeddingIndexListener listener =
            new com.wikantik.search.embedding.AsyncEmbeddingIndexListener( indexService, modelCode );
        listener.setPostIndexCallback( vectorIndex::upsertChunks );
        chunkProjector.setPostChunkSink( listener );
        this.hybridIndexListener = listener;

        // Rebuild-path hook: after a full content rebuild, walk all chunks and
        // upsert embeddings via EmbeddingIndexService.indexAll(modelCode).
        if ( rebuildService != null ) {
            rebuildService.setEmbeddingHook( indexService, modelCode );
        }

        // Query-side wrapper with cache + timeout + circuit breaker.
        final com.wikantik.search.hybrid.QueryEmbedderConfig qeCfg =
            com.wikantik.search.hybrid.QueryEmbedderConfig.fromProperties( props );
        final com.wikantik.search.hybrid.QueryEmbedder embedder =
            new com.wikantik.search.hybrid.QueryEmbedder( client, qeCfg, java.time.Clock.systemUTC() );
        managers.put( com.wikantik.search.hybrid.QueryEmbedder.class, embedder );
        this.hybridQueryEmbedder = embedder;

        // Retrieval-side orchestrator: embed the query, run dense retrieval,
        // fuse with BM25 via RRF. Fails closed to BM25-only when the embedding
        // backend is unavailable. Reads the winner eval defaults directly so a
        // fresh install gets the best-measured config with zero knobs.
        final com.wikantik.search.hybrid.HybridConfig hybridCfg;
        try {
            hybridCfg = com.wikantik.search.hybrid.HybridConfig.fromProperties( props );
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "Invalid hybrid retrieval configuration; hybrid search disabled: {}", e.getMessage() );
            LOG.info( "Hybrid retrieval wired (embedding-only; search path NOT enabled)" );
            return;
        }
        final com.wikantik.search.hybrid.DenseRetriever denseRetriever =
            new com.wikantik.search.hybrid.DenseRetriever( vectorIndex,
                hybridCfg.pageAggregation(), hybridCfg.denseChunkTop(), hybridCfg.densePageTop() );
        final com.wikantik.search.hybrid.HybridFuser fuser =
            new com.wikantik.search.hybrid.HybridFuser( hybridCfg.rrfK(),
                hybridCfg.bm25Weight(), hybridCfg.denseWeight(), hybridCfg.rrfTruncate() );
        final com.wikantik.search.hybrid.HybridSearchService hybridSearch =
            new com.wikantik.search.hybrid.HybridSearchService( embedder, denseRetriever, fuser,
                hybridCfg.enabled() );
        managers.put( com.wikantik.search.hybrid.HybridSearchService.class, hybridSearch );

        // One-shot bootstrap: if the embeddings table is empty for this model,
        // kick off indexAll(modelCode) on a background thread. Idempotent —
        // safe to call on every engine init; it latches after the first run.
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer bootstrap =
            new com.wikantik.search.embedding.BootstrapEmbeddingIndexer(
                ds, indexService, modelCode, vectorIndex::reload );
        managers.put( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class, bootstrap );
        this.hybridBootstrapIndexer = bootstrap;
        try {
            bootstrap.startIfNeeded();
        } catch( final RuntimeException e ) {
            LOG.warn( "Embedding bootstrap start failed (model={}): {}", modelCode, e.getMessage(), e );
        }

        com.wikantik.search.hybrid.HybridMetricsBridge.register(
            com.wikantik.api.observability.MeterRegistryHolder.get(),
            embedder, bootstrap, vectorIndex );

        LOG.info( "Hybrid retrieval wired (model={}, backend={}, vectorIndex.size={})",
            modelCode, cfg.backend(), vectorIndex.size() );
    }

    /**
     * Wires the Phase 2 entity-extraction pipeline: extractor backend (Claude
     * or Ollama), async listener on {@code ChunkProjector}'s post-chunk sink,
     * and the mention repository. Opt-in via
     * {@code wikantik.knowledge.extractor.backend=claude|ollama|disabled}
     * (default {@code disabled}) — a fresh deploy emits no proposals until an
     * operator flips the flag.
     */
    @SuppressWarnings( "PMD.CloseResource" ) // Listener is stored as a field; lifecycle follows engine shutdown.
    private void wireEntityExtraction( final Properties props,
                                       final javax.sql.DataSource ds,
                                       final com.wikantik.knowledge.chunking.ChunkProjector chunkProjector,
                                       final com.wikantik.knowledge.chunking.ContentChunkRepository contentChunkRepo ) {
        final com.wikantik.knowledge.extraction.EntityExtractorConfig extractorCfg =
            com.wikantik.knowledge.extraction.EntityExtractorConfig.fromProperties( props );
        if ( !extractorCfg.enabled() ) {
            LOG.info( "Entity extraction disabled (wikantik.knowledge.extractor.backend=disabled)" );
            return;
        }
        final java.util.Optional< com.wikantik.api.knowledge.EntityExtractor > extractorOpt =
            com.wikantik.knowledge.extraction.EntityExtractorFactory.create( extractorCfg );
        if ( extractorOpt.isEmpty() ) {
            LOG.warn( "Entity extraction configured ({}), but no usable backend; skipping wiring",
                      extractorCfg.backend() );
            return;
        }
        final com.wikantik.knowledge.extraction.ChunkEntityMentionRepository mentionRepo =
            new com.wikantik.knowledge.extraction.ChunkEntityMentionRepository( ds );
        final com.wikantik.knowledge.JdbcKnowledgeRepository kgRepo =
            new com.wikantik.knowledge.JdbcKnowledgeRepository( ds );
        final io.micrometer.core.instrument.MeterRegistry meter =
            io.micrometer.core.instrument.Metrics.globalRegistry;

        final com.wikantik.knowledge.extraction.AsyncEntityExtractionListener listener =
            new com.wikantik.knowledge.extraction.AsyncEntityExtractionListener(
                extractorOpt.get(), extractorCfg, contentChunkRepo, mentionRepo, kgRepo, meter );
        this.entityExtractionListener = listener;
        managers.put( com.wikantik.knowledge.extraction.ChunkEntityMentionRepository.class, mentionRepo );
        managers.put( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class, listener );

        // Admin-triggered full-corpus extraction: re-runs the extractor for
        // every chunk. Shares the listener's extractor + persistence logic so
        // there's exactly one code path for how mentions / proposals land.
        final com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer bootstrap =
            new com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer(
                listener, contentChunkRepo, mentionRepo, extractorCfg.concurrency() );
        managers.put( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class, bootstrap );

        // Compose with any existing post-chunk sink so embedding indexing and
        // entity extraction both run on every save. Consumer.andThen catches
        // exceptions from the first and still invokes the second? No — andThen
        // propagates. Wrap the first so a listener crash can't poison the chain.
        final java.util.function.Consumer< java.util.List< java.util.UUID > > prior =
            this.hybridIndexListener;
        final java.util.function.Consumer< java.util.List< java.util.UUID > > safePrior = prior == null
            ? null
            : ids -> {
                try {
                    prior.accept( ids );
                } catch( final RuntimeException e ) {
                    LOG.warn( "Hybrid index listener failed; entity extraction will still run: {}",
                              e.getMessage(), e );
                }
            };
        final java.util.function.Consumer< java.util.List< java.util.UUID > > composite =
            safePrior == null ? listener : safePrior.andThen( listener );
        chunkProjector.setPostChunkSink( composite );

        final String modelLabel = "claude".equalsIgnoreCase( extractorCfg.backend() )
            ? extractorCfg.claudeModel()
            : extractorCfg.ollamaModel();
        LOG.info( "Entity extraction wired (backend={}, model={}, threshold={}, timeoutMs={}, batchConcurrency={})",
                  extractorCfg.backend(), modelLabel, extractorCfg.confidenceThreshold(),
                  extractorCfg.timeoutMs(), extractorCfg.concurrency() );
    }

    /**
     * Wires the Phase 3 graph-aware rerank step: loads {@code kg_edges} into
     * an {@link com.wikantik.search.hybrid.InMemoryGraphNeighborIndex}, builds
     * the name-based {@link com.wikantik.search.hybrid.QueryEntityResolver},
     * and registers a {@link com.wikantik.search.hybrid.GraphRerankStep} for
     * {@link com.wikantik.rest.SearchResource} to pick up. The feature is
     * config-gated: {@code wikantik.search.graph.boost=0} skips wiring
     * entirely so a fresh deploy pays zero cost until an operator opts in.
     */
    private void wireGraphRerank( final Properties props, final javax.sql.DataSource ds ) {
        final com.wikantik.search.hybrid.GraphRerankConfig cfg;
        try {
            cfg = com.wikantik.search.hybrid.GraphRerankConfig.fromProperties( props );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Invalid graph rerank config; feature disabled: {}", e.getMessage() );
            return;
        }
        if ( !cfg.enabled() ) {
            LOG.info( "Graph rerank disabled (wikantik.search.graph.boost=0)" );
            return;
        }
        final com.wikantik.search.hybrid.InMemoryGraphNeighborIndex neighborIndex;
        try {
            neighborIndex = new com.wikantik.search.hybrid.InMemoryGraphNeighborIndex( ds, cfg.neighborIndexMaxEdges() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Graph neighbor index failed to initialize; graph rerank disabled: {}", e.getMessage(), e );
            return;
        }
        final com.wikantik.search.hybrid.GraphProximityScorer scorer =
            new com.wikantik.search.hybrid.GraphProximityScorer( neighborIndex );
        final com.wikantik.search.hybrid.QueryEntityResolver resolver =
            new com.wikantik.search.hybrid.QueryEntityResolver( ds, cfg );
        final com.wikantik.search.hybrid.PageMentionsLoader mentionsLoader =
            new com.wikantik.search.hybrid.PageMentionsLoader( ds );
        final com.wikantik.search.hybrid.GraphRerankStep step =
            new com.wikantik.search.hybrid.GraphRerankStep( resolver, mentionsLoader, scorer, neighborIndex, cfg );

        managers.put( com.wikantik.search.hybrid.InMemoryGraphNeighborIndex.class, neighborIndex );
        managers.put( com.wikantik.search.hybrid.GraphNeighborIndex.class, neighborIndex );
        managers.put( com.wikantik.search.hybrid.GraphProximityScorer.class, scorer );
        managers.put( com.wikantik.search.hybrid.QueryEntityResolver.class, resolver );
        managers.put( com.wikantik.search.hybrid.PageMentionsLoader.class, mentionsLoader );
        managers.put( com.wikantik.search.hybrid.GraphRerankStep.class, step );

        LOG.info( "Graph rerank wired (boost={}, maxHops={}, indexNodes={})",
            cfg.boost(), cfg.maxHops(), neighborIndex.nodeCount() );
    }

    /**
     * Phase 5 of the Agent-Grade Content design — registers the
     * {@link com.wikantik.api.eval.RetrievalQualityRunner} so the
     * {@code /admin/retrieval-quality} endpoint and the nightly schedule
     * have a backend. The runner pulls one query at a time through BM25,
     * HYBRID, or HYBRID_GRAPH via the live {@link SearchManager} /
     * {@link com.wikantik.search.hybrid.HybridSearchService} /
     * {@link com.wikantik.search.hybrid.GraphRerankStep} stack.
     *
     * <p>The schedule activates only when {@code wikantik.retrieval.cron.enabled=true}
     * (default). Disable the cron in test harnesses without a live search
     * index to avoid noisy failures.</p>
     */
    private void wireRetrievalQualityRunner( final Properties props,
                                              final javax.sql.DataSource ds,
                                              final com.wikantik.api.structure.StructuralIndexService structuralIndex ) {
        try {
            final com.wikantik.knowledge.eval.RetrievalQualityDao rqDao =
                new com.wikantik.knowledge.eval.RetrievalQualityDao( ds );
            final com.wikantik.knowledge.eval.RetrievalQualityMetrics rqMetrics =
                com.wikantik.knowledge.eval.RetrievalQualityMetrics.resolveAndBind();
            final com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner.Retriever retriever =
                buildRetriever();
            final com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner.CanonicalIdResolver resolver =
                slug -> structuralIndex.resolveCanonicalIdFromSlug( slug );
            final int hour = TextUtil.getIntegerProperty( props, "wikantik.retrieval.cron.hour_utc", 3 );
            final com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner runner =
                new com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner(
                    rqDao, retriever, resolver, rqMetrics, hour );
            managers.put( com.wikantik.api.eval.RetrievalQualityRunner.class, runner );

            if ( TextUtil.getBooleanProperty( props, "wikantik.retrieval.cron.enabled", true ) ) {
                runner.scheduleNightly();
                LOG.info( "RetrievalQualityRunner registered with nightly schedule (hour={}Z)", hour );
            } else {
                LOG.info( "RetrievalQualityRunner registered (nightly schedule disabled by config)" );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "RetrievalQualityRunner wiring failed; /admin/retrieval-quality will return 503: {}",
                e.getMessage(), e );
        }
    }

    /**
     * Bridge the live search stack to the {@link
     * com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner.Retriever}
     * functional interface. BM25 calls {@link SearchManager#findPages} and
     * extracts page-name strings; HYBRID passes those through the hybrid
     * fuser; HYBRID_GRAPH adds the graph rerank step. Any throw collapses to
     * an empty list — the runner records {@code degraded=true} on the row.
     */
    private com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner.Retriever buildRetriever() {
        return ( mode, query ) -> {
            final SearchManager sm = getManager( SearchManager.class );
            if ( sm == null ) return java.util.List.of();
            final com.wikantik.api.core.Context ctx;
            try {
                final PageManager pm = getManager( PageManager.class );
                final com.wikantik.api.core.Page front = pm == null ? null : pm.getPage( getFrontPage() );
                ctx = front == null ? null : new WikiContext( this, front );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Could not build evaluation Context; aborting query: {}", e.getMessage() );
                return java.util.List.of();
            }
            if ( ctx == null ) return java.util.List.of();
            final java.util.List< String > bm25Names;
            try {
                final java.util.Collection< com.wikantik.api.search.SearchResult > raw =
                    sm.findPages( query, ctx );
                bm25Names = new java.util.ArrayList<>( raw.size() );
                for ( final com.wikantik.api.search.SearchResult sr : raw ) {
                    if ( sr.getPage() != null ) bm25Names.add( sr.getPage().getName() );
                }
            } catch ( final Exception e ) {
                LOG.warn( "BM25 findPages failed for '{}': {}", query, e.getMessage(), e );
                return java.util.List.of();
            }
            switch ( mode ) {
                case BM25:
                    return bm25Names;
                case HYBRID: {
                    final com.wikantik.search.hybrid.HybridSearchService hs =
                        getManager( com.wikantik.search.hybrid.HybridSearchService.class );
                    return hs == null ? bm25Names : hs.rerank( query, bm25Names );
                }
                case HYBRID_GRAPH: {
                    final com.wikantik.search.hybrid.HybridSearchService hs =
                        getManager( com.wikantik.search.hybrid.HybridSearchService.class );
                    final java.util.List< String > fused = hs == null ? bm25Names : hs.rerank( query, bm25Names );
                    final com.wikantik.search.hybrid.GraphRerankStep gr =
                        getManager( com.wikantik.search.hybrid.GraphRerankStep.class );
                    return gr == null ? fused : gr.rerank( query, fused );
                }
                default:
                    return bm25Names;
            }
        };
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
                list.add( prop.substring( prop.lastIndexOf( '.' ) + 1 ) );
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
        if ( hybridIndexListener != null ) {
            try { hybridIndexListener.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridIndexListener close failed: {}", e.getMessage(), e ); }
        }
        if ( entityExtractionListener != null ) {
            try { entityExtractionListener.close(); }
            catch( final RuntimeException e ) { LOG.warn( "entityExtractionListener close failed: {}", e.getMessage(), e ); }
        }
        if ( hybridQueryEmbedder != null ) {
            try { hybridQueryEmbedder.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridQueryEmbedder close failed: {}", e.getMessage(), e ); }
        }
        if ( hybridBootstrapIndexer != null ) {
            try { hybridBootstrapIndexer.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridBootstrapIndexer close failed: {}", e.getMessage(), e ); }
        }
        WikiEventManager.unregisterListenersFor( this );
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
