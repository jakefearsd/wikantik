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
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;


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
     *  KG-flavored service registrations go through typed backing fields.
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

    // Ckpt A2: managers Map deleted — all reads/writes go through typed backing fields.

    // -----------------------------------------------------------------------
    // Phase 11 Ckpt 1: static class→writer/reader maps replace the 75-arm if-chains.
    // IdentityHashMap gives O(1) reference-equality lookup (Class.hashCode is
    // identity-based, but IdentityHashMap makes the intent explicit and avoids
    // equals() dispatch).
    // -----------------------------------------------------------------------
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    private static final Map<Class<?>, BiConsumer<WikiEngine, Object>> TYPED_FIELD_WRITERS;
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    private static final Map<Class<?>, Function<WikiEngine, Object>> TYPED_FIELD_READERS;

    static {
        final IdentityHashMap<Class<?>, BiConsumer<WikiEngine, Object>> w = new IdentityHashMap<>( 128 );
        // Auth
        w.put( com.wikantik.auth.AuthenticationManager.class,      ( e, m ) -> e.mgr_AuthenticationManager      = (com.wikantik.auth.AuthenticationManager) m );
        w.put( com.wikantik.auth.AuthorizationManager.class,       ( e, m ) -> e.mgr_AuthorizationManager       = (com.wikantik.auth.AuthorizationManager) m );
        w.put( com.wikantik.auth.UserManager.class,                ( e, m ) -> e.mgr_UserManager                = (com.wikantik.auth.UserManager) m );
        w.put( com.wikantik.auth.authorize.GroupManager.class,     ( e, m ) -> e.mgr_GroupManager               = (com.wikantik.auth.authorize.GroupManager) m );
        w.put( com.wikantik.auth.acl.AclManager.class,             ( e, m ) -> e.mgr_AclManager                 = (com.wikantik.auth.acl.AclManager) m );
        // Page
        w.put( com.wikantik.api.managers.PageManager.class,        ( e, m ) -> e.mgr_PageManager                = (com.wikantik.api.managers.PageManager) m );
        w.put( com.wikantik.api.managers.AttachmentManager.class,  ( e, m ) -> e.mgr_AttachmentManager          = (com.wikantik.api.managers.AttachmentManager) m );
        w.put( com.wikantik.content.PageRenamer.class,             ( e, m ) -> e.mgr_PageRenamer                = (com.wikantik.content.PageRenamer) m );
        w.put( com.wikantik.api.managers.ReferenceManager.class,   ( e, m ) -> e.mgr_ReferenceManager           = (com.wikantik.api.managers.ReferenceManager) m );
        // Core
        w.put( com.wikantik.cache.CachingManager.class,                   ( e, m ) -> e.mgr_CachingManager                   = (com.wikantik.cache.CachingManager) m );
        w.put( com.wikantik.variables.VariableManager.class,              ( e, m ) -> e.mgr_VariableManager                  = (com.wikantik.variables.VariableManager) m );
        w.put( com.wikantik.ui.progress.ProgressManager.class,            ( e, m ) -> e.mgr_ProgressManager                  = (com.wikantik.ui.progress.ProgressManager) m );
        w.put( com.wikantik.ui.CommandResolver.class,                     ( e, m ) -> e.mgr_CommandResolver                  = (com.wikantik.ui.CommandResolver) m );
        w.put( com.wikantik.url.URLConstructor.class,                     ( e, m ) -> e.mgr_URLConstructor                   = (com.wikantik.url.URLConstructor) m );
        w.put( com.wikantik.i18n.InternationalizationManager.class,       ( e, m ) -> e.mgr_InternationalizationManager      = (com.wikantik.i18n.InternationalizationManager) m );
        w.put( com.wikantik.api.managers.SystemPageRegistry.class,        ( e, m ) -> e.mgr_SystemPageRegistry               = (com.wikantik.api.managers.SystemPageRegistry) m );
        w.put( com.wikantik.content.RecentArticlesManager.class,          ( e, m ) -> e.mgr_RecentArticlesManager            = (com.wikantik.content.RecentArticlesManager) m );
        w.put( com.wikantik.blog.BlogManager.class,                       ( e, m ) -> e.mgr_BlogManager                      = (com.wikantik.blog.BlogManager) m );
        // Rendering
        w.put( com.wikantik.render.RenderingManager.class,         ( e, m ) -> e.mgr_RenderingManager           = (com.wikantik.render.RenderingManager) m );
        w.put( com.wikantik.plugin.PluginManager.class,            ( e, m ) -> e.mgr_PluginManager              = (com.wikantik.plugin.PluginManager) m );
        w.put( com.wikantik.filters.FilterManager.class,           ( e, m ) -> e.mgr_FilterManager              = (com.wikantik.filters.FilterManager) m );
        w.put( com.wikantik.diff.DifferenceManager.class,          ( e, m ) -> e.mgr_DifferenceManager          = (com.wikantik.diff.DifferenceManager) m );
        w.put( com.wikantik.content.NewsPageGenerator.class,       ( e, m ) -> e.mgr_NewsPageGenerator          = (com.wikantik.content.NewsPageGenerator) m );
        // Search
        w.put( com.wikantik.search.SearchManager.class,                                  ( e, m ) -> e.mgr_SearchManager                = (com.wikantik.search.SearchManager) m );
        w.put( com.wikantik.search.SearchProvider.class,                                 ( e, m ) -> e.mgr_SearchProvider               = (com.wikantik.search.SearchProvider) m );
        w.put( com.wikantik.search.hybrid.HybridSearchService.class,                     ( e, m ) -> e.mgr_HybridSearchService          = (com.wikantik.search.hybrid.HybridSearchService) m );
        w.put( com.wikantik.search.hybrid.QueryEmbedder.class,                           ( e, m ) -> e.mgr_QueryEmbedder                = (com.wikantik.search.hybrid.QueryEmbedder) m );
        w.put( com.wikantik.search.hybrid.QueryEntityResolver.class,                     ( e, m ) -> e.mgr_QueryEntityResolver          = (com.wikantik.search.hybrid.QueryEntityResolver) m );
        w.put( com.wikantik.search.hybrid.GraphRerankStep.class,                         ( e, m ) -> e.mgr_GraphRerankStep              = (com.wikantik.search.hybrid.GraphRerankStep) m );
        w.put( com.wikantik.search.hybrid.GraphProximityScorer.class,                    ( e, m ) -> e.mgr_GraphProximityScorer         = (com.wikantik.search.hybrid.GraphProximityScorer) m );
        w.put( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class,                ( e, m ) -> e.mgr_InMemoryChunkVectorIndex      = (com.wikantik.search.hybrid.InMemoryChunkVectorIndex) m );
        w.put( com.wikantik.search.hybrid.ChunkVectorIndex.class,                        ( e, m ) -> e.mgr_ChunkVectorIndex             = (com.wikantik.search.hybrid.ChunkVectorIndex) m );
        w.put( com.wikantik.search.hybrid.InMemoryGraphNeighborIndex.class,              ( e, m ) -> e.mgr_InMemoryGraphNeighborIndex   = (com.wikantik.search.hybrid.InMemoryGraphNeighborIndex) m );
        w.put( com.wikantik.search.hybrid.GraphNeighborIndex.class,                      ( e, m ) -> e.mgr_GraphNeighborIndex           = (com.wikantik.search.hybrid.GraphNeighborIndex) m );
        w.put( com.wikantik.search.hybrid.PageMentionsLoader.class,                      ( e, m ) -> e.mgr_PageMentionsLoader           = (com.wikantik.search.hybrid.PageMentionsLoader) m );
        w.put( com.wikantik.search.embedding.EmbeddingIndexService.class,                ( e, m ) -> e.mgr_EmbeddingIndexService        = (com.wikantik.search.embedding.EmbeddingIndexService) m );
        w.put( com.wikantik.search.embedding.OllamaEmbeddingClient.class,                ( e, m ) -> e.mgr_OllamaEmbeddingClient        = (com.wikantik.search.embedding.OllamaEmbeddingClient) m );
        w.put( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class,            ( e, m ) -> e.mgr_BootstrapEmbeddingIndexer    = (com.wikantik.search.embedding.BootstrapEmbeddingIndexer) m );
        w.put( com.wikantik.search.embedding.AsyncEmbeddingIndexListener.class,          ( e, m ) -> e.mgr_AsyncEmbeddingIndexListener  = (com.wikantik.search.embedding.AsyncEmbeddingIndexListener) m );
        w.put( com.wikantik.search.FrontmatterMetadataCache.class,                       ( e, m ) -> e.mgr_FrontmatterMetadataCache     = (com.wikantik.search.FrontmatterMetadataCache) m );
        w.put( com.wikantik.search.subsystem.lucene.LuceneIndexer.class,                 ( e, m ) -> e.mgr_LuceneIndexer               = (com.wikantik.search.subsystem.lucene.LuceneIndexer) m );
        w.put( com.wikantik.search.subsystem.lucene.LuceneSearcher.class,                ( e, m ) -> e.mgr_LuceneSearcher              = (com.wikantik.search.subsystem.lucene.LuceneSearcher) m );
        w.put( com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle.class,          ( e, m ) -> e.mgr_LuceneIndexLifecycle        = (com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle) m );
        // Page Graph
        w.put( com.wikantik.api.pagegraph.StructuralIndexService.class,                  ( e, m ) -> e.mgr_StructuralIndexService       = (com.wikantik.api.pagegraph.StructuralIndexService) m );
        w.put( com.wikantik.api.pagegraph.PageGraphService.class,                        ( e, m ) -> e.mgr_PageGraphService             = (com.wikantik.api.pagegraph.PageGraphService) m );
        w.put( com.wikantik.admin.ContentIndexRebuildService.class,                      ( e, m ) -> e.mgr_ContentIndexRebuildService   = (com.wikantik.admin.ContentIndexRebuildService) m );
        w.put( com.wikantik.pagegraph.spine.PageVerificationDao.class,                   ( e, m ) -> e.mgr_PageVerificationDao          = (com.wikantik.pagegraph.spine.PageVerificationDao) m );
        w.put( com.wikantik.pagegraph.spine.TrustedAuthorsDao.class,                     ( e, m ) -> e.mgr_TrustedAuthorsDao            = (com.wikantik.pagegraph.spine.TrustedAuthorsDao) m );
        w.put( com.wikantik.pagegraph.spine.StructuralIndexEventListener.class,          ( e, m ) -> e.mgr_StructuralIndexEventListener = (com.wikantik.pagegraph.spine.StructuralIndexEventListener) m );
        // Knowledge
        w.put( com.wikantik.api.knowledge.KnowledgeGraphService.class,                         ( e, m ) -> e.mgr_KnowledgeGraphService              = (com.wikantik.api.knowledge.KnowledgeGraphService) m );
        w.put( com.wikantik.api.knowledge.KgProposalJudgeService.class,                        ( e, m ) -> e.mgr_KgProposalJudgeService             = (com.wikantik.api.knowledge.KgProposalJudgeService) m );
        w.put( com.wikantik.knowledge.judge.JudgeRunner.class,                                 ( e, m ) -> e.mgr_JudgeRunner                        = (com.wikantik.knowledge.judge.JudgeRunner) m );
        w.put( com.wikantik.knowledge.judge.KgMaterializationService.class,                    ( e, m ) -> e.mgr_KgMaterializationService           = (com.wikantik.knowledge.judge.KgMaterializationService) m );
        w.put( com.wikantik.knowledge.judge.KgJudgeTimeoutRepository.class,                    ( e, m ) -> e.mgr_KgJudgeTimeoutRepository           = (com.wikantik.knowledge.judge.KgJudgeTimeoutRepository) m );
        w.put( com.wikantik.knowledge.HubProposalService.class,                                ( e, m ) -> e.mgr_HubProposalService                 = (com.wikantik.knowledge.HubProposalService) m );
        w.put( com.wikantik.knowledge.HubDiscoveryService.class,                               ( e, m ) -> e.mgr_HubDiscoveryService                = (com.wikantik.knowledge.HubDiscoveryService) m );
        w.put( com.wikantik.knowledge.HubOverviewService.class,                                ( e, m ) -> e.mgr_HubOverviewService                 = (com.wikantik.knowledge.HubOverviewService) m );
        w.put( com.wikantik.knowledge.HubProposalRepository.class,                             ( e, m ) -> e.mgr_HubProposalRepository              = (com.wikantik.knowledge.HubProposalRepository) m );
        w.put( com.wikantik.knowledge.HubDiscoveryRepository.class,                            ( e, m ) -> e.mgr_HubDiscoveryRepository             = (com.wikantik.knowledge.HubDiscoveryRepository) m );
        w.put( com.wikantik.knowledge.chunking.ContentChunkRepository.class,                   ( e, m ) -> e.mgr_ContentChunkRepository             = (com.wikantik.knowledge.chunking.ContentChunkRepository) m );
        w.put( com.wikantik.knowledge.chunking.ChunkProjector.class,                           ( e, m ) -> e.mgr_ChunkProjector                     = (com.wikantik.knowledge.chunking.ChunkProjector) m );
        w.put( com.wikantik.knowledge.MentionIndex.class,                                      ( e, m ) -> e.mgr_MentionIndex                       = (com.wikantik.knowledge.MentionIndex) m );
        w.put( com.wikantik.knowledge.embedding.NodeMentionSimilarity.class,                   ( e, m ) -> e.mgr_NodeMentionSimilarity              = (com.wikantik.knowledge.embedding.NodeMentionSimilarity) m );
        w.put( com.wikantik.knowledge.FrontmatterDefaultsFilter.class,                         ( e, m ) -> e.mgr_FrontmatterDefaultsFilter          = (com.wikantik.knowledge.FrontmatterDefaultsFilter) m );
        w.put( com.wikantik.knowledge.HubSyncFilter.class,                                     ( e, m ) -> e.mgr_HubSyncFilter                      = (com.wikantik.knowledge.HubSyncFilter) m );
        w.put( com.wikantik.api.knowledge.ContextRetrievalService.class,                       ( e, m ) -> e.mgr_ContextRetrievalService            = (com.wikantik.api.knowledge.ContextRetrievalService) m );
        w.put( com.wikantik.api.agent.ForAgentProjectionService.class,                         ( e, m ) -> e.mgr_ForAgentProjectionService          = (com.wikantik.api.agent.ForAgentProjectionService) m );
        w.put( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class,       ( e, m ) -> e.mgr_BootstrapEntityExtractionIndexer   = (com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer) m );
        w.put( com.wikantik.api.kgpolicy.KgInclusionPolicy.class,                              ( e, m ) -> e.mgr_KgInclusionPolicy                  = (com.wikantik.api.kgpolicy.KgInclusionPolicy) m );
        w.put( com.wikantik.kgpolicy.ReconciliationJobRunner.class,                            ( e, m ) -> e.mgr_ReconciliationJobRunner            = (com.wikantik.kgpolicy.ReconciliationJobRunner) m );
        w.put( com.wikantik.api.eval.RetrievalQualityRunner.class,                             ( e, m ) -> e.mgr_RetrievalQualityRunner             = (com.wikantik.api.eval.RetrievalQualityRunner) m );
        w.put( com.wikantik.knowledge.extraction.ChunkEntityMentionRepository.class,           ( e, m ) -> e.mgr_ChunkEntityMentionRepository       = (com.wikantik.knowledge.extraction.ChunkEntityMentionRepository) m );
        w.put( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class,          ( e, m ) -> e.mgr_KgAsyncEntityExtractionListener    = (com.wikantik.knowledge.extraction.AsyncEntityExtractionListener) m );
        w.put( com.wikantik.kgpolicy.KgClusterPolicyRepository.class,                         ( e, m ) -> e.mgr_KgClusterPolicyRepository          = (com.wikantik.kgpolicy.KgClusterPolicyRepository) m );
        w.put( com.wikantik.kgpolicy.KgExcludedPagesRepository.class,                         ( e, m ) -> e.mgr_KgExcludedPagesRepository          = (com.wikantik.kgpolicy.KgExcludedPagesRepository) m );
        TYPED_FIELD_WRITERS = w;

        final IdentityHashMap<Class<?>, Function<WikiEngine, Object>> r = new IdentityHashMap<>( 128 );
        // Auth
        r.put( com.wikantik.auth.AuthenticationManager.class,      e -> e.mgr_AuthenticationManager );
        r.put( com.wikantik.auth.AuthorizationManager.class,       e -> e.mgr_AuthorizationManager );
        r.put( com.wikantik.auth.UserManager.class,                e -> e.mgr_UserManager );
        r.put( com.wikantik.auth.authorize.GroupManager.class,     e -> e.mgr_GroupManager );
        r.put( com.wikantik.auth.acl.AclManager.class,             e -> e.mgr_AclManager );
        // Page
        r.put( com.wikantik.api.managers.PageManager.class,        e -> e.mgr_PageManager );
        r.put( com.wikantik.api.managers.AttachmentManager.class,  e -> e.mgr_AttachmentManager );
        r.put( com.wikantik.content.PageRenamer.class,             e -> e.mgr_PageRenamer );
        r.put( com.wikantik.api.managers.ReferenceManager.class,   e -> e.mgr_ReferenceManager );
        // Core
        r.put( com.wikantik.cache.CachingManager.class,                   e -> e.mgr_CachingManager );
        r.put( com.wikantik.variables.VariableManager.class,              e -> e.mgr_VariableManager );
        r.put( com.wikantik.ui.progress.ProgressManager.class,            e -> e.mgr_ProgressManager );
        r.put( com.wikantik.ui.CommandResolver.class,                     e -> e.mgr_CommandResolver );
        r.put( com.wikantik.url.URLConstructor.class,                     e -> e.mgr_URLConstructor );
        r.put( com.wikantik.i18n.InternationalizationManager.class,       e -> e.mgr_InternationalizationManager );
        r.put( com.wikantik.api.managers.SystemPageRegistry.class,        e -> e.mgr_SystemPageRegistry );
        r.put( com.wikantik.content.RecentArticlesManager.class,          e -> e.mgr_RecentArticlesManager );
        r.put( com.wikantik.blog.BlogManager.class,                       e -> e.mgr_BlogManager );
        // Rendering
        r.put( com.wikantik.render.RenderingManager.class,         e -> e.mgr_RenderingManager );
        r.put( com.wikantik.plugin.PluginManager.class,            e -> e.mgr_PluginManager );
        r.put( com.wikantik.filters.FilterManager.class,           e -> e.mgr_FilterManager );
        r.put( com.wikantik.diff.DifferenceManager.class,          e -> e.mgr_DifferenceManager );
        r.put( com.wikantik.content.NewsPageGenerator.class,       e -> e.mgr_NewsPageGenerator );
        // Search
        r.put( com.wikantik.search.SearchManager.class,                                  e -> e.mgr_SearchManager );
        r.put( com.wikantik.search.SearchProvider.class,                                 e -> e.mgr_SearchProvider );
        r.put( com.wikantik.search.hybrid.HybridSearchService.class,                     e -> e.mgr_HybridSearchService );
        r.put( com.wikantik.search.hybrid.QueryEmbedder.class,                           e -> e.mgr_QueryEmbedder );
        r.put( com.wikantik.search.hybrid.QueryEntityResolver.class,                     e -> e.mgr_QueryEntityResolver );
        r.put( com.wikantik.search.hybrid.GraphRerankStep.class,                         e -> e.mgr_GraphRerankStep );
        r.put( com.wikantik.search.hybrid.GraphProximityScorer.class,                    e -> e.mgr_GraphProximityScorer );
        r.put( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class,                e -> e.mgr_InMemoryChunkVectorIndex );
        r.put( com.wikantik.search.hybrid.ChunkVectorIndex.class,                        e -> e.mgr_ChunkVectorIndex );
        r.put( com.wikantik.search.hybrid.InMemoryGraphNeighborIndex.class,              e -> e.mgr_InMemoryGraphNeighborIndex );
        r.put( com.wikantik.search.hybrid.GraphNeighborIndex.class,                      e -> e.mgr_GraphNeighborIndex );
        r.put( com.wikantik.search.hybrid.PageMentionsLoader.class,                      e -> e.mgr_PageMentionsLoader );
        r.put( com.wikantik.search.embedding.EmbeddingIndexService.class,                e -> e.mgr_EmbeddingIndexService );
        r.put( com.wikantik.search.embedding.OllamaEmbeddingClient.class,                e -> e.mgr_OllamaEmbeddingClient );
        r.put( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class,            e -> e.mgr_BootstrapEmbeddingIndexer );
        r.put( com.wikantik.search.embedding.AsyncEmbeddingIndexListener.class,          e -> e.mgr_AsyncEmbeddingIndexListener );
        r.put( com.wikantik.search.FrontmatterMetadataCache.class,                       e -> e.mgr_FrontmatterMetadataCache );
        r.put( com.wikantik.search.subsystem.lucene.LuceneIndexer.class,                 e -> e.mgr_LuceneIndexer );
        r.put( com.wikantik.search.subsystem.lucene.LuceneSearcher.class,                e -> e.mgr_LuceneSearcher );
        r.put( com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle.class,          e -> e.mgr_LuceneIndexLifecycle );
        // Page Graph
        r.put( com.wikantik.api.pagegraph.StructuralIndexService.class,                  e -> e.mgr_StructuralIndexService );
        r.put( com.wikantik.api.pagegraph.PageGraphService.class,                        e -> e.mgr_PageGraphService );
        r.put( com.wikantik.admin.ContentIndexRebuildService.class,                      e -> e.mgr_ContentIndexRebuildService );
        r.put( com.wikantik.pagegraph.spine.PageVerificationDao.class,                   e -> e.mgr_PageVerificationDao );
        r.put( com.wikantik.pagegraph.spine.TrustedAuthorsDao.class,                     e -> e.mgr_TrustedAuthorsDao );
        r.put( com.wikantik.pagegraph.spine.StructuralIndexEventListener.class,          e -> e.mgr_StructuralIndexEventListener );
        // Knowledge
        r.put( com.wikantik.api.knowledge.KnowledgeGraphService.class,                         e -> e.mgr_KnowledgeGraphService );
        r.put( com.wikantik.api.knowledge.KgProposalJudgeService.class,                        e -> e.mgr_KgProposalJudgeService );
        r.put( com.wikantik.knowledge.judge.JudgeRunner.class,                                 e -> e.mgr_JudgeRunner );
        r.put( com.wikantik.knowledge.judge.KgMaterializationService.class,                    e -> e.mgr_KgMaterializationService );
        r.put( com.wikantik.knowledge.judge.KgJudgeTimeoutRepository.class,                    e -> e.mgr_KgJudgeTimeoutRepository );
        r.put( com.wikantik.knowledge.HubProposalService.class,                                e -> e.mgr_HubProposalService );
        r.put( com.wikantik.knowledge.HubDiscoveryService.class,                               e -> e.mgr_HubDiscoveryService );
        r.put( com.wikantik.knowledge.HubOverviewService.class,                                e -> e.mgr_HubOverviewService );
        r.put( com.wikantik.knowledge.HubProposalRepository.class,                             e -> e.mgr_HubProposalRepository );
        r.put( com.wikantik.knowledge.HubDiscoveryRepository.class,                            e -> e.mgr_HubDiscoveryRepository );
        r.put( com.wikantik.knowledge.chunking.ContentChunkRepository.class,                   e -> e.mgr_ContentChunkRepository );
        r.put( com.wikantik.knowledge.chunking.ChunkProjector.class,                           e -> e.mgr_ChunkProjector );
        r.put( com.wikantik.knowledge.MentionIndex.class,                                      e -> e.mgr_MentionIndex );
        r.put( com.wikantik.knowledge.embedding.NodeMentionSimilarity.class,                   e -> e.mgr_NodeMentionSimilarity );
        r.put( com.wikantik.knowledge.FrontmatterDefaultsFilter.class,                         e -> e.mgr_FrontmatterDefaultsFilter );
        r.put( com.wikantik.knowledge.HubSyncFilter.class,                                     e -> e.mgr_HubSyncFilter );
        r.put( com.wikantik.api.knowledge.ContextRetrievalService.class,                       e -> e.mgr_ContextRetrievalService );
        r.put( com.wikantik.api.agent.ForAgentProjectionService.class,                         e -> e.mgr_ForAgentProjectionService );
        r.put( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class,       e -> e.mgr_BootstrapEntityExtractionIndexer );
        r.put( com.wikantik.api.kgpolicy.KgInclusionPolicy.class,                              e -> e.mgr_KgInclusionPolicy );
        r.put( com.wikantik.kgpolicy.ReconciliationJobRunner.class,                            e -> e.mgr_ReconciliationJobRunner );
        r.put( com.wikantik.api.eval.RetrievalQualityRunner.class,                             e -> e.mgr_RetrievalQualityRunner );
        r.put( com.wikantik.knowledge.extraction.ChunkEntityMentionRepository.class,           e -> e.mgr_ChunkEntityMentionRepository );
        r.put( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class,          e -> e.mgr_KgAsyncEntityExtractionListener );
        r.put( com.wikantik.kgpolicy.KgClusterPolicyRepository.class,                         e -> e.mgr_KgClusterPolicyRepository );
        r.put( com.wikantik.kgpolicy.KgExcludedPagesRepository.class,                         e -> e.mgr_KgExcludedPagesRepository );
        TYPED_FIELD_READERS = r;
    }

    /**
     * Maps each class key to the subsystem-snapshot rebuilder that must run when that
     * class is hot-swapped via {@link #setManager}.  One entry per class; the lambda
     * is a no-op if the relevant subsystem snapshot is still null (i.e. during boot).
     * Populated in the same static block as TYPED_FIELD_WRITERS / TYPED_FIELD_READERS.
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
        s.put( com.wikantik.content.NewsPageGenerator.class,       rebuildRendering );
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

    // -----------------------------------------------------------------------
    // Per-class typed backing fields — Phase 10 (Ckpt A1 + A2)
    // Populated by setManager / initComponent; read by getManager.
    // Names use mgr_ prefix to avoid collisions with existing subsystem fields.
    // -----------------------------------------------------------------------

    // Auth group
    private com.wikantik.auth.AuthenticationManager mgr_AuthenticationManager;
    private com.wikantik.auth.AuthorizationManager mgr_AuthorizationManager;
    private com.wikantik.auth.UserManager mgr_UserManager;
    private com.wikantik.auth.authorize.GroupManager mgr_GroupManager;
    private com.wikantik.auth.acl.AclManager mgr_AclManager;

    // Page group
    private com.wikantik.api.managers.PageManager mgr_PageManager;
    private com.wikantik.api.managers.AttachmentManager mgr_AttachmentManager;
    private com.wikantik.content.PageRenamer mgr_PageRenamer;
    private com.wikantik.api.managers.ReferenceManager mgr_ReferenceManager;

    // Core group
    private com.wikantik.cache.CachingManager mgr_CachingManager;
    private com.wikantik.variables.VariableManager mgr_VariableManager;
    private com.wikantik.ui.progress.ProgressManager mgr_ProgressManager;
    private com.wikantik.ui.CommandResolver mgr_CommandResolver;
    private com.wikantik.url.URLConstructor mgr_URLConstructor;
    private com.wikantik.i18n.InternationalizationManager mgr_InternationalizationManager;
    private com.wikantik.api.managers.SystemPageRegistry mgr_SystemPageRegistry;
    private com.wikantik.content.RecentArticlesManager mgr_RecentArticlesManager;
    private com.wikantik.blog.BlogManager mgr_BlogManager;

    // Rendering group
    private com.wikantik.render.RenderingManager mgr_RenderingManager;
    private com.wikantik.plugin.PluginManager mgr_PluginManager;
    private com.wikantik.filters.FilterManager mgr_FilterManager;
    private com.wikantik.diff.DifferenceManager mgr_DifferenceManager;
    private com.wikantik.content.NewsPageGenerator mgr_NewsPageGenerator;

    // Search group
    private com.wikantik.search.SearchManager mgr_SearchManager;
    private com.wikantik.search.SearchProvider mgr_SearchProvider;
    private com.wikantik.search.hybrid.HybridSearchService mgr_HybridSearchService;
    private com.wikantik.search.hybrid.QueryEmbedder mgr_QueryEmbedder;
    private com.wikantik.search.hybrid.QueryEntityResolver mgr_QueryEntityResolver;
    private com.wikantik.search.hybrid.GraphRerankStep mgr_GraphRerankStep;
    private com.wikantik.search.hybrid.GraphProximityScorer mgr_GraphProximityScorer;
    private com.wikantik.search.hybrid.InMemoryChunkVectorIndex mgr_InMemoryChunkVectorIndex;
    private com.wikantik.search.hybrid.ChunkVectorIndex mgr_ChunkVectorIndex;
    private com.wikantik.search.hybrid.InMemoryGraphNeighborIndex mgr_InMemoryGraphNeighborIndex;
    private com.wikantik.search.hybrid.GraphNeighborIndex mgr_GraphNeighborIndex;
    private com.wikantik.search.hybrid.PageMentionsLoader mgr_PageMentionsLoader;
    private com.wikantik.search.embedding.EmbeddingIndexService mgr_EmbeddingIndexService;
    private com.wikantik.search.embedding.OllamaEmbeddingClient mgr_OllamaEmbeddingClient;
    private com.wikantik.search.embedding.BootstrapEmbeddingIndexer mgr_BootstrapEmbeddingIndexer;
    private com.wikantik.search.embedding.AsyncEmbeddingIndexListener mgr_AsyncEmbeddingIndexListener;
    private com.wikantik.search.FrontmatterMetadataCache mgr_FrontmatterMetadataCache;
    private com.wikantik.search.subsystem.lucene.LuceneIndexer mgr_LuceneIndexer;
    private com.wikantik.search.subsystem.lucene.LuceneSearcher mgr_LuceneSearcher;
    private com.wikantik.search.subsystem.lucene.LuceneIndexLifecycle mgr_LuceneIndexLifecycle;

    // Page Graph group
    private com.wikantik.api.pagegraph.StructuralIndexService mgr_StructuralIndexService;
    private com.wikantik.api.pagegraph.PageGraphService mgr_PageGraphService;
    private com.wikantik.admin.ContentIndexRebuildService mgr_ContentIndexRebuildService;
    private com.wikantik.pagegraph.spine.PageVerificationDao mgr_PageVerificationDao;
    private com.wikantik.pagegraph.spine.TrustedAuthorsDao mgr_TrustedAuthorsDao;
    private com.wikantik.pagegraph.spine.StructuralIndexEventListener mgr_StructuralIndexEventListener;

    // Knowledge group
    private com.wikantik.api.knowledge.KnowledgeGraphService mgr_KnowledgeGraphService;
    private com.wikantik.api.knowledge.KgProposalJudgeService mgr_KgProposalJudgeService;
    private com.wikantik.knowledge.judge.JudgeRunner mgr_JudgeRunner;
    private com.wikantik.knowledge.judge.KgMaterializationService mgr_KgMaterializationService;
    private com.wikantik.knowledge.judge.KgJudgeTimeoutRepository mgr_KgJudgeTimeoutRepository;
    private com.wikantik.knowledge.HubProposalService mgr_HubProposalService;
    private com.wikantik.knowledge.HubDiscoveryService mgr_HubDiscoveryService;
    private com.wikantik.knowledge.HubOverviewService mgr_HubOverviewService;
    private com.wikantik.knowledge.HubProposalRepository mgr_HubProposalRepository;
    private com.wikantik.knowledge.HubDiscoveryRepository mgr_HubDiscoveryRepository;
    private com.wikantik.knowledge.chunking.ContentChunkRepository mgr_ContentChunkRepository;
    private com.wikantik.knowledge.chunking.ChunkProjector mgr_ChunkProjector;
    private com.wikantik.knowledge.MentionIndex mgr_MentionIndex;
    private com.wikantik.knowledge.embedding.NodeMentionSimilarity mgr_NodeMentionSimilarity;
    private com.wikantik.knowledge.FrontmatterDefaultsFilter mgr_FrontmatterDefaultsFilter;
    private com.wikantik.knowledge.HubSyncFilter mgr_HubSyncFilter;
    private com.wikantik.api.knowledge.ContextRetrievalService mgr_ContextRetrievalService;
    private com.wikantik.api.agent.ForAgentProjectionService mgr_ForAgentProjectionService;
    private com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer mgr_BootstrapEntityExtractionIndexer;
    private com.wikantik.api.kgpolicy.KgInclusionPolicy mgr_KgInclusionPolicy;
    private com.wikantik.kgpolicy.ReconciliationJobRunner mgr_ReconciliationJobRunner;
    private com.wikantik.api.eval.RetrievalQualityRunner mgr_RetrievalQualityRunner;
    private com.wikantik.knowledge.extraction.ChunkEntityMentionRepository mgr_ChunkEntityMentionRepository;
    private com.wikantik.knowledge.extraction.AsyncEntityExtractionListener mgr_KgAsyncEntityExtractionListener;
    private com.wikantik.kgpolicy.KgClusterPolicyRepository mgr_KgClusterPolicyRepository;
    private com.wikantik.kgpolicy.KgExcludedPagesRepository mgr_KgExcludedPagesRepository;

    /** Guice injector for modern dependency management. */
    private Injector injector;

    /** Hybrid-retrieval lifecycle handles; null when hybrid is disabled. */
    private com.wikantik.search.embedding.AsyncEmbeddingIndexListener hybridIndexListener;
    private com.wikantik.search.hybrid.QueryEmbedder hybridQueryEmbedder;
    private com.wikantik.search.embedding.BootstrapEmbeddingIndexer hybridBootstrapIndexer;

    /** Entity-extraction lifecycle handle; null when the extractor is disabled. */
    private com.wikantik.knowledge.extraction.AsyncEntityExtractionListener entityExtractionListener;

    // -----------------------------------------------------------------------
    // Lifecycle-handle setters — called by the subsystem wiring helpers
    // (SearchWiringHelper, KnowledgeWiringHelper) that live in other packages.
    // These are intentionally not part of the Engine interface; they are
    // internal plumbing used during initialize() and deleted in Ckpt 4d.
    // -----------------------------------------------------------------------

    /** Sets the hybrid-retrieval async embedding listener. Called by {@link com.wikantik.search.subsystem.SearchWiringHelper}. */
    public void setHybridIndexListener( final com.wikantik.search.embedding.AsyncEmbeddingIndexListener l ) {
        this.hybridIndexListener = l;
    }
    /** Returns the hybrid-retrieval async embedding listener (for composing the entity-extraction chain). */
    public com.wikantik.search.embedding.AsyncEmbeddingIndexListener getHybridIndexListener() {
        return hybridIndexListener;
    }
    /** Sets the query embedder lifecycle handle. Called by {@link com.wikantik.search.subsystem.SearchWiringHelper}. */
    public void setHybridQueryEmbedder( final com.wikantik.search.hybrid.QueryEmbedder e ) {
        this.hybridQueryEmbedder = e;
    }
    /** Sets the bootstrap embedding indexer lifecycle handle. Called by {@link com.wikantik.search.subsystem.SearchWiringHelper}. */
    public void setHybridBootstrapIndexer( final com.wikantik.search.embedding.BootstrapEmbeddingIndexer b ) {
        this.hybridBootstrapIndexer = b;
    }
    /** Sets the entity-extraction listener lifecycle handle. Called by {@link com.wikantik.knowledge.subsystem.KnowledgeWiringHelper}. */
    public void setEntityExtractionListener( final com.wikantik.knowledge.extraction.AsyncEntityExtractionListener l ) {
        this.entityExtractionListener = l;
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
            final com.wikantik.search.FrontmatterMetadataCache fmCacheInstance =
                new com.wikantik.search.FrontmatterMetadataCache( getManager( PageManager.class ) );
            setManager( com.wikantik.search.FrontmatterMetadataCache.class, fmCacheInstance );
            // Publish Caffeine cache size/hits/misses/evictions for this cache
            // — registration is colocated with construction so wireHybridRetrieval
            // doesn't need an extra getManager call (blocked by the decomposition
            // ArchUnit rule).
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

            //  Hook the different manager routines into the system.
            getManager( FilterManager.class ).addPageFilter( getManager( ReferenceManager.class ), -1001 );
            getManager( FilterManager.class ).addPageFilter( getManager( SearchManager.class ), -1002 );

            // Phase 2 of the wikantik-main subsystem decomposition: build
            // the Core subsystem after its leaf managers are constructed.
            // Knowledge (built next, in initKnowledgeGraph) will consume
            // Core via WikiSubsystems in a subsequent checkpoint.
            this.coreSubsystem = com.wikantik.core.subsystem.CoreSubsystemFactory.create(
                new com.wikantik.core.subsystem.CoreSubsystem.Deps(
                    props,
                    servletContext,
                    com.wikantik.api.observability.MeterRegistryHolder.get(),
                    getManager( SystemPageRegistry.class ),
                    getManager( RecentArticlesManager.class ),
                    getManager( BlogManager.class ),
                    this ) );

            // Phase 4 of the wikantik-main subsystem decomposition: build
            // the Auth subsystem after the four auth managers are
            // registered. Persistence is null at this point — it gets
            // built inside initKnowledgeGraph below — and the auth
            // factory tolerates that.
            this.authSubsystem = com.wikantik.auth.subsystem.AuthSubsystemFactory.create(
                new com.wikantik.auth.subsystem.AuthSubsystem.Deps(
                    coreSubsystem, persistenceSubsystem, servletContext, this ) );

            // Phase 5 of the wikantik-main subsystem decomposition: build
            // the Page subsystem BEFORE initKnowledgeGraph so the KG
            // factory can declare a typed PageSubsystem.Services
            // dependency. Page doesn't currently consume persistence; the
            // null-persistence Deps is fine.
            this.pageSubsystem = com.wikantik.page.subsystem.PageSubsystemFactory.create(
                new com.wikantik.page.subsystem.PageSubsystem.Deps(
                    coreSubsystem, persistenceSubsystem, authSubsystem, this ) );

            // Phase 6 of the wikantik-main subsystem decomposition: build
            // the Rendering subsystem after Page (Rendering depends on Page
            // for the page-save filter chain seam) and BEFORE
            // initKnowledgeGraph so KG can evolve to consume Rendering in a
            // Phase 6 follow-up.
            this.renderingSubsystem = com.wikantik.render.subsystem.RenderingSubsystemFactory.create(
                new com.wikantik.render.subsystem.RenderingSubsystem.Deps(
                    coreSubsystem, authSubsystem, pageSubsystem, this ) );

            // Phase 9: Knowledge graph (optional — requires datasource
            // configuration). Builds persistenceSubsystem internally and
            // consumes pageSubsystem via KnowledgeSubsystem.Deps.
            initKnowledgeGraph( props );

            // Phase 7 of the wikantik-main subsystem decomposition: build
            // the Search subsystem AFTER Knowledge. Search depends on
            // Knowledge for the graph-rerank step; Knowledge today
            // receives a LuceneMlt seam that is resolved earlier in
            // initKnowledgeGraph by reading the live SearchManager. The
            // ordering here keeps Search → Knowledge as the dependency
            // direction; the LuceneMlt cycle stays nullable until a
            // post-construction wiring lands in a later checkpoint.
            this.searchSubsystem = com.wikantik.search.subsystem.SearchSubsystemFactory.create(
                new com.wikantik.search.subsystem.SearchSubsystem.Deps(
                    persistenceSubsystem != null ? persistenceSubsystem.dataSource() : null,
                    coreSubsystem, persistenceSubsystem, pageSubsystem, knowledgeSubsystem, this ) );

            // Phase 7 Ckpt 4: post-construction wire of the LuceneMlt seam
            // onto HubOverviewService. Resolves the Search↔Knowledge cycle
            // (Search is built AFTER Knowledge, but Knowledge needs an MLT
            // implementation backed by Lucene). Skipped when Knowledge
            // didn't initialise (no datasource), or when the configured
            // search provider isn't Lucene — in either case the no-op MLT
            // installed by HubOverviewService at construction time stays
            // active. Delegates to the decomposed LuceneSearcher (Ckpt 3).
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

        // Phase 1 of the wikantik-main subsystem decomposition: stash the
        // typed subsystem services on the ServletContext so servlets can
        // reach them without going through getManager(Class). Subsequent
        // phases append fields to the WikiSubsystems record. Skipped when
        // running outside a servlet container OR when the Knowledge
        // subsystem didn't initialise (no datasource — e.g. unit-test
        // engines built via TestEngine.setManager). In those cases
        // RestServletBase falls back to a synthetic bundle reading the
        // legacy manager registry.
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

        // Phase 9 Ckpt 1: build the Page Graph subsystem after page + knowledge
        // (both may be deps of PG services in future phases). The four services
        // (StructuralIndexService, PageGraphService, ReferenceManager,
        // ContentIndexRebuildService) are registered in initPageGraphServices()
        // which fires earlier in initialize(). This call just wraps them into
        // the typed Services record.
        this.pageGraphSubsystem = com.wikantik.pagegraph.subsystem.PageGraphSubsystemFactory.create(
            new com.wikantik.pagegraph.subsystem.PageGraphSubsystem.Deps(
                coreSubsystem, persistenceSubsystem, pageSubsystem, this ) );

        if ( servletContext != null && coreSubsystem != null && knowledgeSubsystem != null ) {
            final WikiSubsystems subsystems = new WikiSubsystems(
                coreSubsystem, persistenceSubsystem, authSubsystem, pageSubsystem,
                renderingSubsystem, searchSubsystem, knowledgeSubsystem, pageGraphSubsystem );
            servletContext.setAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE, subsystems );
        }

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
        // Write directly to the typed backing field. Do NOT call setManager here:
        // setManager triggers subsystem snapshot rebuilds which would produce partial
        // snapshots (e.g. coreSubsystem with null systemPageRegistry) that subsequent
        // initializers then see via getCoreSubsystem(). The full snapshots are built
        // by the factory calls (CoreSubsystemFactory.create etc.) after all
        // initComponent calls complete.
        writeTypedField( componentClass, component );
        if( Initializable.class.isAssignableFrom( component.getClass() ) ) {
            ( ( Initializable )component ).initialize( this, properties );
        }
    }

    /**
     * Writes {@code mgr} to the per-class typed backing field for {@code clazz}.
     * Called from {@link #setManager} and {@link #initComponent}.
     *
     * <p>Unknown class keys are silently ignored — they carry no typed field.</p>
     */
    private < T > void writeTypedField( final Class< T > clazz, final T mgr ) {
        final BiConsumer<WikiEngine, Object> writer = TYPED_FIELD_WRITERS.get( clazz );
        if ( writer != null ) writer.accept( this, mgr );
        // Unknown class: silently skip — matches original switch no-op default.
    }

    /**
     * Reads the per-class typed backing field for {@code clazz}, or {@code null}
     * when the class is not in the known-types table. Used by
     * {@link #getManager(Class)} as the primary lookup.
     */
    @SuppressWarnings( "unchecked" )
    private < T > T readTypedField( final Class< T > clazz ) {
        final Function<WikiEngine, Object> reader = TYPED_FIELD_READERS.get( clazz );
        if ( reader == null ) return null;
        return clazz.cast( reader.apply( this ) );
    }

    /** Retrieves the object registered under the given type key. Not part of the {@link Engine} interface. */
    @SuppressWarnings( "unchecked" )
    public < T > T getManager( final Class< T > manager ) {
        // 1. Typed backing field — O(1) exact-class lookup for all known types.
        //    Returns non-null when the field has been written by setManager / initComponent.
        final T fromField = readTypedField( manager );
        if ( fromField != null ) return fromField;

        // 2. Try Guice (for classes not in the typed table — plugin-contributed, etc.)
        try {
            if( injector != null ) {
                return injector.getInstance( manager );
            }
        } catch( final ConfigurationException | ProvisionException e ) {
            // Not bound in Guice — fall through to coreSubsystem bridge.
            LOG.trace( "Manager {} not found in Guice or typed field", manager.getName() );
        }

        // 3. Fall through to typed subsystem services. Phase 2 of the
        //    wikantik-main decomposition removed SystemPageRegistry,
        //    RecentArticlesManager, and BlogManager from the typed-field table;
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

        // Differentiate "known class, field not yet populated (boot-ordering between
        // cross-manager getManager calls)" vs "genuinely unknown class". The former is
        // expected during init — managers reach for siblings before all initComponent
        // calls have finished — and produced 9000+ WARN lines on every boot. Only
        // warn when the class isn't on the typed table at all.
        if ( ! TYPED_FIELD_READERS.containsKey( manager ) ) {
            LOG.warn( "getManager({}) returned null — class has no typed backing field and was not found in Guice", manager.getName() );
        } else {
            LOG.debug( "getManager({}) returned null — typed field not yet populated (called before its initComponent)", manager.getName() );
        }
        return null;
    }

    /** Registers an object under the given type key. Not part of the {@link Engine} interface. */
    public < T > void setManager( final Class< T > clazz, final T manager ) {
        writeTypedField( clazz, manager );
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

    // -----------------------------------------------------------------------
    // Typed register setters — Ckpt 4d-i
    // Each delegates to setManager(Class, T) so the registry stays intact and
    // snapshot-invalidation logic in setManager fires normally.
    // -----------------------------------------------------------------------

    // -- Search / embedding --

    /** Registers {@link com.wikantik.search.embedding.EmbeddingIndexService}. Called by SearchWiringHelper. */
    public void registerEmbeddingIndexService( final com.wikantik.search.embedding.EmbeddingIndexService svc ) {
        setManager( com.wikantik.search.embedding.EmbeddingIndexService.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.ChunkVectorIndex} (and its in-memory impl). Called by SearchWiringHelper. */
    public void registerChunkVectorIndex( final com.wikantik.search.hybrid.InMemoryChunkVectorIndex svc ) {
        setManager( com.wikantik.search.hybrid.ChunkVectorIndex.class, svc );
        setManager( com.wikantik.search.hybrid.InMemoryChunkVectorIndex.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.QueryEmbedder}. Called by SearchWiringHelper. */
    public void registerQueryEmbedder( final com.wikantik.search.hybrid.QueryEmbedder svc ) {
        setManager( com.wikantik.search.hybrid.QueryEmbedder.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.HybridSearchService}. Called by SearchWiringHelper. */
    public void registerHybridSearchService( final com.wikantik.search.hybrid.HybridSearchService svc ) {
        setManager( com.wikantik.search.hybrid.HybridSearchService.class, svc );
    }

    /** Registers {@link com.wikantik.search.embedding.BootstrapEmbeddingIndexer}. Called by SearchWiringHelper. */
    public void registerBootstrapEmbeddingIndexer( final com.wikantik.search.embedding.BootstrapEmbeddingIndexer svc ) {
        setManager( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class, svc );
    }

    // -- Graph rerank --

    /** Registers {@link com.wikantik.search.hybrid.InMemoryGraphNeighborIndex} (and its interface). Called by SearchWiringHelper. */
    public void registerGraphNeighborIndex( final com.wikantik.search.hybrid.InMemoryGraphNeighborIndex svc ) {
        setManager( com.wikantik.search.hybrid.InMemoryGraphNeighborIndex.class, svc );
        setManager( com.wikantik.search.hybrid.GraphNeighborIndex.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.GraphProximityScorer}. Called by SearchWiringHelper. */
    public void registerGraphProximityScorer( final com.wikantik.search.hybrid.GraphProximityScorer svc ) {
        setManager( com.wikantik.search.hybrid.GraphProximityScorer.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.QueryEntityResolver}. Called by SearchWiringHelper. */
    public void registerQueryEntityResolver( final com.wikantik.search.hybrid.QueryEntityResolver svc ) {
        setManager( com.wikantik.search.hybrid.QueryEntityResolver.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.PageMentionsLoader}. Called by SearchWiringHelper. */
    public void registerPageMentionsLoader( final com.wikantik.search.hybrid.PageMentionsLoader svc ) {
        setManager( com.wikantik.search.hybrid.PageMentionsLoader.class, svc );
    }

    /** Registers {@link com.wikantik.search.hybrid.GraphRerankStep}. Called by SearchWiringHelper. */
    public void registerGraphRerankStep( final com.wikantik.search.hybrid.GraphRerankStep svc ) {
        setManager( com.wikantik.search.hybrid.GraphRerankStep.class, svc );
    }

    /** Registers {@link com.wikantik.api.eval.RetrievalQualityRunner}. Called by SearchWiringHelper. */
    public void registerRetrievalQualityRunner( final com.wikantik.api.eval.RetrievalQualityRunner svc ) {
        setManager( com.wikantik.api.eval.RetrievalQualityRunner.class, svc );
    }

    // -- Page Graph --

    /** Registers {@link com.wikantik.pagegraph.spine.PageVerificationDao}. Called by PageGraphWiringHelper. */
    public void registerPageVerificationDao( final com.wikantik.pagegraph.spine.PageVerificationDao svc ) {
        setManager( com.wikantik.pagegraph.spine.PageVerificationDao.class, svc );
    }

    /** Registers {@link com.wikantik.pagegraph.spine.TrustedAuthorsDao}. Called by PageGraphWiringHelper. */
    public void registerTrustedAuthorsDao( final com.wikantik.pagegraph.spine.TrustedAuthorsDao svc ) {
        setManager( com.wikantik.pagegraph.spine.TrustedAuthorsDao.class, svc );
    }

    /** Registers {@link com.wikantik.api.pagegraph.StructuralIndexService}. Called by PageGraphWiringHelper. */
    public void registerStructuralIndexService( final com.wikantik.api.pagegraph.StructuralIndexService svc ) {
        setManager( com.wikantik.api.pagegraph.StructuralIndexService.class, svc );
    }

    /** Registers {@link com.wikantik.pagegraph.spine.StructuralIndexEventListener}. Called by PageGraphWiringHelper. */
    public void registerStructuralIndexEventListener( final com.wikantik.pagegraph.spine.StructuralIndexEventListener svc ) {
        setManager( com.wikantik.pagegraph.spine.StructuralIndexEventListener.class, svc );
    }

    /** Registers {@link com.wikantik.api.pagegraph.PageGraphService}. Called by PageGraphWiringHelper. */
    public void registerPageGraphService( final com.wikantik.api.pagegraph.PageGraphService svc ) {
        setManager( com.wikantik.api.pagegraph.PageGraphService.class, svc );
    }

    // -- Knowledge Graph / KG policy --

    /** Registers {@link com.wikantik.api.kgpolicy.KgInclusionPolicy}. Called by KnowledgeWiringHelper. */
    public void registerKgInclusionPolicy( final com.wikantik.api.kgpolicy.KgInclusionPolicy svc ) {
        setManager( com.wikantik.api.kgpolicy.KgInclusionPolicy.class, svc );
    }

    /** Registers {@link com.wikantik.kgpolicy.KgClusterPolicyRepository}. Called by KnowledgeWiringHelper. */
    public void registerKgClusterPolicyRepository( final com.wikantik.kgpolicy.KgClusterPolicyRepository svc ) {
        setManager( com.wikantik.kgpolicy.KgClusterPolicyRepository.class, svc );
    }

    /** Registers {@link com.wikantik.kgpolicy.KgExcludedPagesRepository}. Called by KnowledgeWiringHelper. */
    public void registerKgExcludedPagesRepository( final com.wikantik.kgpolicy.KgExcludedPagesRepository svc ) {
        setManager( com.wikantik.kgpolicy.KgExcludedPagesRepository.class, svc );
    }

    /** Registers {@link com.wikantik.kgpolicy.ReconciliationJobRunner}. Called by KnowledgeWiringHelper. */
    public void registerReconciliationJobRunner( final com.wikantik.kgpolicy.ReconciliationJobRunner svc ) {
        setManager( com.wikantik.kgpolicy.ReconciliationJobRunner.class, svc );
    }

    /** Registers {@link com.wikantik.api.agent.ForAgentProjectionService}. Called by KnowledgeWiringHelper. */
    public void registerForAgentProjectionService( final com.wikantik.api.agent.ForAgentProjectionService svc ) {
        setManager( com.wikantik.api.agent.ForAgentProjectionService.class, svc );
    }

    /** Registers {@link com.wikantik.admin.ContentIndexRebuildService}. Called by KnowledgeWiringHelper. */
    public void registerContentIndexRebuildService( final com.wikantik.admin.ContentIndexRebuildService svc ) {
        setManager( com.wikantik.admin.ContentIndexRebuildService.class, svc );
    }

    /** Registers {@link com.wikantik.knowledge.extraction.ChunkEntityMentionRepository}. Called by KnowledgeWiringHelper. */
    public void registerChunkEntityMentionRepository( final com.wikantik.knowledge.extraction.ChunkEntityMentionRepository svc ) {
        setManager( com.wikantik.knowledge.extraction.ChunkEntityMentionRepository.class, svc );
    }

    /** Registers {@link com.wikantik.knowledge.extraction.AsyncEntityExtractionListener}. Called by KnowledgeWiringHelper. */
    public void registerAsyncEntityExtractionListener( final com.wikantik.knowledge.extraction.AsyncEntityExtractionListener svc ) {
        setManager( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class, svc );
    }

    /** Registers {@link com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer}. Called by KnowledgeWiringHelper. */
    public void registerBootstrapEntityExtractionIndexer( final com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer svc ) {
        setManager( com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.class, svc );
    }

    // -- Content --

    /** Registers {@link com.wikantik.content.NewsPageGenerator}. Called by CachingProvider. */
    public void registerNewsPageGenerator( final com.wikantik.content.NewsPageGenerator svc ) {
        setManager( com.wikantik.content.NewsPageGenerator.class, svc );
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
            this.persistenceSubsystem = com.wikantik.persistence.subsystem.PersistenceSubsystemFactory.create(
                new com.wikantik.persistence.subsystem.PersistenceSubsystem.Deps(
                    ds, coreSubsystem.properties() ) );

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
            // fmCache is null here — the FrontmatterMetadataCache metric is
            // registered at the cache's construction site (see line ~822).
            // wireHybridRetrieval ignores a null fmCache (already guarded).
            com.wikantik.search.subsystem.SearchWiringHelper.wireHybridRetrieval(
                props, ds, svcs.chunkProjector(), svcs.contentChunkRepository(),
                /*fmCache*/ null,
                rebuildService, this );

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

            // Register save-time filters.
            filterManager.addPageFilter( svcs.chunkProjector(), -1005 );
            filterManager.addPageFilter( svcs.frontmatterDefaultsFilter(), -1004 );
            com.wikantik.pagegraph.subsystem.PageGraphWiringHelper.wireSpineFilters(
                props, structuralIndex, coreSubsystem, filterManager, pageManager, this );
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
            base.kgCurationOps()
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
            knowledgeSubsystem.kgCurationOps()
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
