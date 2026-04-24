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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
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
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
            final String publicBaseUrl ) {
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
    }

    /**
     * Convenience factory pulling every dependency from the engine. Returns
     * {@code null} if the engine has no {@link PageManager}, since without
     * pages there is nothing to retrieve.
     */
    public static DefaultContextRetrievalService fromEngine( final Engine engine ) {
        final PageManager pm = engine.getManager( PageManager.class );
        if ( pm == null ) return null;
        final SearchManager sm = engine.getManager( SearchManager.class );
        if ( sm == null ) return null;
        return new DefaultContextRetrievalService(
            engine, sm,
            engine.getManager( HybridSearchService.class ),
            engine.getManager( GraphRerankStep.class ),
            engine.getManager( ChunkVectorIndex.class ),
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( NodeMentionSimilarity.class ),
            engine.getManager( MentionIndex.class ),
            pm,
            engine.getManager( FrontmatterMetadataCache.class ),
            engine.getBaseURL() );
    }

    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        if ( query == null ) throw new IllegalArgumentException( "query required" );

        final Context ctx = buildContext();
        final Collection< SearchResult > bm25;
        try {
            bm25 = searchManager.findPages( query.query(), ctx );
        } catch ( final Exception e ) {
            LOG.warn( "BM25 search failed: {}", e.getMessage(), e );
            return new RetrievalResult( query.query(), List.of(), 0 );
        }
        final List< SearchResult > ordered = applyHybridAndGraphRerank( query.query(), bm25 );
        final Map< String, List< RetrievedChunk > > chunksByPage =
            fetchContributingChunks( query.query(), ordered, query.chunksPerPage() );

        final PageListFilter f = query.filter();
        final List< RetrievedPage > pages = new ArrayList<>();
        for ( final SearchResult sr : ordered ) {
            final Page page = sr.getPage();
            if ( page == null ) continue;
            final ParsedPage parsed = parseCurrentText( page );
            if ( f != null && !matchesFilter( page, parsed, f ) ) continue;
            pages.add( buildRetrievedPage(
                page,
                parsed,
                sr.getScore(),
                chunksByPage.getOrDefault( page.getName(), List.of() ),
                fetchRelatedPages( page.getName() ) ) );
            if ( pages.size() >= query.maxPages() ) break;
        }
        return new RetrievalResult( query.query(), pages, ordered.size() );
    }

    private Map< String, List< RetrievedChunk > > fetchContributingChunks(
            final String query,
            final List< SearchResult > ordered,
            final int chunksPerPage ) {
        if ( chunkIndex == null || !chunkIndex.isReady()
                || chunkRepo == null || hybridSearch == null ) {
            return Map.of();
        }
        final Optional< float[] > embedding;
        try {
            embedding = hybridSearch.prefetchQueryEmbedding( query ).get( 2500, TimeUnit.MILLISECONDS );
        } catch ( final Exception e ) {
            LOG.debug( "Embedding fetch for chunks failed: {}", e.getMessage() );
            return Map.of();
        }
        if ( embedding.isEmpty() ) return Map.of();
        final List< ScoredChunk > topChunks = chunkIndex.topKChunks( embedding.get(), 200 );
        if ( topChunks.isEmpty() ) return Map.of();

        final Map< String, List< ScoredChunk > > grouped =
            groupChunksByInterestingPage( topChunks, interestingPageNames( ordered ), chunksPerPage );
        return shapeChunkOutput( grouped );
    }

    private static Set< String > interestingPageNames( final List< SearchResult > ordered ) {
        final Set< String > out = new HashSet<>();
        for ( final SearchResult sr : ordered ) {
            if ( sr.getPage() != null ) out.add( sr.getPage().getName() );
        }
        return out;
    }

    /**
     * Groups scored chunks by page name, pre-truncating each per-page list to
     * {@code chunksPerPage} so downstream callers don't re-slice.
     */
    private static Map< String, List< ScoredChunk > > groupChunksByInterestingPage(
            final List< ScoredChunk > topChunks,
            final Set< String > interestingPages,
            final int chunksPerPage ) {
        final Map< String, List< ScoredChunk > > grouped = new LinkedHashMap<>();
        for ( final ScoredChunk sc : topChunks ) {
            if ( !interestingPages.contains( sc.pageName() ) ) continue;
            final List< ScoredChunk > list = grouped.computeIfAbsent(
                sc.pageName(), k -> new ArrayList<>() );
            if ( list.size() < chunksPerPage ) list.add( sc );
        }
        return grouped;
    }

    /** Loads chunk bodies via {@link ContentChunkRepository} and shapes per-page RetrievedChunk lists. */
    private Map< String, List< RetrievedChunk > > shapeChunkOutput(
            final Map< String, List< ScoredChunk > > grouped ) {
        final List< UUID > allIds = new ArrayList<>();
        for ( final List< ScoredChunk > list : grouped.values() ) {
            for ( final ScoredChunk sc : list ) allIds.add( sc.chunkId() );
        }
        final Map< UUID, ContentChunkRepository.MentionableChunk > byId = new HashMap<>();
        for ( final ContentChunkRepository.MentionableChunk mc : chunkRepo.findByIds( allIds ) ) {
            byId.put( mc.id(), mc );
        }
        final Map< String, List< RetrievedChunk > > out = new LinkedHashMap<>();
        for ( final Map.Entry< String, List< ScoredChunk > > entry : grouped.entrySet() ) {
            final List< RetrievedChunk > pageChunks = new ArrayList<>( entry.getValue().size() );
            for ( final ScoredChunk sc : entry.getValue() ) {
                final ContentChunkRepository.MentionableChunk mc = byId.get( sc.chunkId() );
                if ( mc != null ) {
                    pageChunks.add( new RetrievedChunk(
                        mc.headingPath(), mc.text(), sc.score(), List.of() ) );
                }
            }
            out.put( entry.getKey(), pageChunks );
        }
        return out;
    }

    private Context buildContext() {
        final Page anchor = pageManager.getPage( "Main" );
        if ( anchor == null ) return null;
        try {
            return com.wikantik.api.spi.Wiki.context().create( engine, anchor );
        } catch ( final Exception e ) {
            LOG.debug( "Context build failed: {}", e.getMessage() );
            return null;
        }
    }

    private List< SearchResult > applyHybridAndGraphRerank( final String query,
                                                            final Collection< SearchResult > bm25 ) {
        final List< SearchResult > asList = new ArrayList<>( bm25 == null ? List.of() : bm25 );
        if ( hybridSearch == null || !hybridSearch.isEnabled() ) {
            return asList;
        }
        final List< String > names = new ArrayList<>();
        final Map< String, SearchResult > byName = new LinkedHashMap<>();
        for ( final SearchResult sr : asList ) {
            if ( sr.getPage() == null ) continue;
            names.add( sr.getPage().getName() );
            byName.putIfAbsent( sr.getPage().getName(), sr );
        }
        List< String > fused = hybridSearch.rerank( query, names );
        if ( graphRerank != null ) {
            try {
                fused = graphRerank.rerank( query, fused );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Graph rerank failed; using hybrid fused order: {}", e.getMessage(), e );
            }
        }
        if ( fused.equals( names ) ) return asList;
        final List< SearchResult > out = new ArrayList<>( fused.size() );
        for ( final String name : fused ) {
            final SearchResult existing = byName.get( name );
            if ( existing != null ) { out.add( existing ); continue; }
            final Page p = pageManager.getPage( name );
            if ( p == null ) continue;
            out.add( new DenseOnlySearchResult( p ) );
        }
        return out;
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
        return buildRetrievedPage( page, parseCurrentText( page ), 0.0, List.of(), List.of() );
    }

    /**
     * Reads the latest-version raw text for the given page and parses its
     * frontmatter. Null / missing text is treated as empty content.
     */
    private ParsedPage parseCurrentText( final Page page ) {
        final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
        return FrontmatterParser.parse( text == null ? "" : text );
    }

    /**
     * Builds a {@link RetrievedPage} from the given page + its parsed
     * frontmatter, filling in URL, summary, cluster, tags, author, and
     * lastModified from the canonical sources. Callers supply score,
     * contributing chunks, and related pages for their context.
     */
    private RetrievedPage buildRetrievedPage(
            final Page page,
            final ParsedPage parsed,
            final double score,
            final List< RetrievedChunk > chunks,
            final List< RelatedPage > related ) {
        return new RetrievedPage(
            page.getName(),
            buildUrl( page.getName() ),
            score,
            stringOrEmpty( parsed.metadata().get( "summary" ) ),
            stringOrNull( parsed.metadata().get( "cluster" ) ),
            stringList( parsed.metadata().get( "tags" ) ),
            chunks,
            related,
            page.getAuthor(),
            page.getLastModified() );
    }

    private String buildUrl( final String pageName ) {
        if ( publicBaseUrl == null || publicBaseUrl.isBlank() ) return pageName;
        final String base = publicBaseUrl.endsWith( "/" ) ? publicBaseUrl : publicBaseUrl + "/";
        return base + pageName;
    }

    private static String stringOrEmpty( final Object o ) {
        return o == null ? "" : o.toString();
    }

    private static String stringOrNull( final Object o ) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings( "unchecked" )
    private static List< String > stringList( final Object o ) {
        if ( !( o instanceof List< ? > raw ) ) return List.of();
        final List< String > out = new ArrayList<>( raw.size() );
        for ( final Object item : raw ) if ( item != null ) out.add( item.toString() );
        return List.copyOf( out );
    }

    private static final int RELATED_PAGES_LIMIT = 5;
    private static final int REASON_ENTITY_LIMIT = 3;

    /**
     * Finds pages related to {@code pageName} via shared extractor mentions.
     * Uses {@link MentionIndex#findRelatedPages} which walks
     * {@code chunk_entity_mentions} directly — no reliance on a KG node
     * keyed by page name (those stopped existing after the cycle-6
     * GraphProjector retirement).
     *
     * <p>The {@code reason} string lists the top shared entity names, so
     * the agent sees concrete evidence ("shared entities: BM25, Qwen3")
     * instead of an opaque similarity score.</p>
     */
    private List< RelatedPage > fetchRelatedPages( final String pageName ) {
        if ( mentionIndex == null ) return List.of();
        final List< MentionIndex.RelatedByMention > matches;
        try {
            matches = mentionIndex.findRelatedPages( pageName, RELATED_PAGES_LIMIT );
        } catch ( final RuntimeException e ) {
            LOG.debug( "MentionIndex.findRelatedPages failed for '{}': {}",
                pageName, e.getMessage() );
            return List.of();
        }
        if ( matches.isEmpty() ) return List.of();
        final List< RelatedPage > out = new ArrayList<>( matches.size() );
        for ( final MentionIndex.RelatedByMention m : matches ) {
            out.add( new RelatedPage( m.pageName(), describeSharedEntities( m ) ) );
        }
        return out;
    }

    private static String describeSharedEntities( final MentionIndex.RelatedByMention m ) {
        final List< String > names = m.sharedEntityNames();
        if ( names.isEmpty() ) {
            return "shared entities: " + m.sharedCount();
        }
        final int take = Math.min( names.size(), REASON_ENTITY_LIMIT );
        final String joined = String.join( ", ", names.subList( 0, take ) );
        final String more = names.size() > take ? " (+" + ( names.size() - take ) + " more)" : "";
        return "shared entities: " + joined + more;
    }

    @Override
    public PageList listPages( final PageListFilter filter ) {
        final PageListFilter f = filter == null ? PageListFilter.unfiltered() : filter;

        final Collection< Page > allPages;
        try {
            allPages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "getAllPages failed: {}", e.getMessage(), e );
            return new PageList( List.of(), 0, f.limit(), f.offset() );
        }

        final List< RetrievedPage > matched = new ArrayList<>();
        for ( final Page page : allPages ) {
            if ( page == null ) continue;
            final ParsedPage parsed = parseCurrentText( page );
            if ( !matchesFilter( page, parsed, f ) ) continue;
            matched.add( buildRetrievedPage( page, parsed, 0.0, List.of(), List.of() ) );
        }
        final int total = matched.size();
        final int from = Math.min( f.offset(), total );
        final int to = Math.min( from + Math.max( f.limit(), 1 ), total );
        return new PageList( matched.subList( from, to ), total, f.limit(), f.offset() );
    }

    private boolean matchesFilter( final Page page, final ParsedPage parsed, final PageListFilter f ) {
        if ( f.cluster() != null
                && !f.cluster().equals( parsed.metadata().get( "cluster" ) ) ) {
            return false;
        }
        if ( f.type() != null
                && !f.type().equals( parsed.metadata().get( "type" ) ) ) {
            return false;
        }
        if ( f.author() != null && !f.author().equals( page.getAuthor() ) ) {
            return false;
        }
        final List< String > tags = stringList( parsed.metadata().get( "tags" ) );
        for ( final String required : f.tags() ) {
            if ( !tags.contains( required ) ) return false;
        }
        if ( f.modifiedAfter() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isBefore( f.modifiedAfter() ) ) return false;
        }
        if ( f.modifiedBefore() != null ) {
            final Date d = page.getLastModified();
            if ( d == null || d.toInstant().isAfter( f.modifiedBefore() ) ) return false;
        }
        return true;
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
            final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
            if ( text == null ) continue;
            final ParsedPage parsed = FrontmatterParser.parse( text );
            final Object raw = parsed.metadata().get( field );
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
}
