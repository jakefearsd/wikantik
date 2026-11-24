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
package com.wikantik.livevalues;

import com.wikantik.TestEngine;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.core.Context;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.citation.CitationRepository;
import com.wikantik.content.ArticleSummary;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.content.RecentArticlesQuery;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.knowledge.agent.AgentHintsDeriver;
import com.wikantik.knowledge.agent.DefaultForAgentProjectionService;
import com.wikantik.knowledge.agent.ForAgentMetrics;
import com.wikantik.knowledge.agent.HubSummarySynthesizer;
import com.wikantik.plugin.HubSetPlugin;
import com.wikantik.preview.PageExcerpts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The reader surfaces of wikantik-main that show a page's summary or body: tokens expand, the stored text keeps them. */
class MainReaderSurfacesLiveValuesTest {

    private static final String SUMMARY = "Over " + TOKEN + " articles";
    private static final String BODY = "# Live Page\n\nThis wiki now holds " + TOKEN + " articles in total.\n";

    private TestEngine engine;
    private final StubLiveValues stub = new StubLiveValues();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.serviceRegistry().put( LiveValues.class, stub );
    }

    @AfterEach
    void tearDown() {
        engine.deleteQuietly( "LiveSurfaceOne", "LiveSurfaceHub" );
        engine.stop();
    }

    private static void assertExpanded( final String out ) {
        assertTrue( out.contains( VALUE ), out );
        assertFalse( out.contains( "{{wiki:" ), out );
    }

    private void saveLivePage() throws Exception {
        engine.saveText( "LiveSurfaceOne",
            "---\ntype: article\ncluster: livesurf\nsummary: " + SUMMARY + "\n---\n" + BODY );
    }

    @Test
    void recentArticlesExcerptExpands() throws Exception {
        saveLivePage();
        engine.getManager( RecentArticlesManager.class ).clearCache();
        final Context context = Wiki.context().create( engine, Wiki.contents().page( engine, "LiveSurfaceOne" ) );
        final List< ArticleSummary > articles = engine.getManager( RecentArticlesManager.class )
            .getRecentArticles( context, new RecentArticlesQuery().includeExcerpt( true ).excerptLength( 300 ) );

        final ArticleSummary ours = articles.stream().filter( a -> "LiveSurfaceOne".equals( a.getName() ) )
            .findFirst().orElseThrow();
        assertExpanded( ours.getExcerpt() );
    }

    @Test
    void hubSetCardSummaryExpands() throws Exception {
        saveLivePage();
        engine.saveText( "LiveSurfaceHub", "---\ntype: hub\ncluster: livesurf\nrelated: [LiveSurfaceOne]\n---\nHub." );
        final Context context = Wiki.context().create( engine, Wiki.contents().page( engine, "LiveSurfaceHub" ) );

        final String html = new HubSetPlugin().execute( context, Map.of( "hub", "LiveSurfaceHub", "detail", "cards" ) );

        assertExpanded( html );
    }

    @Test
    void pageExcerptsExpandsBody() {
        assertExpanded( PageExcerpts.excerpt( BODY, 280, stub ) );
        assertTrue( PageExcerpts.excerpt( BODY, 280 ).contains( "{{wiki:" ), "the two-argument form stays verbatim" );
    }

    @Test
    void forAgentBodyExpandsAndCacheFollowsTheValue() {
        final PageDescriptor d = new PageDescriptor( "01LIVE", "LiveSurfaceOne", "Live Surface One", PageType.ARTICLE,
            "livesurf", List.of(), "plain", Instant.parse( "2026-11-01T00:00:00Z" ), false );
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        when( idx.getByCanonicalId( "01LIVE" ) ).thenReturn( Optional.of( d ) );
        when( idx.resolveSlugFromCanonicalId( "01LIVE" ) ).thenReturn( Optional.of( "LiveSurfaceOne" ) );
        when( idx.verificationOf( "01LIVE" ) ).thenReturn( Optional.empty() );
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( anyString(), eq( -1 ) ) ).thenReturn( "---\ntype: article\n---\n## Over " + TOKEN
            + " articles\n\nThis wiki now holds " + TOKEN + " articles in total.\n" );
        when( pm.getVersionHistory( anyString() ) ).thenReturn( List.of() );
        final ReferenceManager refs = mock( ReferenceManager.class );
        when( refs.findReferrers( anyString() ) ).thenReturn( Set.of() );
        final Map< Serializable, Object > store = new HashMap<>();
        final CachingManager cache = mock( CachingManager.class );
        when( cache.enabled( CachingManager.CACHE_FOR_AGENT ) ).thenReturn( true );
        when( cache.get( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() ) )
            .thenAnswer( inv -> store.get( inv.< Serializable >getArgument( 1 ) ) );
        doAnswer( inv -> store.put( inv.getArgument( 1 ), inv.getArgument( 2 ) ) )
            .when( cache ).put( eq( CachingManager.CACHE_FOR_AGENT ), any(), any() );
        final DefaultForAgentProjectionService svc = new DefaultForAgentProjectionService( idx, pm, cache,
            new ForAgentMetrics(), new AgentHintsDeriver( idx, pm, refs ), new HubSummarySynthesizer(),
            mock( CitationRepository.class ), () -> stub );

        final ForAgentProjection first = svc.project( "01LIVE", slug -> true ).orElseThrow();
        assertExpanded( first.headingsOutline().toString() );
        assertExpanded( first.keyFacts().toString() );

        stub.value( "1,200+" );
        stub.generation( "g2" );
        final ForAgentProjection second = svc.project( "01LIVE", slug -> true ).orElseThrow();
        assertTrue( second.headingsOutline().toString().contains( "1,200+" ),
            "a changed value must not serve the cached body: " + second.headingsOutline() );
    }

    @Test
    void frontmatterDefaultsFilterKeepsToken() {
        final Properties props = new Properties();
        props.setProperty( FrontmatterDefaultsFilter.PROP_AUTO_DEFAULTS, "true" );
        final FrontmatterDefaultsFilter filter = new FrontmatterDefaultsFilter( name -> false, props );

        final String saved = filter.applyDefaults( "LiveDefaults", "This wiki now holds " + TOKEN
            + " articles in total, and it keeps growing every single week of the year." );

        assertTrue( saved.contains( TOKEN ), "the write path stores the raw token: " + saved );
        assertFalse( saved.contains( VALUE ), saved );
    }
}
