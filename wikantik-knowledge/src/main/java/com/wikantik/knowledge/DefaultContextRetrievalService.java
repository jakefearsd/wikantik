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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.MetadataValue;
import com.wikantik.api.knowledge.PageList;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.search.FrontmatterMetadataCache;
import com.wikantik.search.SearchManager;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.GraphRerankStep;
import com.wikantik.search.hybrid.HybridSearchService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final SearchManager searchManager;
    private final HybridSearchService hybridSearch;
    private final GraphRerankStep graphRerank;
    private final ChunkVectorIndex chunkIndex;
    private final ContentChunkRepository chunkRepo;
    private final NodeMentionSimilarity similarity;
    private final PageManager pageManager;
    private final FrontmatterMetadataCache fmCache;
    private final String publicBaseUrl;

    public DefaultContextRetrievalService(
            final SearchManager searchManager,
            final HybridSearchService hybridSearch,
            final GraphRerankStep graphRerank,
            final ChunkVectorIndex chunkIndex,
            final ContentChunkRepository chunkRepo,
            final NodeMentionSimilarity similarity,
            final PageManager pageManager,
            final FrontmatterMetadataCache fmCache,
            final String publicBaseUrl ) {
        if ( searchManager == null ) throw new IllegalArgumentException( "searchManager required" );
        if ( pageManager == null ) throw new IllegalArgumentException( "pageManager required" );
        // hybridSearch, graphRerank, chunkIndex, chunkRepo, similarity, fmCache all nullable:
        // the service degrades rather than failing when optional deps are absent.
        this.searchManager = searchManager;
        this.hybridSearch = hybridSearch;
        this.graphRerank = graphRerank;
        this.chunkIndex = chunkIndex;
        this.chunkRepo = chunkRepo;
        this.similarity = similarity;
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
            sm,
            engine.getManager( HybridSearchService.class ),
            engine.getManager( GraphRerankStep.class ),
            engine.getManager( ChunkVectorIndex.class ),
            engine.getManager( ContentChunkRepository.class ),
            engine.getManager( NodeMentionSimilarity.class ),
            pm,
            engine.getManager( FrontmatterMetadataCache.class ),
            engine.getBaseURL() );
    }

    @Override
    public RetrievalResult retrieve( final ContextQuery query ) {
        throw new UnsupportedOperationException( "implemented in task 9" );
    }

    @Override
    public RetrievedPage getPage( final String pageName ) {
        if ( pageName == null || pageName.isBlank() ) return null;
        final Page page = pageManager.getPage( pageName );
        if ( page == null ) return null;
        final String rawText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
        return new RetrievedPage(
            page.getName(),
            buildUrl( page.getName() ),
            0.0,
            stringOrEmpty( parsed.metadata().get( "summary" ) ),
            stringOrNull( parsed.metadata().get( "cluster" ) ),
            stringList( parsed.metadata().get( "tags" ) ),
            List.of(),
            List.of(),
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
            final String text = pageManager.getPureText( page.getName(), PageProvider.LATEST_VERSION );
            final ParsedPage parsed = FrontmatterParser.parse( text == null ? "" : text );
            if ( !matchesFilter( page, parsed, f ) ) continue;
            matched.add( new RetrievedPage(
                page.getName(),
                buildUrl( page.getName() ),
                0.0,
                stringOrEmpty( parsed.metadata().get( "summary" ) ),
                stringOrNull( parsed.metadata().get( "cluster" ) ),
                stringList( parsed.metadata().get( "tags" ) ),
                List.of(),
                List.of(),
                page.getAuthor(),
                page.getLastModified() ) );
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
