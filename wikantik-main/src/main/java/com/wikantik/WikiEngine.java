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
import com.wikantik.knowledge.HubDiscoveryService;
import com.wikantik.knowledge.HubOverviewService;
import com.wikantik.knowledge.HubProposalService;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.progress.ProgressManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.PropertyReader;
import com.wikantik.util.TextUtil;
import com.wikantik.variables.VariableManager;

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
import java.util.IdentityHashMap;
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

    /** Knowledge subsystem services produced by {@code KnowledgeSubsystemFactory}.
     *  Phase 1 of the wikantik-main decomposition (2026-05-05). Ckpt A2: all
     *  KG-flavored service registrations go through the EngineServiceRegistry.
     *  Volatile: written once during init (unsynchronized path in initialize())
     *  and read from both the unsynchronized wireLuceneMltPostConstruction helper
     *  and the synchronized patchContextRetrievalService method. */
    private volatile com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services knowledgeSubsystem;

    /** Core subsystem services produced by {@code CoreSubsystemFactory}.
     *  Phase 2 of the wikantik-main decomposition (2026-05-06); foundation
     *  every other subsystem depends on (typed properties, event bus,
     *  metrics registry, leaf managers). */
    private com.wikantik.core.subsystem.CoreSubsystem.Services coreSubsystem;

    /** Persistence subsystem services produced by {@code PersistenceSubsystemFactory}.
     *  Phase 3 of the wikantik-main decomposition (2026-05-06); owns the
     *  {@link javax.sql.DataSource} and every JDBC repository / DAO. {@code null}
     *  when the engine boots without a configured datasource (unit-test paths). */
    private com.wikantik.persistence.subsystem.PersistenceSubsystem.Services persistenceSubsystem;

    /** Auth subsystem services produced by {@code AuthSubsystemFactory}.
     *  Phase 4 of the wikantik-main decomposition (2026-05-06); typed surface
     *  over the four core auth managers, the web-container authorizer, the
     *  API-key service, and (Ckpt 3) the SecurityVerifier-derived helpers. */
    private com.wikantik.auth.subsystem.AuthSubsystem.Services authSubsystem;

    /** Page subsystem services produced by {@code PageSubsystemFactory}.
     *  Phase 5 of the wikantik-main decomposition (2026-05-06); typed
     *  surface over PageManager / AttachmentManager / PageRenamer /
     *  PageSaveHelper / PageProvider. */
    private com.wikantik.page.subsystem.PageSubsystem.Services pageSubsystem;

    /** Rendering subsystem services produced by {@code RenderingSubsystemFactory}.
     *  Phase 6 of the wikantik-main decomposition (2026-05-07); typed
     *  surface over RenderingManager / PluginManager / FilterManager /
     *  DifferenceManager + (Ckpt 4) the four decomposed SpamFilter helpers. */
    private com.wikantik.render.subsystem.RenderingSubsystem.Services renderingSubsystem;

    /** Search subsystem services produced by {@code SearchSubsystemFactory}.
     *  Phase 7 of the wikantik-main decomposition (2026-05-07); typed
     *  surface over SearchManager / SearchProvider / hybrid retrieval /
     *  embedding pipeline + (Ckpt 4) the three decomposed Lucene helpers. */
    private com.wikantik.search.subsystem.SearchSubsystem.Services searchSubsystem;

    /** Page Graph subsystem services produced by {@code PageGraphSubsystemFactory}.
     *  Phase 9 Checkpoint 1 of the wikantik-main decomposition (2026-05-07); typed
     *  surface over StructuralIndexService / PageGraphService / ReferenceManager /
     *  ContentIndexRebuildService. */
    private com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Services pageGraphSubsystem;

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

    // Ckpt A2: managers Map deleted. 2026-07-02 (ADR-0008): the 78 typed mgr_* fields
    // deleted too — all service reads/writes now go through the EngineServiceRegistry.

    /**
     * The late-bound service registry: replaces the former 78 per-class typed backing
     * fields and their class-to-writer/reader dispatch maps. New services
     * register here via setManager — never as a WikiEngine field.
     */
    private final com.wikantik.core.registry.EngineServiceRegistry serviceRegistry =
            new com.wikantik.core.registry.EngineServiceRegistry();

    /** The late-bound service registry. New services register here via setManager — never as a WikiEngine field. */
    public com.wikantik.core.registry.EngineServiceRegistry serviceRegistry() { return serviceRegistry; }

    /**
     * The set of manager types WikiEngine knows about — used only to distinguish
     * "known type, not yet populated" (debug, expected during boot) from "genuinely
     * unknown type" (warn). Equals the former typed-field-readers keyset:
     * every SNAPSHOT_REBUILDERS key, plus ContextRetrievalService, SystemPageRegistry,
     * RecentArticlesManager, and BlogManager (excluded from SNAPSHOT_REBUILDERS by
     * design; see setManager and the getManager coreSubsystem fallthrough).
     */
    private static boolean isKnownManagerType( final Class<?> t ) {
        return SNAPSHOT_REBUILDERS.containsKey( t )
            || t == com.wikantik.api.knowledge.ContextRetrievalService.class
            || t == com.wikantik.api.managers.SystemPageRegistry.class
            || t == com.wikantik.content.RecentArticlesManager.class
            || t == com.wikantik.blog.BlogManager.class;
    }

    /**
     * Maps each class key to the subsystem-snapshot rebuilder that must run when that
     * class is hot-swapped via {@link #setManager}.  One entry per class; the lambda
     * is a no-op if the relevant subsystem snapshot is still null (i.e. during boot).
     * Populated in its own static block below.
     *
     * <p>ContextRetrievalService is intentionally absent — see the comment in
     * {@link #setManager} for the reason.</p>
     */
    private static final Map<Class<?>, java.util.function.Consumer<WikiEngine>> SNAPSHOT_REBUILDERS;

    static {
        final IdentityHashMap<Class<?>, java.util.function.Consumer<WikiEngine>> s = new IdentityHashMap<>( 128 );
        // Auth
        java.util.function.Consumer<WikiEngine> rebuildAuth =
            e -> { if ( e.authSubsystem != null ) e.authSubsystem = com.wikantik.auth.subsystem.AuthSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.auth.AuthenticationManager.class,      rebuildAuth );
        s.put( com.wikantik.auth.AuthorizationManager.class,       rebuildAuth );
        s.put( com.wikantik.auth.UserManager.class,                rebuildAuth );
        s.put( com.wikantik.auth.authorize.GroupManager.class,     rebuildAuth );
        s.put( com.wikantik.auth.acl.AclManager.class,             rebuildAuth );
        // Page
        java.util.function.Consumer<WikiEngine> rebuildPage =
            e -> { if ( e.pageSubsystem != null ) e.pageSubsystem = com.wikantik.page.subsystem.PageSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.api.managers.PageManager.class,        rebuildPage );
        s.put( com.wikantik.api.managers.AttachmentManager.class,  rebuildPage );
        s.put( com.wikantik.content.PageRenamer.class,             rebuildPage );
        s.put( com.wikantik.api.managers.ReferenceManager.class,   e -> {
            if ( e.pageSubsystem    != null ) e.pageSubsystem    = com.wikantik.page.subsystem.PageSubsystemBridge.rebuildFromManagers( e );
            if ( e.pageGraphSubsystem != null ) e.pageGraphSubsystem = com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.rebuildFromManagers( e );
        } );
        // Core
        java.util.function.Consumer<WikiEngine> rebuildCore =
            e -> { if ( e.coreSubsystem != null ) e.coreSubsystem = com.wikantik.core.subsystem.CoreSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.cache.CachingManager.class,                   rebuildCore );
        s.put( com.wikantik.variables.VariableManager.class,              rebuildCore );
        s.put( com.wikantik.ui.progress.ProgressManager.class,            rebuildCore );
        s.put( com.wikantik.ui.CommandResolver.class,                     rebuildCore );
        s.put( com.wikantik.url.URLConstructor.class,                     rebuildCore );
        s.put( com.wikantik.i18n.InternationalizationManager.class,       rebuildCore );
        // Rendering
        java.util.function.Consumer<WikiEngine> rebuildRendering =
            e -> { if ( e.renderingSubsystem != null ) e.renderingSubsystem = com.wikantik.render.subsystem.RenderingSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.render.RenderingManager.class,         rebuildRendering );
        s.put( com.wikantik.plugin.PluginManager.class,            rebuildRendering );
        s.put( com.wikantik.filters.FilterManager.class,           rebuildRendering );
        s.put( com.wikantik.diff.DifferenceManager.class,          rebuildRendering );
        // Search
        java.util.function.Consumer<WikiEngine> rebuildSearch =
            e -> { if ( e.searchSubsystem != null ) e.searchSubsystem = com.wikantik.search.subsystem.SearchSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.search.SearchManager.class,                                  rebuildSearch );
        s.put( com.wikantik.search.SearchProvider.class,                                 rebuildSearch );
        s.put( com.wikantik.search.hybrid.HybridSearchService.class,                     rebuildSearch );
        s.put( com.wikantik.search.hybrid.QueryEmbedder.class,                           rebuildSearch );
        s.put( com.wikantik.search.hybrid.QueryEntityResolver.class,                     rebuildSearch );
        s.put( com.wikantik.search.hybrid.GraphRerankStep.class,                         rebuildSearch );
        s.put( com.wikantik.search.hybrid.GraphProximityScorer.class,                    rebuildSearch );
        s.put( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class,                rebuildSearch );
        s.put( com.wikantik.search.hybrid.ChunkVectorIndex.class,                        rebuildSearch );
        s.put( com.wikantik.search.hybrid.InMemoryGraphNeighborIndex.class,              rebuildSearch );
        s.put( com.wikantik.search.hybrid.GraphNeighborIndex.class,                      rebuildSearch );
        s.put( com.wikantik.search.hybrid.PageMentionsLoader.class,                      rebuildSearch );
        s.put( com.wikantik.search.embedding.EmbeddingIndexService.class,                rebuildSearch );
        s.put( com.wikantik.search.embedding.OllamaEmbeddingClient.class,                rebuildSearch );
        s.put( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class,            rebuildSearch );
        s.put( com.wikantik.search.embedding.AsyncEmbeddingIndexListener.class,          rebuildSearch );
        s.put( com.wikantik.search.FrontmatterMetadataCache.class,                       rebuildSearch );
        s.put( com.wikantik.search.subsystem.lucene.LuceneIndexer.class,                 rebuildSearch );
        s.put( com.wikantik.search.subsystem.lucene.LuceneSearcher.class,                rebuildSearch );
        s.put( com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle.class,          rebuildSearch );
        // Page Graph
        java.util.function.Consumer<WikiEngine> rebuildPageGraph =
            e -> { if ( e.pageGraphSubsystem != null ) e.pageGraphSubsystem = com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.api.pagegraph.StructuralIndexService.class,                  rebuildPageGraph );
        s.put( com.wikantik.api.pagegraph.PageGraphService.class,                        rebuildPageGraph );
        s.put( com.wikantik.admin.ContentIndexRebuildService.class,                      rebuildPageGraph );
        s.put( com.wikantik.ontology.runtime.OntologyRebuildCoordinator.class,           rebuildPageGraph );
        s.put( com.wikantik.drift.DriftSweepService.class,                               rebuildPageGraph );
        s.put( com.wikantik.citation.CitationRepository.class,                           rebuildPageGraph );
        s.put( com.wikantik.citation.CitationSync.class,                                 rebuildPageGraph );
        s.put( com.wikantik.pagegraph.spine.PageVerificationDao.class,                   e -> {} ); // no subsystem snapshot
        s.put( com.wikantik.pagegraph.spine.TrustedAuthorsDao.class,                     e -> {} ); // no subsystem snapshot
        s.put( com.wikantik.pagegraph.spine.StructuralIndexEventListener.class,          e -> {} ); // no subsystem snapshot
        // Knowledge (ContextRetrievalService intentionally excluded — see setManager comment)
        java.util.function.Consumer<WikiEngine> rebuildKnowledge =
            e -> { if ( e.knowledgeSubsystem != null ) e.knowledgeSubsystem = com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge.rebuildFromManagers( e ); };
        s.put( com.wikantik.api.knowledge.KnowledgeGraphService.class,                         rebuildKnowledge );
        s.put( com.wikantik.api.knowledge.KgProposalJudgeService.class,                        rebuildKnowledge );
        s.put( com.wikantik.knowledge.judge.JudgeRunner.class,                                 rebuildKnowledge );
        s.put( com.wikantik.knowledge.judge.KgMaterializationService.class,                    rebuildKnowledge );
        s.put( com.wikantik.knowledge.judge.KgJudgeTimeoutRepository.class,                    rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubProposalService.class,                                rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubDiscoveryService.class,                               rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubOverviewService.class,                                rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubProposalRepository.class,                             rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubDiscoveryRepository.class,                            rebuildKnowledge );
        s.put( com.wikantik.knowledge.chunking.ContentChunkRepository.class,                   rebuildKnowledge );
        s.put( com.wikantik.knowledge.chunking.ChunkProjector.class,                           rebuildKnowledge );
        s.put( com.wikantik.knowledge.MentionIndex.class,                                      rebuildKnowledge );
        s.put( com.wikantik.knowledge.embedding.NodeMentionSimilarity.class,                   rebuildKnowledge );
        s.put( com.wikantik.knowledge.FrontmatterDefaultsFilter.class,                         rebuildKnowledge );
        s.put( com.wikantik.knowledge.HubSyncFilter.class,                                     rebuildKnowledge );
        s.put( com.wikantik.api.agent.ForAgentProjectionService.class,                         rebuildKnowledge );
        s.put( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class,       rebuildKnowledge );
        s.put( com.wikantik.api.kgpolicy.KgInclusionPolicy.class,                              rebuildKnowledge );
        s.put( com.wikantik.kgpolicy.ReconciliationJobRunner.class,                            rebuildKnowledge );
        s.put( com.wikantik.api.eval.RetrievalQualityRunner.class,                             rebuildKnowledge );
        s.put( com.wikantik.knowledge.extraction.ChunkEntityMentionRepository.class,           e -> {} ); // no subsystem snapshot
        s.put( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class,          e -> {} ); // no subsystem snapshot
        s.put( com.wikantik.kgpolicy.KgClusterPolicyRepository.class,                         e -> {} ); // no subsystem snapshot
        s.put( com.wikantik.kgpolicy.KgExcludedPagesRepository.class,                         e -> {} ); // no subsystem snapshot
        SNAPSHOT_REBUILDERS = s;
    }

    // Audit subsystem (built in initKnowledgeGraph where the DataSource is in scope).
    private volatile com.wikantik.audit.AuditService auditService;
    private volatile com.wikantik.audit.AuditReadPolicy auditReadPolicy;
    private volatile com.wikantik.audit.AuditWriterThread auditWriter;
    // Strong reference prevents the listener from being garbage-collected out of
    // WikiEventManager's WeakHashMap before we can de-register it on shutdown.
    private volatile com.wikantik.audit.AuditEventListener auditEventListener;

    // Strong reference (same WeakHashMap concern as auditEventListener): stamps each
    // account's last-login timestamp on LOGIN_AUTHENTICATED.
    private volatile com.wikantik.auth.LastLoginEventListener lastLoginEventListener;

    /** Citation subsystem wired holder; retains a strong ref to the event listener. */
    private com.wikantik.citation.CitationWiringHelper.Wired citationWired;

    // -----------------------------------------------------------------------
    // Hybrid-retrieval / entity-extraction lifecycle handles are no longer
    // tracked as WikiEngine fields — they live in the service registry
    // (set via setManager by SearchWiringHelper / KnowledgeWiringHelper) and
    // are read back via getManager, including at shutdown() for close().
    // -----------------------------------------------------------------------

    /** Returns the hybrid-retrieval async embedding listener (for composing the entity-extraction chain). */
    public com.wikantik.search.embedding.AsyncEmbeddingIndexListener getHybridIndexListener() {
        // Read the registry directly — WikiEngine-internal reads must not go through the
        // getManager() service-locator (ArchUnit R-2 freezes its caller set).
        return serviceRegistry.get( com.wikantik.search.embedding.AsyncEmbeddingIndexListener.class );
    }

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

        logStartupBannerAndValidateContainer();

        fireEvent( WikiEngineEvent.INITIALIZING ); // begin initialization

        LOG.debug( "Configuring WikiEngine..." );

        configureWorkingDirectoryAndProperties( props );

        //
        //  Initialize the important modules.  Any exception thrown by the managers means that we will not start up.
        //  initManagers() (managers) and the subsystem-factory phases below must run inside this try so a startup
        //  failure aborts the whole engine; the catch handlers below preserve the original per-type diagnostics.
        //
        try {
            initManagers( props );

            // getManager() lookups stay in initialize() — the decomposition
            // ArchUnit rule freezes getManager callers by method, so the helpers
            // receive their collaborators as parameters rather than looking them up.
            initFrontmatterMetadataCache( getManager( PageManager.class ) );
            wireCorePageFilters( getManager( FilterManager.class ),
                getManager( ReferenceManager.class ), getManager( SearchManager.class ) );
            buildCoreAuthPageRenderingSubsystems( props,
                getManager( SystemPageRegistry.class ),
                getManager( RecentArticlesManager.class ),
                getManager( BlogManager.class ) );

            // Phase 9: Knowledge graph (optional — requires datasource
            // configuration). Builds persistenceSubsystem internally and
            // consumes pageSubsystem via KnowledgeSubsystem.Deps.
            initKnowledgeGraph( props );

            // Audit subsystem is initialized independently of Knowledge-Graph init so a
            // KG failure cannot silently disable auditing. initKnowledgeGraph swallows
            // its own exceptions, so this runs on both the happy and the KG-failed path.
            try {
                initAuditSubsystem( props );
            } catch ( final RuntimeException e ) {
                // Never break engine startup because of auditing; never swallow silently.
                LOG.warn( "Audit subsystem initialization failed; continuing without audit.", e );
            }

            buildSearchSubsystem();

            // Phase 7 Ckpt 4: post-construction wire of the LuceneMlt seam
            // onto HubOverviewService. Resolves the Search↔Knowledge cycle.
            wireLuceneMltPostConstruction();
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

        // Phase 8 Ckpt 1.5: rebuild the KnowledgeSubsystem.Services record with the five
        // post-construction services that were wired into the manager registry by
        // initKnowledgeGraph (ForAgentProjectionService, BootstrapEntityExtractionIndexer,
        // KgInclusionPolicy, ReconciliationJobRunner, RetrievalQualityRunner) after
        // KnowledgeSubsystemFactory.create() returned. ContextRetrievalService is wired
        // even later by ContextRetrievalServiceInitializer (a servlet listener) and stays
        // null here; patchContextRetrievalService() will fill it in once the servlet listener fires.
        if ( knowledgeSubsystem != null ) {
            knowledgeSubsystem = rebuildKnowledgeSubsystemWithPostConstructionServices( knowledgeSubsystem );
        }

        buildPageGraphSubsystem();
        stashSubsystemsOnServletContext();

        fireEvent( WikiEngineEvent.INITIALIZED ); // initialization complete

        LOG.info( "WikiEngine configured." );
        isConfigured = true;
    }

    /**
     *  Logs the startup banner and enforces the minimum Servlet container
     *  version (3.1). Throws {@link InternalWikiException} on an older container.
     */
    private void logStartupBannerAndValidateContainer() {
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
    }

    /**
     *  Resolves and validates the working directory, then loads the core engine
     *  properties (encoding, user-info storage, front page, template directory).
     */
    private void configureWorkingDirectoryAndProperties( final Properties props ) throws WikiException {
        createAndFindWorkingDirectory( props );

        useUTF8        = StandardCharsets.UTF_8.name().equals( TextUtil.getStringProperty( props, PROP_ENCODING, StandardCharsets.ISO_8859_1.name() ) );
        saveUserInfo   = TextUtil.getBooleanProperty( props, PROP_STOREUSERNAME, saveUserInfo );
        frontPage      = TextUtil.getStringProperty( props, PROP_FRONTPAGE, "Main" );
        templateDir    = TextUtil.getStringProperty( props, PROP_TEMPLATEDIR, "default" );
        enforceValidTemplateDirectory();
    }

    /**
     *  Initializes every manager in dependency order. Any exception aborts
     *  startup — see {@link #initialize}'s catch block, whose specific handlers
     *  rely on the checked exceptions declared here.
     *
     *  Initialization order matters due to dependencies between managers:
     *  - Phase 1: Core infrastructure (CommandResolver, URLConstructor, CachingManager)
     *  - Phase 2: Storage providers (PageManager, AttachmentManager) - depend on CachingManager
     *  - Phase 3: Utility and security managers - all run on main thread because:
     *      * Security managers (Auth*, UserManager, GroupManager) require JNDI context
     *      * UserManager eagerly initializes UserDatabase which needs JNDI for JDBCUserDatabase
     *      * Utility managers (Plugin, Difference, Variable, Search) are fast and don't benefit from parallelization
     *  - Phase 4: Dependent managers (Editor, Progress, Acl, Workflow, etc.)
     *  - Phase 5: RenderingManager (depends on FilterManager)
     *  - Phase 6: ReferenceManager - initialized asynchronously in background thread
     *      * This is the key optimization: ReferenceManager scans all pages which is expensive
     *      * Running it async allows the wiki to start serving requests immediately
     */
    private void initManagers( final Properties props ) throws Exception {
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
    }

    /**
     *  Builds the frontmatter metadata cache used by the search response path —
     *  keyed on (pageName, lastModified) so a page edit naturally invalidates the
     *  entry — and publishes its Caffeine size/hits/misses/evictions metrics. The
     *  metric registration is colocated with construction so wireHybridRetrieval
     *  doesn't need an extra getManager call (blocked by the decomposition ArchUnit rule).
     */
    private void initFrontmatterMetadataCache( final PageManager pageManager ) {
        final com.wikantik.search.FrontmatterMetadataCache fmCacheInstance =
            new com.wikantik.search.FrontmatterMetadataCache( pageManager );
        setManager( com.wikantik.search.FrontmatterMetadataCache.class, fmCacheInstance );
        try {
            final io.micrometer.core.instrument.MeterRegistry meterReg =
                com.wikantik.api.observability.MeterRegistryHolder.get();
            if ( meterReg != null ) {
                com.wikantik.observability.CaffeineCacheMetricsBridge
                    .register( meterReg, "frontmatter_metadata", fmCacheInstance.cache() );
                // likely-wiki syntax heuristic cache: keyed on content hash so the
                // 6-regex scan over the page body runs once per distinct body, not
                // on every /api/pages/{name} GET. Static cache lives in the converter.
                com.wikantik.observability.CaffeineCacheMetricsBridge
                    .register( meterReg, "likely_wiki_syntax",
                        com.wikantik.content.WikiToMarkdownConverter.likelyWikiCache() );
            }
        } catch ( final Throwable t ) {
            LOG.warn( "FrontmatterMetadataCache metric registration failed: {}", t.getMessage(), t );
        }
    }

    /**
     *  Hooks the Reference and Search managers into the page-filter chain so they
     *  react to page saves.
     */
    private void wireCorePageFilters( final FilterManager filterManager,
            final ReferenceManager referenceManager, final SearchManager searchManager ) {
        filterManager.addPageFilter( referenceManager, -1001 );
        filterManager.addPageFilter( searchManager, -1002 );
    }

    /**
     *  Builds the Core, Auth, Page and Rendering subsystem service records in
     *  dependency order, after their leaf managers are constructed and BEFORE
     *  {@link #initKnowledgeGraph}. Persistence is still null at this point — it
     *  is built inside initKnowledgeGraph — and these factories tolerate that.
     */
    private void buildCoreAuthPageRenderingSubsystems( final Properties props,
            final SystemPageRegistry systemPageRegistry,
            final RecentArticlesManager recentArticlesManager,
            final BlogManager blogManager ) {
        // Build the Core subsystem after its leaf managers are constructed.
        // Knowledge (built next, in initKnowledgeGraph) consumes Core via WikiSubsystems.
        this.coreSubsystem = com.wikantik.core.subsystem.CoreSubsystemFactory.create(
            new com.wikantik.core.subsystem.CoreSubsystem.Deps(
                props,
                servletContext,
                com.wikantik.api.observability.MeterRegistryHolder.get(),
                systemPageRegistry,
                recentArticlesManager,
                blogManager,
                this ) );

        // Build the Auth subsystem after the four auth managers are registered.
        // Persistence is null here (built inside initKnowledgeGraph); the factory tolerates it.
        this.authSubsystem = com.wikantik.auth.subsystem.AuthSubsystemFactory.create(
            new com.wikantik.auth.subsystem.AuthSubsystem.Deps(
                coreSubsystem, persistenceSubsystem, servletContext, this ) );

        // Build the Page subsystem BEFORE initKnowledgeGraph so the KG factory can
        // declare a typed PageSubsystem.Services dependency.
        this.pageSubsystem = com.wikantik.page.subsystem.PageSubsystemFactory.create(
            new com.wikantik.page.subsystem.PageSubsystem.Deps(
                coreSubsystem, persistenceSubsystem, authSubsystem, this ) );

        // Build the Rendering subsystem after Page (Rendering depends on Page for the
        // page-save filter-chain seam) and BEFORE initKnowledgeGraph.
        this.renderingSubsystem = com.wikantik.render.subsystem.RenderingSubsystemFactory.create(
            new com.wikantik.render.subsystem.RenderingSubsystem.Deps(
                coreSubsystem, authSubsystem, pageSubsystem, this ) );
    }

    /**
     *  Builds the Search subsystem AFTER Knowledge — Search depends on Knowledge
     *  for the graph-rerank step. The Search↔Knowledge LuceneMlt cycle stays
     *  nullable until {@link #wireLuceneMltPostConstruction} resolves it.
     */
    private void buildSearchSubsystem() {
        this.searchSubsystem = com.wikantik.search.subsystem.SearchSubsystemFactory.create(
            new com.wikantik.search.subsystem.SearchSubsystem.Deps(
                persistenceSubsystem != null ? persistenceSubsystem.dataSource() : null,
                coreSubsystem, persistenceSubsystem, pageSubsystem, knowledgeSubsystem, this ) );
    }

    /**
     *  Wraps the already-registered Page Graph services (StructuralIndexService,
     *  PageGraphService, ReferenceManager, ContentIndexRebuildService — registered
     *  earlier in initPageGraphServices()) into the typed Services record.
     */
    private void buildPageGraphSubsystem() {
        this.pageGraphSubsystem = com.wikantik.pagegraph.subsystem.PageGraphSubsystemFactory.create(
            new com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Deps(
                coreSubsystem, persistenceSubsystem, pageSubsystem, this ) );
    }

    /**
     *  Stashes the typed subsystem bundle on the ServletContext so servlets can
     *  reach the services without going through getManager(Class). Skipped when
     *  running outside a servlet container OR when the Knowledge subsystem didn't
     *  initialise (no datasource — e.g. unit-test engines built via
     *  TestEngine.setManager); RestServletBase then falls back to a synthetic
     *  bundle reading the legacy manager registry.
     */
    private void stashSubsystemsOnServletContext() {
        if ( servletContext != null && coreSubsystem != null && knowledgeSubsystem != null ) {
            final WikiSubsystems subsystems = new WikiSubsystems(
                coreSubsystem, persistenceSubsystem, authSubsystem, pageSubsystem,
                renderingSubsystem, searchSubsystem, knowledgeSubsystem, pageGraphSubsystem );
            servletContext.setAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE, subsystems );
        }
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
        // Write directly to the service registry. Do NOT call setManager here:
        // setManager triggers subsystem snapshot rebuilds which would produce partial
        // snapshots (e.g. coreSubsystem with null systemPageRegistry) that subsequent
        // initializers then see via getCoreSubsystem(). The full snapshots are built
        // by the factory calls (CoreSubsystemFactory.create etc.) after all
        // initComponent calls complete.
        serviceRegistry.put( componentClass, component );
        if( Initializable.class.isAssignableFrom( component.getClass() ) ) {
            ( ( Initializable )component ).initialize( this, properties );
        }
    }

    /** Retrieves the object registered under the given type key. Not part of the {@link Engine} interface. */
    @SuppressWarnings( "unchecked" )
    public < T > T getManager( final Class< T > manager ) {
        // 1. Service registry — O(1) exact-class lookup for all known types.
        //    Returns non-null when the type has been registered by setManager / initComponent.
        final T fromRegistry = serviceRegistry.get( manager );
        if ( fromRegistry != null ) return fromRegistry;

        // 2. Fall through to typed subsystem services. Phase 2 of the
        //    wikantik-main decomposition removed SystemPageRegistry,
        //    RecentArticlesManager, and BlogManager from the registered-service set;
        //    this bridge keeps getManager(X.class) returning them transparently.
        //    New code should reach the typed accessor directly: getCoreSubsystem().xxx().
        if ( coreSubsystem != null ) {
            if ( manager.isInstance( coreSubsystem.systemPageRegistry() ) ) {
                return ( T ) coreSubsystem.systemPageRegistry();
            }
            if ( manager.isInstance( coreSubsystem.recentArticlesManager() ) ) {
                return ( T ) coreSubsystem.recentArticlesManager();
            }
            if ( manager.isInstance( coreSubsystem.blogManager() ) ) {
                return ( T ) coreSubsystem.blogManager();
            }
        }

        // Differentiate "known class, not yet registered (boot-ordering between
        // cross-manager getManager calls)" vs "genuinely unknown class". The former is
        // expected during init — managers reach for siblings before all initComponent
        // calls have finished — and produced 9000+ WARN lines on every boot. Only
        // warn when the class isn't a known manager type at all.
        if ( ! isKnownManagerType( manager ) ) {
            LOG.warn( "getManager({}) returned null — class is not a known registered service type", manager.getName() );
        } else {
            LOG.debug( "getManager({}) returned null — service not yet registered (called before its initComponent)", manager.getName() );
        }
        return null;
    }

    /** Registers an object under the given type key. Not part of the {@link Engine} interface. */
    public < T > void setManager( final Class< T > clazz, final T manager ) {
        serviceRegistry.put( clazz, manager );
        // When a subsystem-owned manager is hot-swapped POST-BOOT (e.g. by a unit test installing
        // a mock), rebuild the typed snapshot so callers reaching the subsystem directly see the
        // new value without a full re-init. During boot the snapshot is null — DO NOT rebuild
        // eagerly: the partial registry would produce a snapshot with null fields that then
        // poisons subsequent initComponent calls (RenderingManager.initialize reads filterManager
        // through the bridge; if a stale snapshot is cached the bridge returns it instead of
        // rebuilding from the now-complete registry, so filterManager stays null forever).
        // The boot path builds each snapshot at the correct moment via *SubsystemFactory.create.
        //
        // ContextRetrievalService is intentionally absent from SNAPSHOT_REBUILDERS — it is wired
        // post-boot by ContextRetrievalServiceInitializer; including it would overwrite the
        // patched service before the patch can be re-applied.
        final java.util.function.Consumer<WikiEngine> rebuilder = SNAPSHOT_REBUILDERS.get( clazz );
        if ( rebuilder != null ) rebuilder.accept( this );
    }

    /** The async audit service; {@code null} when no datasource is configured. */
    public com.wikantik.audit.AuditService getAuditService() { return auditService; }

    /** The read-audit policy; {@code null} when no datasource is configured. */
    public com.wikantik.audit.AuditReadPolicy getAuditReadPolicy() { return auditReadPolicy; }

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
                // Only warn when an explicit non-default template was configured but is missing.
                // The "couldn't find default, falling back to default" case is the boot-time
                // happy path for a wiki without a custom template — silent it.
                if ( ! DEFAULT_TEMPLATE_NAME.equals( getTemplateDir() ) ) {
                    LOG.warn( "Configured template '{}' not found — falling back to '{}'.",
                            getTemplateDir(), DEFAULT_TEMPLATE_NAME );
                }
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
     * <p>Phase 9 Ckpt 4c: the bulk of the inline wiring has been relocated to
     * {@link com.wikantik.pagegraph.subsystem.PageGraphWiringHelper},
     * {@link com.wikantik.knowledge.subsystem.KnowledgeWiringHelper}, and
     * {@link com.wikantik.search.subsystem.SearchWiringHelper}. This method
     * is now a sequenced call into those helpers.</p>
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

            // Phase 3: Persistence subsystem.
            //
            // The anchored-comments DAOs (PageOwnerService, MentionService)
            // need to ask "does this login exist?" and "who is the frontmatter
            // author of this canonical_id?" — those answers live in the Auth
            // subsystem (built before initKnowledgeGraph) and the structural
            // index (built later in this method by PageGraphWiringHelper),
            // respectively. We thread both through Deps as closures that
            // capture `this` and resolve lazily at call time.
            final java.util.function.Predicate< String > userExistsLookup = login -> {
                if ( login == null || login.isBlank() ) return false;
                final com.wikantik.auth.subsystem.AuthSubsystem.Services auth = this.authSubsystem;
                if ( auth == null || auth.users() == null ) return false;
                try {
                    auth.users().getUserDatabase().findByLoginName( login );
                    return true;
                } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
                    return false;
                } catch ( final Exception e ) {
                    LOG.warn( "userExistsLookup({}) failed: {}", login, e.getMessage(), e );
                    return false;
                }
            };
            final java.util.function.Function< String, java.util.Optional< String > > pageAuthorLookup = canonicalId -> {
                if ( canonicalId == null || canonicalId.isBlank() ) return java.util.Optional.empty();
                try {
                    final com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Services pg = this.pageGraphSubsystem;
                    if ( pg == null || pg.structuralIndexService() == null ) return java.util.Optional.empty();
                    final java.util.Optional< String > slug = pg.structuralIndexService().resolveSlugFromCanonicalId( canonicalId );
                    if ( slug.isEmpty() ) return java.util.Optional.empty();
                    final com.wikantik.page.subsystem.PageSubsystem.Services ps = this.pageSubsystem;
                    if ( ps == null || ps.pages() == null ) return java.util.Optional.empty();
                    final com.wikantik.api.managers.PageManager pm = ps.pages();
                    final com.wikantik.api.core.Page page = pm.getPage( slug.get() );
                    if ( page == null ) return java.util.Optional.empty();
                    final String author = page.getAuthor();
                    if ( author == null || author.isBlank() ) return java.util.Optional.empty();
                    return java.util.Optional.of( author );
                } catch ( final Exception e ) {
                    LOG.warn( "pageAuthorLookup({}) failed: {}", canonicalId, e.getMessage(), e );
                    return java.util.Optional.empty();
                }
            };

            this.persistenceSubsystem = com.wikantik.persistence.subsystem.PersistenceSubsystemFactory.create(
                new com.wikantik.persistence.subsystem.PersistenceSubsystem.Deps(
                    ds, coreSubsystem.properties(), userExistsLookup, pageAuthorLookup ) );

            // Resolve the Lucene MoreLikeThis seam.
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

            final io.micrometer.core.instrument.MeterRegistry meterRegistry =
                com.wikantik.api.observability.MeterRegistryHolder.get();
            if ( meterRegistry == null ) {
                LOG.warn( "No shared MeterRegistry installed — ChunkProjector and "
                        + "ContentIndexRebuildService will publish metrics to a "
                        + "local SimpleMeterRegistry that is NOT scraped at "
                        + "/observability/metrics. Check that ObservabilityLifecycleExtension "
                        + "is on the classpath and that onInit has run." );
            }

            // Audit dropped-entry gauge — lazy, null-safe: the AuditService is
            // built later in this same method (initAuditSubsystem). The supplier
            // reads getAuditService() at scrape time so the gauge is always
            // present even before the audit subsystem finishes constructing.
            if ( meterRegistry != null ) {
                io.micrometer.core.instrument.Gauge.builder(
                        "wikantik_audit_dropped_total",
                        this,
                        e -> {
                            final com.wikantik.audit.AuditService a = e.getAuditService();
                            return a == null ? 0d : (double) a.droppedCount();
                        } )
                    .description( "Audit entries dropped due to full queue" )
                    .register( meterRegistry );
            }

            // Build Knowledge subsystem core.
            final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Deps kgDeps =
                new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Deps(
                    ds, persistenceSubsystem, coreSubsystem, pageSubsystem, luceneMlt );
            final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services svcs =
                com.wikantik.knowledge.subsystem.KnowledgeSubsystemFactory.create( kgDeps );
            // Note: this.knowledgeSubsystem is intentionally assigned at the END
            // of this method, AFTER all wiring helpers have run. Each helper
            // calls engine.setManager(...) which invalidates the
            // knowledgeSubsystem snapshot (so it can be rebuilt on the next
            // legacy getManager lookup). Assigning the snapshot before the
            // helpers means it would be wiped to null before initialize()
            // reaches rebuildKnowledgeSubsystemWithPostConstructionServices,
            // and the WikiSubsystems stash would be skipped — leaving REST
            // endpoints to fall through to the synthetic bridge with null
            // hub-discovery / hub-proposal repositories. (Phase 9 Ckpt 4c-fix.)

            if ( svcs.kgService() instanceof DefaultKnowledgeGraphService dkgs ) {
                dkgs.setEngine( this );
            }

            // Resolve collaborators that multiple wiring helpers consume.
            final PageManager pageManager = getManager( PageManager.class );
            final FilterManager filterManager = getManager( FilterManager.class );
            final ReferenceManager referenceManager = getManager( ReferenceManager.class );
            final CachingManager cachingManager = getManager( CachingManager.class );

            // Wire structural spine + page graph (PageGraphWiringHelper).
            final com.wikantik.pagegraph.spine.DefaultStructuralIndexService structuralIndex =
                com.wikantik.pagegraph.subsystem.PageGraphWiringHelper.wireStructuralSpine(
                    props, persistenceSubsystem, coreSubsystem,
                    pageManager, filterManager, referenceManager, this );

            // Wire KG policy + ForAgent + ContentIndexRebuild (KnowledgeWiringHelper).
            final com.wikantik.admin.ContentIndexRebuildService rebuildService =
                com.wikantik.knowledge.subsystem.KnowledgeWiringHelper.wireKgPolicyAndContent(
                    props, structuralIndex, coreSubsystem, persistenceSubsystem,
                    svcs, searchMgr, meterRegistry, pageManager, cachingManager, referenceManager, this );

            // Wire hybrid retrieval (SearchWiringHelper).
            // Pass a FrontmatterMetadataCache so the embedding indexer can build
            // contextual document embeddings (title|cluster|section|summary). A fresh
            // instance over pageManager is fine — it's a bounded on-demand parse cache.
            com.wikantik.search.subsystem.SearchWiringHelper.wireHybridRetrieval(
                props, ds, svcs.chunkProjector(), svcs.contentChunkRepository(),
                new com.wikantik.search.FrontmatterMetadataCache( pageManager ),
                rebuildService, this );

            // Wire the RDF ontology runtime (OntologyWiringHelper): builds the TDB2-backed
            // OntologyModelManager + rebuild coordinator, registers the coordinator, and
            // kicks a startup-if-empty rebuild. setManager-only, so ArchUnit-neutral.
            final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontologyCoordinator =
                    com.wikantik.ontology.runtime.OntologyWiringHelper.wireOntology( this, props, ds, pageManager, filterManager );
            // Note: wireOntology's startup rebuildIfEmpty() has already run, so a first-boot
            // rebuild does NOT trigger a drift sweep — the first sweep comes from the nightly
            // scheduler or the admin dashboard. Intentional: keeps first deploy fast.
            com.wikantik.drift.DriftWiringHelper.wireDrift( this, props, ds, pageManager, ontologyCoordinator );

            // Wire citation subsystem (CitationWiringHelper): builds CitationRepository,
            // CitationSync, and CitationEventListener; registers both components on the engine;
            // wires reconcileAll() onto the ontology rebuild coordinator (if present).
            // The Wired holder is kept as a strong reference so the event listener is
            // not garbage-collected out of WikiEventManager's WeakHashMap.
            this.citationWired = com.wikantik.citation.CitationWiringHelper.wireCitations(
                this, props, ds, pageManager, filterManager, structuralIndex, ontologyCoordinator );

            // Wire entity extraction (KnowledgeWiringHelper).
            // KgExcludedPagesRepository is registered by wireKgPolicyAndContent above;
            // read it via getManager so the parameter is explicit in wireEntityExtraction.
            final com.wikantik.kgpolicy.KgExcludedPagesRepository excludedPagesRepo =
                getManager( com.wikantik.kgpolicy.KgExcludedPagesRepository.class );
            com.wikantik.knowledge.subsystem.KnowledgeWiringHelper.wireEntityExtraction(
                props, ds, svcs.chunkProjector(), svcs.contentChunkRepository(),
                persistenceSubsystem, excludedPagesRepo, this );

            // Wire graph rerank (SearchWiringHelper).
            com.wikantik.search.subsystem.SearchWiringHelper.wireGraphRerank( props, ds, this );

            // Wire retrieval-quality runner (SearchWiringHelper).
            // HybridSearchService + GraphRerankStep are registered by the two helpers above.
            final com.wikantik.search.hybrid.HybridSearchService hybridSearch =
                getManager( com.wikantik.search.hybrid.HybridSearchService.class );
            final com.wikantik.search.hybrid.GraphRerankStep graphRerankStep =
                getManager( com.wikantik.search.hybrid.GraphRerankStep.class );
            com.wikantik.search.subsystem.SearchWiringHelper.wireRetrievalQualityRunner(
                props, ds, structuralIndex, searchMgr, pageManager,
                hybridSearch, graphRerankStep, this );

            // Wire the retrieval query log (real-traffic capture for eval-corpus grounding; default on).
            this.setQueryLogService( com.wikantik.knowledge.querylog.QueryLogWiring.build( ds, props ) );
            this.setQueryLogReader( com.wikantik.knowledge.querylog.QueryLogWiring.buildReader( ds ) );

            // Wire the briefing log (S3 telemetry for session-start context briefings; default on).
            this.setBriefingLogService( com.wikantik.knowledge.querylog.BriefingLogWiring.build( ds, props ) );

            // Register save-time filters.
            filterManager.addPageFilter( svcs.chunkProjector(), -1005 );
            filterManager.addPageFilter( svcs.frontmatterDefaultsFilter(), -1004 );
            com.wikantik.pagegraph.subsystem.PageGraphWiringHelper.wireSpineFilters(
                props, structuralIndex, coreSubsystem, filterManager, pageManager, this );
            // PageOwnershipSaveFilter — postSave hook ensures every saved page has a
            // page_owners row. Uses StructuralIndexService::resolveCanonicalIdFromSlug
            // and PageOwnerService::getOwner (find-or-create). Gated by
            // wikantik.page_ownership.enforcement.enabled (default true).
            final boolean ownershipEnforcement = Boolean.parseBoolean(
                props.getProperty(
                    com.wikantik.comments.PageOwnershipSaveFilter.PROP_ENFORCEMENT_ENABLED,
                    "true" ) );
            filterManager.addPageFilter(
                new com.wikantik.comments.PageOwnershipSaveFilter(
                    persistenceSubsystem.pageOwners(),
                    structuralIndex::resolveCanonicalIdFromSlug,
                    ownershipEnforcement ),
                -998 );
            filterManager.addPageFilter( svcs.hubSyncFilter(), -999 );

            // Assign the typed snapshot ONLY after all helpers have run.
            // See note above next to the local 'svcs' assignment.
            this.knowledgeSubsystem = svcs;

            LOG.info( "HubProposalService registered (reviewPercentile property='{}')",
                props.getProperty( HubProposalService.PROP_REVIEW_PERCENTILE, "default" ) );
            LOG.info( "HubDiscoveryService registered (minClusterSize property='{}', minPts='{}')",
                props.getProperty( HubDiscoveryService.PROP_MIN_CLUSTER_SIZE, "default" ),
                props.getProperty( HubDiscoveryService.PROP_MIN_PTS, "default" ) );
            LOG.info( "Knowledge graph initialized with datasource '{}'", datasource );

        } catch ( final javax.naming.NamingException | RuntimeException e ) {
            LOG.warn( "Knowledge graph initialization failed: {}", e.getMessage(), e );
        }
    }

    /**
     * Construct the audit subsystem (JDBC repo + async writer), register the
     * {@link com.wikantik.audit.AuditEventListener} against the auth + page
     * managers, and build the {@link com.wikantik.audit.AuditReadPolicy}.
     * Called from {@link #initialize} independently of Knowledge-Graph init so
     * a KG failure cannot silently disable auditing.
     */
    private void initAuditSubsystem( final Properties props ) {
        // Resolve the DataSource independently of Knowledge-Graph init (same JNDI
        // lookup initKnowledgeGraph uses). Null when no datasource is configured.
        javax.sql.DataSource ds = null;
        try {
            final String datasource = props.getProperty(
                    AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
            ds = ( javax.sql.DataSource ) ctx.lookup( datasource );
        } catch ( final javax.naming.NamingException e ) {
            LOG.warn( "Audit subsystem: no JNDI DataSource resolved ({}); audit log disabled.",
                    e.getMessage() );
        }
        // Resolve the page manager + structural index from the registry (may be null
        // if their subsystems failed to build — read-gating degrades, audit still runs).
        final PageManager pageManager = getManager( PageManager.class );
        final com.wikantik.api.pagegraph.StructuralIndexService structuralIndex =
                getManager( com.wikantik.api.pagegraph.StructuralIndexService.class );

        if ( ds == null ) {
            LOG.warn( "Audit subsystem disabled — no JNDI DataSource resolved; "
                    + "security and page events will not be written to the audit log." );
            return;
        }

        final com.wikantik.audit.AuditSubsystemFactory.AuditSubsystem sub =
            com.wikantik.audit.AuditSubsystemFactory.build( ds, 10_000 );
        this.auditService = sub.service();
        this.auditWriter = sub.writer();

        // Frontmatter lookup — never throws; returns an empty map on any failure.
        // Page providers do not populate Page.FRONTMATTER_METADATA, so we parse
        // the raw page text via FrontmatterParser to extract the frontmatter map.
        final var pmForLookup = getManager( com.wikantik.api.managers.PageManager.class );
        final java.util.function.Function< String, java.util.Map< String, Object > > frontmatterByPage = pageName -> {
            try {
                if ( pmForLookup == null ) return java.util.Map.of();
                final String raw = pmForLookup.getPureText( pageName,
                        com.wikantik.api.providers.PageProvider.LATEST_VERSION );
                if ( raw == null || raw.isEmpty() ) return java.util.Map.of();
                final com.wikantik.api.frontmatter.ParsedPage parsed =
                        com.wikantik.api.frontmatter.FrontmatterParser.parse( raw );
                final java.util.Map< String, Object > fm = parsed.metadata();
                return fm != null ? fm : java.util.Map.of();
            } catch ( final Exception e ) {
                LOG.warn( "audit frontmatter lookup({}) failed: {}", pageName, e.getMessage(), e );
                return java.util.Map.of();
            }
        };

        // Cluster lookup — mirrors DefaultKgInclusionPolicy.pageDescriptor: resolve
        // canonical_id from slug, then the descriptor, then its cluster. Returns
        // null (read-auditing simply won't trigger by cluster) on any failure.
        final java.util.function.Function< String, String > clusterByPage = pageName -> {
            try {
                if ( structuralIndex == null ) return null;
                return structuralIndex.resolveCanonicalIdFromSlug( pageName )
                    .flatMap( structuralIndex::getByCanonicalId )
                    .map( com.wikantik.api.pagegraph.PageDescriptor::cluster )
                    .orElse( null );
            } catch ( final Exception e ) {
                LOG.warn( "audit cluster lookup({}) failed: {}", pageName, e.getMessage(), e );
                return null;
            }
        };

        final java.util.Set< String > auditedClusters = parseAuditedClusters(
            props.getProperty( "wikantik.audit.readClusters", "" ) );

        this.auditReadPolicy =
            new com.wikantik.audit.AuditReadPolicy( frontmatterByPage, clusterByPage, auditedClusters );

        // Register the event listener against every manager that fires the events
        // the audit listener consumes: authn/authz/user/group (WikiSecurityEvent)
        // and the page manager (WikiPageEvent / WikiPageRenameEvent).
        // Store in a field so the instance is strongly reachable and won't be
        // evicted from WikiEventManager's WeakHashMap by the garbage collector.
        this.auditEventListener = new com.wikantik.audit.AuditEventListener( this.auditService );

        final AuthenticationManager authnMgr = getManager( AuthenticationManager.class );
        if ( authnMgr != null ) authnMgr.addWikiEventListener( auditEventListener );
        final AuthorizationManager authzMgr = getManager( AuthorizationManager.class );
        if ( authzMgr != null ) authzMgr.addWikiEventListener( auditEventListener );
        final UserManager userMgr = getManager( UserManager.class );
        if ( userMgr != null ) userMgr.addWikiEventListener( auditEventListener );
        // Last-login stamping: LOGIN_AUTHENTICATED fires from the authentication manager,
        // so register the listener there. The user database is the write target.
        if ( authnMgr != null && userMgr != null && userMgr.getUserDatabase() != null ) {
            this.lastLoginEventListener = new com.wikantik.auth.LastLoginEventListener( userMgr.getUserDatabase() );
            authnMgr.addWikiEventListener( this.lastLoginEventListener );
        }
        final GroupManager groupMgr = getManager( GroupManager.class );
        if ( groupMgr != null ) groupMgr.addWikiEventListener( auditEventListener );
        // PageManager has no addWikiEventListener on its interface; it fires page
        // events via WikiEventManager keyed on the manager instance, so register
        // the listener against that instance directly (same mechanism).
        if ( pageManager != null ) {
            com.wikantik.event.WikiEventManager.addWikiEventListener( pageManager, auditEventListener );
        }
        // DefaultPageRenamer fires WikiPageRenameEvent (which carries the detail
        // field with {"from":...,"to":...}) keyed on itself, not on PageManager.
        // Register the same listener against the PageRenamer instance so rename
        // events are captured by the audit chain.
        final com.wikantik.content.PageRenamer pageRenamer = getManager( com.wikantik.content.PageRenamer.class );
        if ( pageRenamer != null ) {
            com.wikantik.event.WikiEventManager.addWikiEventListener( pageRenamer, auditEventListener );
        }

        LOG.info( "Audit subsystem initialized (queue=10000)" );
    }

    /** Parse the comma-separated {@code wikantik.audit.readClusters} value into a trimmed, non-empty set. */
    private static java.util.Set< String > parseAuditedClusters( final String raw ) {
        if ( raw == null || raw.isBlank() ) return java.util.Set.of();
        final java.util.Set< String > out = new java.util.HashSet<>();
        for ( final String part : raw.split( "," ) ) {
            final String trimmed = part.trim();
            if ( !trimmed.isEmpty() ) out.add( trimmed );
        }
        return out;
    }

    /**
     * Phase 7 Ckpt 4 — post-construction wire of the {@link HubOverviewService.LuceneMlt}
     * seam. Called after both the Knowledge and Search subsystems are
     * built so the cycle (Search depends on Knowledge for graph rerank;
     * Knowledge needs Lucene for MoreLikeThis) is broken: Knowledge
     * constructs with the no-op MLT default, Search constructs, then this
     * method walks the live {@link LuceneSearchProvider} and installs a
     * delegating MLT onto {@link HubOverviewService}. Skipped silently
     * (modulo a debug log) when Knowledge didn't initialise or when the
     * configured search provider isn't Lucene.
     *
     * <p>Delegates to the decomposed {@link com.wikantik.search.subsystem.lucene.LuceneSearcher}
     * (Phase 7 Ckpt 3) when available, falling back to the facade's own
     * {@code moreLikeThis} method for safety. Either path produces an
     * identical wire result; the facade just adds one delegation hop.</p>
     */
    private void wireLuceneMltPostConstruction() {
        if ( knowledgeSubsystem == null ) {
            return;
        }
        final HubOverviewService hub = knowledgeSubsystem.hubOverviewService();
        if ( hub == null ) {
            return;
        }
        final SearchProvider sp = searchSubsystem != null ? searchSubsystem.searchProvider() : null;
        if ( !( sp instanceof LuceneSearchProvider lsp ) ) {
            LOG.debug( "wireLuceneMltPostConstruction: SearchProvider is not Lucene "
                + "(actual={}); leaving HubOverviewService MLT as no-op.",
                sp == null ? "null" : sp.getClass().getName() );
            return;
        }
        final HubOverviewService.LuceneMlt mlt = ( seed, max, excludes ) -> {
            final var hits = lsp.moreLikeThis( seed, max, excludes );
            final java.util.List< HubOverviewService.MoreLikeThisLucene > out =
                new java.util.ArrayList<>( hits.size() );
            for ( final var h : hits ) {
                out.add( new HubOverviewService.MoreLikeThisLucene( h.name(), h.score() ) );
            }
            return out;
        };
        hub.setLuceneMlt( mlt );
        LOG.info( "wireLuceneMltPostConstruction: HubOverviewService.LuceneMlt wired "
            + "to live LuceneSearchProvider." );
    }


    /** {@inheritDoc} */
    @Override
    public Properties getWikiProperties() {
        return properties;
    }

    /**
     * The persistence-subsystem's {@link com.wikantik.pagegraph.spine.PageCanonicalIdsDao},
     * or {@code null} before the persistence subsystem is built. Exposed so post-startup
     * wiring (e.g. {@code BundleServiceWiring}) can resolve slug → canonical_id without
     * re-resolving the JNDI datasource.
     */
    public com.wikantik.pagegraph.spine.PageCanonicalIdsDao pageCanonicalIdsDao() {
        return persistenceSubsystem != null ? persistenceSubsystem.pageCanonicalIds() : null;
    }

    /** Per-mode context-bundle candidate sources; set at search wiring, read at the retrieval-patch seam.
     *  Null when the dense index is unavailable (bundle then falls back to the page-gated path).
     *  A plain field — not a manager — so it carries no snapshot machinery. */
    private volatile Map< com.wikantik.api.bundle.RetrievalMode,
                          com.wikantik.knowledge.bundle.SectionCandidateSource > bundleSectionSources;

    /** Called by {@code SearchWiringHelper} once the dense index + query embedder are built. */
    public void setBundleSectionSources( final Map< com.wikantik.api.bundle.RetrievalMode,
                                                    com.wikantik.knowledge.bundle.SectionCandidateSource > sources ) {
        this.bundleSectionSources = sources;
    }

    /** The per-mode dense-chunk candidate sources, or {@code null} if the dense index was not wired. */
    public Map< com.wikantik.api.bundle.RetrievalMode,
                com.wikantik.knowledge.bundle.SectionCandidateSource > bundleSectionSources() {
        return bundleSectionSources;
    }

    /** The single canonical dense index, wired by {@code SearchWiringHelper} and read by
     *  {@code SearchSubsystemFactory}. A typed field instead of the manager registry to
     *  satisfy the no-new-getManager-callers architecture rule. */
    private volatile com.wikantik.search.hybrid.ChunkVectorIndex chunkVectorIndex;

    /** Called by {@code SearchWiringHelper} once the dense index backend has been constructed. */
    public void setChunkVectorIndex( final com.wikantik.search.hybrid.ChunkVectorIndex index ) {
        this.chunkVectorIndex = index;
    }

    /** The wired dense index, or {@code null} if it has not been wired yet. */
    public com.wikantik.search.hybrid.ChunkVectorIndex getChunkVectorIndex() {
        return chunkVectorIndex;
    }

    /** Retrieval-query log; set at startup, read by the retrieval endpoints. Null when disabled
     *  or not yet wired (callers no-op). A plain field — carries no snapshot machinery. */
    private volatile com.wikantik.api.querylog.QueryLogService queryLogService;

    /** Called at startup once the persistence DataSource is available. */
    public void setQueryLogService( final com.wikantik.api.querylog.QueryLogService service ) {
        this.queryLogService = service;
    }

    /** The retrieval-query log service, or {@code null} if logging was not wired. */
    public com.wikantik.api.querylog.QueryLogService queryLogService() {
        return queryLogService;
    }

    private volatile com.wikantik.api.querylog.QueryLogReader queryLogReader;

    /** Called at startup once the persistence DataSource is available. */
    public void setQueryLogReader( final com.wikantik.api.querylog.QueryLogReader reader ) {
        this.queryLogReader = reader;
    }

    /** The query-log read side; {@code null} when no datasource is configured. */
    public com.wikantik.api.querylog.QueryLogReader queryLogReader() {
        return queryLogReader;
    }

    /** Briefing-request log; set at startup, read by nothing yet (write-only telemetry). Null
     *  when disabled or not yet wired (callers no-op). A plain field — carries no snapshot
     *  machinery. */
    private volatile com.wikantik.api.briefing.BriefingLogService briefingLogService;

    /** Called at startup once the persistence DataSource is available. */
    public void setBriefingLogService( final com.wikantik.api.briefing.BriefingLogService service ) {
        this.briefingLogService = service;
    }

    /** The briefing-log service, or {@code null} if logging was not wired. */
    public com.wikantik.api.briefing.BriefingLogService briefingLogService() {
        return briefingLogService;
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

    /**
     * Rebuilds the {@link com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services}
     * record by copying the original 16 core fields and filling in the six
     * post-construction services that were registered into the manager map
     * after {@code KnowledgeSubsystemFactory.create()} returned.
     *
     * <p>Phase 8 Ckpt 1.5 of the wikantik-main decomposition. Called once
     * from {@code initialize()} just before the {@code WikiSubsystems} bundle
     * is stashed on the {@code ServletContext}. {@code ContextRetrievalService}
     * is intentionally left null here — it is wired by
     * {@code ContextRetrievalServiceInitializer} (a {@code ServletContextListener})
     * after the engine starts and cannot be present at this point.</p>
     */
    private com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services
    rebuildKnowledgeSubsystemWithPostConstructionServices(
            final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services base ) {
        return new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(
            base.kgService(),
            base.judgeService(),
            base.judgeRunner(),
            base.kgMaterialization(),
            base.judgeTimeoutRepository(),
            base.hubProposalService(),
            base.hubDiscoveryService(),
            base.hubOverviewService(),
            base.hubProposalRepository(),
            base.hubDiscoveryRepository(),
            base.contentChunkRepository(),
            base.chunkProjector(),
            base.mentionIndex(),
            base.nodeMentionSimilarity(),
            base.frontmatterDefaultsFilter(),
            base.hubSyncFilter(),
            /* contextRetrievalService — set by servlet listener post-boot */      null,
            getManager( com.wikantik.api.agent.ForAgentProjectionService.class ),
            getManager( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class ),
            getManager( com.wikantik.api.kgpolicy.KgInclusionPolicy.class ),
            getManager( com.wikantik.kgpolicy.ReconciliationJobRunner.class ),
            getManager( com.wikantik.api.eval.RetrievalQualityRunner.class ),
            base.kgCurationOps(),
            /* bundleAssemblyService — derived from contextRetrievalService, which is null
               here (wired post-boot); built later at patchContextRetrievalService */ null,
            /* briefingAssemblyService — derived from the bundle service, likewise null
               here; built later at patchContextRetrievalService */ null
        );
    }

    /**
     * Returns the Knowledge subsystem's services bundle, or {@code null} if
     * the subsystem failed to initialize or the engine ran without a
     * knowledge graph datasource.
     *
     * <p>Phase 1 of the wikantik-main subsystem decomposition. New code
     * should obtain Knowledge services this way; legacy code uses
     * {@link #getManager(Class)} until migrated.</p>
     */
    public com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services getKnowledgeSubsystem() {
        return knowledgeSubsystem;
    }

    /**
     * Patches the live {@link WikiSubsystems} stash and the local
     * {@code knowledgeSubsystem} field with the supplied
     * {@link com.wikantik.api.knowledge.ContextRetrievalService} instance.
     *
     * <p>{@code ContextRetrievalService} cannot be placed in the stash at
     * engine-boot time because it is wired by
     * {@code ContextRetrievalServiceInitializer} (a {@code ServletContextListener})
     * that fires after {@code initialize()} returns. Call this method from that
     * listener once the service is available so that servlet callers reading
     * {@code getSubsystems().knowledge().contextRetrievalService()} get the
     * live service rather than {@code null}.</p>
     *
     * <p>If the knowledge subsystem is not present (no datasource) this is a
     * no-op.</p>
     */
    public synchronized void patchContextRetrievalService(
            final com.wikantik.api.knowledge.ContextRetrievalService svc ) {
        if ( knowledgeSubsystem == null || servletContext == null ) return;
        // slug -> Confidence lookup for the 'metadata-boost' rerank stage: resolve slug -> canonical_id
        // via the persistence subsystem's dao, then canonical_id -> Verification.confidence() via the
        // structural index. Null (stage skipped) when the page-graph subsystem isn't wired.
        final com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Services pg = this.pageGraphSubsystem;
        final com.wikantik.api.pagegraph.StructuralIndexService structuralIndex =
            pg != null ? pg.structuralIndexService() : null;
        final java.util.function.Function< String, com.wikantik.api.pagegraph.Confidence > confidenceOf =
            structuralIndex == null ? null : slug -> {
                final com.wikantik.pagegraph.spine.PageCanonicalIdsDao dao = pageCanonicalIdsDao();
                if ( dao == null ) return com.wikantik.api.pagegraph.Confidence.PROVISIONAL;
                return dao.findBySlug( slug )
                    .map( com.wikantik.pagegraph.spine.PageCanonicalIdsDao.Row::canonicalId )
                    .flatMap( structuralIndex::verificationOf )
                    .map( com.wikantik.api.pagegraph.Verification::confidence )
                    .orElse( com.wikantik.api.pagegraph.Confidence.PROVISIONAL );
            };
        // Build the bundle service once and reuse it: the briefing assembler layers
        // on top of it (prompt-driven widening), so both derive from the same instance.
        final com.wikantik.api.bundle.BundleAssemblyService bundleSvc =
            com.wikantik.knowledge.bundle.BundleServiceWiring.build(
                svc,
                bundleSectionSources(),
                pageCanonicalIdsDao(),
                pageSubsystem != null ? pageSubsystem.pages() : null,
                properties,
                confidenceOf );

        // Scheduled retrieval-quality eval (disabled unless wikantik.bundle.eval.interval.hours > 0).
        // Same JNDI datasource lookup as initKnowledgeGraph()/initAuditSubsystem() — fail-soft to
        // null (the scheduler is still built, just persists nowhere until a run tries to write).
        javax.sql.DataSource evalDs = null;
        try {
            final String datasource = properties.getProperty(
                    AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
            evalDs = ( javax.sql.DataSource ) ctx.lookup( datasource );
        } catch ( final javax.naming.NamingException e ) {
            LOG.warn( "bundle-eval scheduler: no JNDI DataSource ({}); eval persistence disabled", e.getMessage() );
        }
        // Assumes patchContextRetrievalService is called once at startup; a re-entrant call
        // would build a second scheduler here without stopping the first (matches the
        // BundleServiceWiring build() above, which makes the same single-call assumption).
        final com.wikantik.knowledge.eval.BundleEvalScheduler bundleEvalScheduler =
            com.wikantik.knowledge.eval.BundleEvalWiring.build( bundleSvc, evalDs, properties );
        if ( bundleEvalScheduler != null ) {
            bundleEvalScheduler.start();
        }

        knowledgeSubsystem = new com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services(
            knowledgeSubsystem.kgService(),
            knowledgeSubsystem.judgeService(),
            knowledgeSubsystem.judgeRunner(),
            knowledgeSubsystem.kgMaterialization(),
            knowledgeSubsystem.judgeTimeoutRepository(),
            knowledgeSubsystem.hubProposalService(),
            knowledgeSubsystem.hubDiscoveryService(),
            knowledgeSubsystem.hubOverviewService(),
            knowledgeSubsystem.hubProposalRepository(),
            knowledgeSubsystem.hubDiscoveryRepository(),
            knowledgeSubsystem.contentChunkRepository(),
            knowledgeSubsystem.chunkProjector(),
            knowledgeSubsystem.mentionIndex(),
            knowledgeSubsystem.nodeMentionSimilarity(),
            knowledgeSubsystem.frontmatterDefaultsFilter(),
            knowledgeSubsystem.hubSyncFilter(),
            svc,
            knowledgeSubsystem.forAgentProjectionService(),
            knowledgeSubsystem.bootstrapEntityExtractionIndexer(),
            knowledgeSubsystem.kgInclusionPolicy(),
            knowledgeSubsystem.reconciliationJobRunner(),
            knowledgeSubsystem.retrievalQualityRunner(),
            knowledgeSubsystem.kgCurationOps(),
            // bundleAssemblyService — DERIVED from the now-live retrieval service (svc).
            // Collaborators are passed from typed accessors (no getManager) so the wiring
            // helper stays a plain assembler. Null-safe: build returns null if svc is null.
            bundleSvc,
            // briefingAssemblyService — DERIVED from the just-built bundle service plus the
            // structural index. Both collaborators are nullable (degraded briefing still
            // answers explicit pins); requires only a live PageManager. Null-safe build.
            com.wikantik.knowledge.briefing.BriefingServiceWiring.build(
                bundleSvc,
                structuralIndexOrNull(),
                pageSubsystem != null ? pageSubsystem.pages() : null,
                properties )
        );
        // Rebuild the full WikiSubsystems stash so servlet callers see the updated record.
        final WikiSubsystems current =
            (WikiSubsystems) servletContext.getAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE );
        if ( current != null ) {
            servletContext.setAttribute(
                WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE,
                new WikiSubsystems(
                    current.core(), current.persistence(), current.auth(),
                    current.page(), current.rendering(), current.search(),
                    knowledgeSubsystem, current.pageGraph() ) );
        }
    }

    /**
     * Resolves the {@link com.wikantik.api.pagegraph.StructuralIndexService} for the
     * briefing wiring via the Page Graph subsystem bridge, degrading to {@code null}
     * if the bridge is unavailable (the briefing then skips cluster expansion rather
     * than failing). Never throws.
     */
    private com.wikantik.api.pagegraph.StructuralIndexService structuralIndexOrNull() {
        try {
            return com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge
                .fromLegacyEngine( this ).structuralIndexService();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Structural index unavailable for briefing wiring: {}", e.getMessage() );
            return null;
        }
    }

    /**
     * Returns the Core subsystem's services bundle, or {@code null} when the
     * engine has not yet completed initialization.
     *
     * <p>Phase 2 of the wikantik-main subsystem decomposition. New code
     * should obtain typed properties, the event bus, the metrics registry,
     * and the leaf managers (SystemPageRegistry, RecentArticlesManager,
     * BlogManager) through this accessor.</p>
     */
    public com.wikantik.core.subsystem.CoreSubsystem.Services getCoreSubsystem() {
        return coreSubsystem;
    }

    /**
     * Returns the Persistence subsystem's services bundle, or {@code null}
     * when the engine booted without a configured datasource (unit-test
     * paths).
     *
     * <p>Phase 3 of the wikantik-main subsystem decomposition. New code
     * should obtain JDBC repositories / DAOs through this accessor; legacy
     * code constructs them inline until migrated.</p>
     */
    public com.wikantik.persistence.subsystem.PersistenceSubsystem.Services getPersistenceSubsystem() {
        return persistenceSubsystem;
    }

    /**
     * Returns the Auth subsystem's services bundle, or {@code null} when
     * the engine has not yet completed initialization.
     *
     * <p>Phase 4 of the wikantik-main subsystem decomposition. New code
     * should obtain the auth managers, web-container authorizer, and
     * API-key service through this accessor.</p>
     */
    public com.wikantik.auth.subsystem.AuthSubsystem.Services getAuthSubsystem() {
        return authSubsystem;
    }

    /**
     * Returns the Page subsystem's services bundle, or {@code null} when
     * the engine has not yet completed initialization.
     *
     * <p>Phase 5 of the wikantik-main subsystem decomposition. New code
     * should obtain {@link com.wikantik.api.managers.PageManager},
     * {@link com.wikantik.api.managers.AttachmentManager},
     * {@link com.wikantik.content.PageRenamer}, the page-save helper, and
     * the underlying {@link com.wikantik.api.providers.PageProvider} chain
     * through this accessor.</p>
     */
    public com.wikantik.page.subsystem.PageSubsystem.Services getPageSubsystem() {
        return pageSubsystem;
    }

    /**
     * Returns the Rendering subsystem's services bundle, or {@code null}
     * when the engine has not yet completed initialization.
     *
     * <p>Phase 6 of the wikantik-main subsystem decomposition. New code
     * should obtain {@link com.wikantik.render.RenderingManager},
     * {@link com.wikantik.plugin.PluginManager},
     * {@link com.wikantik.filters.FilterManager}, and
     * {@link com.wikantik.diff.DifferenceManager} through this accessor.
     * Phase 6 Ckpt 4 will additionally expose the four decomposed
     * SpamFilter helpers on the same record.</p>
     */
    public com.wikantik.render.subsystem.RenderingSubsystem.Services getRenderingSubsystem() {
        return renderingSubsystem;
    }

    /**
     * Returns the Search subsystem's services bundle, or {@code null}
     * when the engine has not yet completed initialization.
     *
     * <p>Phase 7 of the wikantik-main subsystem decomposition. New code
     * should obtain {@link com.wikantik.search.SearchManager},
     * {@link com.wikantik.search.SearchProvider}, hybrid retrieval
     * services, and the embedding pipeline through this accessor. Phase 7
     * Ckpt 4 will additionally expose the three decomposed Lucene helpers
     * on the same record.</p>
     */
    public com.wikantik.search.subsystem.SearchSubsystem.Services getSearchSubsystem() {
        return searchSubsystem;
    }

    /**
     * Returns the Page Graph subsystem's services bundle, or {@code null}
     * when the engine has not yet completed initialization.
     *
     * <p>Phase 9 Checkpoint 1 of the wikantik-main subsystem decomposition. New
     * code should obtain {@link com.wikantik.api.pagegraph.StructuralIndexService},
     * {@link com.wikantik.api.pagegraph.PageGraphService},
     * {@link com.wikantik.api.managers.ReferenceManager}, and
     * {@link com.wikantik.admin.ContentIndexRebuildService} through this
     * accessor rather than via {@code getManager(Class)}.</p>
     */
    public com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Services getPageGraphSubsystem() {
        return pageGraphSubsystem;
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
        final com.wikantik.search.embedding.AsyncEmbeddingIndexListener hybridIndexListener =
            serviceRegistry.get( com.wikantik.search.embedding.AsyncEmbeddingIndexListener.class );
        if ( hybridIndexListener != null ) {
            try { hybridIndexListener.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridIndexListener close failed: {}", e.getMessage(), e ); }
        }
        final com.wikantik.knowledge.extraction.AsyncEntityExtractionListener entityExtractionListener =
            serviceRegistry.get( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class );
        if ( entityExtractionListener != null ) {
            try { entityExtractionListener.close(); }
            catch( final RuntimeException e ) { LOG.warn( "entityExtractionListener close failed: {}", e.getMessage(), e ); }
        }
        final com.wikantik.search.hybrid.QueryEmbedder hybridQueryEmbedder =
            serviceRegistry.get( com.wikantik.search.hybrid.QueryEmbedder.class );
        if ( hybridQueryEmbedder != null ) {
            try { hybridQueryEmbedder.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridQueryEmbedder close failed: {}", e.getMessage(), e ); }
        }
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer hybridBootstrapIndexer =
            serviceRegistry.get( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class );
        if ( hybridBootstrapIndexer != null ) {
            try { hybridBootstrapIndexer.close(); }
            catch( final RuntimeException e ) { LOG.warn( "hybridBootstrapIndexer close failed: {}", e.getMessage(), e ); }
        }
        if ( auditWriter != null ) {
            auditWriter.shutdownWriter();
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
