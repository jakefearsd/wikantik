/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.retrieval.ContributingChunkAssembler;
import com.wikantik.knowledge.retrieval.PageListEngine;
import com.wikantik.knowledge.retrieval.RelatedPagesFinder;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import com.wikantik.search.hybrid.ScoredChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Composes the retrieval singletons into the agent-facing contract defined
 * by {@link ContextRetrievalService}. Thread-safe and stateless; constructed
 * once at engine boot.
 *
 * <p>Dependencies are pulled from the engine in
 * {@link #fromEngine(Engine)} so the constructor stays trivially testable
 * with fakes.</p>
 */
public final class DefaultContextRetrievalService implements ContextRetrievalService {

    private static final Logger LOG = LogManager.getLogger( DefaultContextRetrievalService.class );

    private final Engine engine;
    private final SearchManager searchManager;
    private final HybridSearchService hybridSearch;
    private final GraphRerankStep graphRerank;
    private final ChunkVectorIndex chunkIndex;
    private final ContentChunkRepository chunkRepo;
    private final NodeMentionSimilarity similarity;
    private final MentionIndex mentionIndex;
    private final PageManager pageManager;
    private final FrontmatterMetadataCache fmCache;
    private final String publicBaseUrl;
    private final com.wikantik.api.ontology.OntologyQueryService ontologyQuery;
    private final RelatedPagesFinder relatedPagesFinder;
    private final PageListEngine pageListEngine;
    private final ContributingChunkAssembler contributingChunkAssembler;

    public DefaultContextRetrievalService(
            final Engine engine,
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final GraphRerankStep graphRerank,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final MentionIndex mentionIndex,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl,
            final com.wikantik.api.ontology.OntologyQueryService ontologyQuery ) {
        if ( engine == null ) throw new IllegalArgumentException( "engine required" );
        if ( searchManager == null ) throw new IllegalArgumentException( "searchManager required" );
        if ( pageManager == null ) throw new IllegalArgumentException( "pageManager required" );
        // hybridSearch, graphRerank, chunkIndex, chunkRepo, similarity, mentionIndex, fmCache
        // all nullable: the service degrades rather than failing when optional deps are absent.
        this.engine = engine;
        this.searchManager = searchManager;
        this.hybridSearch = hybridSearch;
        this.graphRerank = graphRerank;
        this.chunkIndex = chunkIndex;
        this.chunkRepo = chunkRepo;
        this.similarity = similarity;
        this.mentionIndex = mentionIndex;
        this.pageManager = pageManager;
        this.fmCache = fmCache;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
        // Optional: when present (and the flag enabled at the wiring site), the query is
        // expanded with ontology-derived terms before search. Null = no expansion.
        this.ontologyQuery = ontologyQuery;
        this.relatedPagesFinder = new RelatedPagesFinder( mentionIndex );
        this.pageListEngine = new PageListEngine( pageManager, fmCache,
            ( page, meta ) -> buildRetrievedPage( page, meta, 0.0, List.of(), List.of() ) );
        this.contributingChunkAssembler = new ContributingChunkAssembler( chunkIndex, chunkRepo, hybridSearch );
    }

    /**
     * Convenience factory pulling every dependency from the engine. Returns
     * {@code null} if the engine has no {@link PageManager}, since without
     * pages there is nothing to retrieve.
     */
    public static DefaultContextRetrievalService fromEngine( final Engine engine ) {
        final PageManager pm = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
        if ( pm == null ) return null;
        final SearchManager sm = com.wikantik.search.subsystem.SearchSubsystemBridge.fromLegacyEngine( engine ).searchManager();
        if ( sm == null ) return null;
        // Phase 1 / Phase 8: KG-flavored services from KnowledgeSubsystem; Search-
        // flavored services from SearchSubsystem. Both bridges prefer the typed
        // subsystem record and fall back to engine.getManager() in test fixtures.
        final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services kg =
            com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge.fromLegacyEngine( engine );
        final com.wikantik.search.subsystem.SearchSubsystem.Services search =
            com.wikantik.search.subsystem.SearchSubsystemBridge.fromLegacyEngine( engine );
        return new DefaultContextRetrievalService(
            engine, sm,
            search.hybridSearch(),
            search.graphRerankStep(),
            search.chunkVectorIndex(),
            kg.contentChunkRepository(),
            kg.nodeMentionSimilarity(),
            kg.mentionIndex(),
            pm,
            search.frontmatterMetadataCache(),
            engine.getBaseURL(),
            ontologyQueryFor( engine ) );
    }

    /**
     * Builds the ontology query-expansion service, but only when
     * {@code wikantik.search.ontologyExpansion.enabled=true} and the materialized
     * ontology is available. Returns {@code null} otherwise (the default — no expansion).
     */
    private static com.wikantik.api.ontology.OntologyQueryService ontologyQueryFor( final Engine engine ) {
        final java.util.Properties props = engine.getWikiProperties();
        final boolean enabled = props != null && Boolean.parseBoolean(
            props.getProperty( "wikantik.search.ontologyExpansion.enabled", "false" ) );
        if ( !enabled ) {
            return null;
        }
        final com.wikantik.ontology.runtime.OntologyRebuildCoordinator coord =
            com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge
                .fromLegacyEngine( engine ).ontologyRebuildCoordinator();
        if ( coord == null || coord.modelManager() == null ) {
            return null;
        }
        return new com.wikantik.ontology.JenaOntologyQueryService( coord.modelManager() );
    }

    /** Appends ontology-derived expansion terms to the query when the service is present. */
    private String expandWithOntology( final String q ) {
        if ( ontologyQuery == null || q == null ) {
            return q;
        }
        final java.util.List< String > extra = ontologyQuery.expandQuery( q );
        if ( extra.isEmpty() ) {
            return q;
        }
        LOG.debug( "ontology expansion added {} term(s) to the query", extra.size() );
        return q + " " + String.join( " ", extra );
    }

    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        if ( query == null ) throw new IllegalArgumentException( "query required" );

        final Context ctx = buildContext();
        final String effectiveQuery = expandWithOntology( query.query() );
        final Collection< SearchResult > bm25;
        try {
            bm25 = searchManager.findPages( effectiveQuery, ctx );
        } catch ( final Exception e ) {
            // ParseException-rooted failures are client-class (malformed query
            // from the caller). Log without a stack trace to keep operator logs
            // clean. Anything else is server-class — keep the trace.
            if ( isQueryParseFailure( e ) ) {
                LOG.warn( "BM25 search rejected malformed query: {}", e.getMessage() );
            } else {
                LOG.warn( "BM25 search failed: {}", e.getMessage(), e );
            }
            return new RetrievalResult( query.query(), List.of(), 0 );
        }
        final RerankPipelineResult pipeline = applyHybridAndGraphRerank( query.query(), bm25 );
        final List< SearchResult > ordered = pipeline.ordered();
        // Plumb the first dense scan's chunks down so fetchContributingChunks
        // can skip its second full-corpus scan when the first one already
        // produced enough material. Empty Optional → fall through to the
        // current path (re-embed + topKChunks).
        final Map< String, List< RetrievedChunk > > chunksByPage =
            contributingChunkAssembler.fetchContributingChunks(
                query.query(), ordered, query.chunksPerPage(), pipeline.reusableChunks() );

        // Pre-compute related-pages for every candidate in ONE batched call.
        // The previous per-page fetchRelatedPages call inside the loop was an
        // N+1 lookup pattern under concurrent load — one DBCP acquire and one
        // PreparedStatement prep per result page. The batch version reuses
        // both across the N executions; see MentionIndex.findRelatedPagesBatch.
        final List< String > candidateNames = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            if ( sr.getPage() != null ) candidateNames.add( sr.getPage().getName() );
        }
        final Map< String, List< RelatedPage > > relatedByPage = relatedPagesFinder.fetchRelatedPagesBatch( candidateNames );

        final PageListFilter f = query.filter();
        final List< RetrievedPage > pages = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            final Page page = sr.getPage();
            if ( page == null ) continue;
            final Map< String, Object > meta = pageListEngine.metadataFor( page );
            if ( f != null && !pageListEngine.matchesFilter( page, meta, f ) ) continue;
            pages.add( buildRetrievedPage(
                page,
                meta,
                sr.getScore(),
                chunksByPage.getOrDefault( page.getName(), List.of() ),
                relatedByPage.getOrDefault( page.getName(), List.of() ) ) );
            if ( pages.size() >= query.maxPages() ) break;
        }
        return new RetrievalResult( query.query(), pages, ordered.size() );
    }

    private Context buildContext() {
        final Page anchor = pageManager.getPage( "Main" );
        if ( anchor == null ) return null;
        try {
            return com.wikantik.api.spi.Wiki.context().create( engine, anchor );
        } catch ( final Exception e ) {
            LOG.info( "WikiContext build failed for retrieval — returning null context: {}", e.getMessage() );
            return null;
        }
    }

    /**
     * Holds the rerank-pipeline outputs that {@link #retrieve} needs: the
     * reordered {@link SearchResult} list plus the raw dense chunks the
     * hybrid pass produced (so {@link #fetchContributingChunks} can reuse
     * them and skip its second full-corpus scan).
     */
    private record RerankPipelineResult(
        List< SearchResult > ordered,
        Optional< List< ScoredChunk > > reusableChunks
    ) {}

    private RerankPipelineResult applyHybridAndGraphRerank( final String query,
                                                            final Collection< SearchResult > bm25 ) {
        final List< SearchResult > asList = new ArrayList<>( bm25 == null ? List.of() : bm25 );
        if ( hybridSearch == null || !hybridSearch.isEnabled() ) {
            return new RerankPipelineResult( asList, Optional.empty() );
        }
        final List< String > names = new ArrayList<>();
        final Map< String, SearchResult > byName = new LinkedHashMap<>();
        for ( final SearchResult sr : asList ) {
            if ( sr.getPage() == null ) continue;
            names.add( sr.getPage().getName() );
            byName.putIfAbsent( sr.getPage().getName(), sr );
        }
        final com.wikantik.search.hybrid.RerankOutcome rerankOut =
            hybridSearch.rerankWithChunks( query, names );
        List< String > fused = rerankOut.fusedPageNames();
        if ( graphRerank != null ) {
            try {
                fused = graphRerank.rerank( query, fused );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Graph rerank failed; using hybrid fused order: {}", e.getMessage(), e );
            }
        }
        if ( fused.equals( names ) ) {
            return new RerankPipelineResult( asList, rerankOut.denseChunks() );
        }
        final List< SearchResult > out = new ArrayList<>( fused.size() );
        for ( final String name : fused ) {
            final SearchResult existing = byName.get( name );
            if ( existing != null ) { out.add( existing ); continue; }
            final Page p = pageManager.getPage( name );
            if ( p == null ) continue;
            out.add( new DenseOnlySearchResult( p ) );
        }
        return new RerankPipelineResult( out, rerankOut.denseChunks() );
    }

    /** Mirrors the DenseOnlySearchResult in SearchResource. */
    private static final class DenseOnlySearchResult implements SearchResult {
        private final Page page;
        DenseOnlySearchResult( final Page p ) { this.page = p; }
        @Override public Page getPage() { return page; }
        @Override public int getScore() { return 0; }
        @Override public String[] getContexts() { return new String[ 0 ]; }
    }

    @Override
    public RetrievedPage getPage( final String pageName ) {
        if ( pageName == null || pageName.isBlank() ) return null;
        final Page page = pageManager.getPage( pageName );
        if ( page == null ) return null;
        return buildRetrievedPage( page, pageListEngine.metadataFor( page ), 0.0, List.of(), List.of() );
    }

    /**
     * Builds a {@link RetrievedPage} from the given page + its parsed
     * frontmatter, filling in URL, summary, cluster, tags, author, and
     * lastModified from the canonical sources. Callers supply score,
     * contributing chunks, and related pages for their context.
     */
    private RetrievedPage buildRetrievedPage(
            final Page page,
            final Map< String, Object > meta,
            final double score,
            final List< RetrievedChunk > chunks,
            final List< RelatedPage > related ) {
        return new RetrievedPage(
            page.getName(),
            buildUrl( page.getName() ),
            score,
            PageListEngine.stringOrEmpty( meta.get( "summary" ) ),
            PageListEngine.stringOrNull( meta.get( "cluster" ) ),
            PageListEngine.stringList( meta.get( "tags" ) ),
            chunks,
            related,
            page.getAuthor(),
            page.getLastModified(),
            meta.get( "derived_from" ) != null );
    }

    private String buildUrl( final String pageName ) {
        if ( publicBaseUrl == null || publicBaseUrl.isBlank() ) return pageName;
        final String base = publicBaseUrl.endsWith( "/" ) ? publicBaseUrl : publicBaseUrl + "/";
        return base + pageName;
    }

    @Override
    public PageList listPages( final PageListFilter filter ) {
        return pageListEngine.listPages( filter );
    }

    @Override
    public List< MetadataValue > listMetadataValues( final String field ) {
        if ( field == null || field.isBlank() ) return List.of();
        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "getAllPages failed: {}", e.getMessage(), e );
            return List.of();
        }
        final Map< String, Integer > counts = new LinkedHashMap<>();
        for ( final Page page : allPages ) {
            final Object raw = pageListEngine.metadataFor( page ).get( field );
            if ( raw == null ) continue;
            if ( raw instanceof List< ? > listVal ) {
                for ( final Object v : listVal ) {
                    if ( v != null ) counts.merge( v.toString(), 1, Integer::sum );
                }
            } else {
                counts.merge( raw.toString(), 1, Integer::sum );
            }
        }
        return counts.entrySet().stream()
            .map( e -> new MetadataValue( e.getKey(), e.getValue() ) )
            .sorted( ( a, b ) -> Integer.compare( b.count(), a.count() ) )
            .toList();
    }

    /** True iff this exception (or any cause) is a Lucene query-parse failure. */
    private static boolean isQueryParseFailure( final Throwable t ) {
        for ( Throwable c = t; c != null; c = c.getCause() ) {
            if ( c instanceof org.apache.lucene.queryparser.classic.ParseException ) return true;
        }
        return false;
    }
}
