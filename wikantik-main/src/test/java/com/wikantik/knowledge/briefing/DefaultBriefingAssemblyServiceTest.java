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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultBriefingAssemblyServiceTest {

    private static final int DEFAULT_BUDGET = 6000;
    private static final int MAX_BUDGET = 24000;

    // --------------------------------------------------------------- helpers

    private static BundleSection sec( final String slug, final String heading, final String text ) {
        return new BundleSection( "01" + slug, slug, List.of( heading ), text, 0.9,
            new CitationHandle( "01" + slug, 1, List.of( heading ), text, "sha" ) );
    }

    /** A page body with a title/summary frontmatter block plus {@code fill} filler chars. */
    private static String page( final String title, final String summary, final int fill ) {
        return "---\ntitle: " + title + "\nsummary: " + summary + "\n---\n" + "x".repeat( fill );
    }

    /** Fresh PageManager mock knowing "BillingProcess" (~400 chars) and "Q3Goals" (~400 chars). */
    private static PageManager pageManagerFixture() {
        final PageManager pm = mock( PageManager.class );
        stub( pm, "BillingProcess", page( "Billing Process", "Billing summary.", 350 ) );
        stub( pm, "Q3Goals", page( "Q3 Goals", "Quarterly goals summary.", 350 ) );
        return pm;
    }

    private static void stub( final PageManager pm, final String slug, final String body ) {
        when( pm.getPage( slug ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( slug, PageProvider.LATEST_VERSION ) ).thenReturn( body );
    }

    private static PageDescriptor desc( final String slug, final Instant updated ) {
        return new PageDescriptor( "01" + slug, slug, slug, null, "billing",
            List.of(), null, updated, Optional.empty() );
    }

    private static ClusterDetails cluster( final String name, final PageDescriptor hub,
                                           final List< PageDescriptor > articles ) {
        return new ClusterDetails( name, hub, articles, Map.of(), Instant.now() );
    }

    private static BriefingItem itemBySlug( final ContextBriefing b, final String slug ) {
        return b.items().stream().filter( i -> slug.equals( i.slug() ) ).findFirst().orElse( null );
    }

    /** Configurable StructuralIndexService double; only the three lookups the assembler uses are wired. */
    private static final class StubIndex implements StructuralIndexService {
        final Map< String, ClusterDetails > clusters = new HashMap<>();
        final Map< String, String > slugFromCanonical = new HashMap<>();
        final Map< String, String > canonicalFromSlug = new HashMap<>();

        @Override public Optional< ClusterDetails > getCluster( final String name ) {
            return Optional.ofNullable( clusters.get( name ) );
        }
        @Override public Optional< String > resolveSlugFromCanonicalId( final String canonicalId ) {
            return Optional.ofNullable( slugFromCanonical.get( canonicalId ) );
        }
        @Override public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
            return Optional.ofNullable( canonicalFromSlug.get( slug ) );
        }
        @Override public List< ClusterSummary > listClusters() { throw new UnsupportedOperationException(); }
        @Override public List< TagSummary > listTags( final int minPages ) { throw new UnsupportedOperationException(); }
        @Override public List< PageDescriptor > listPagesByType( final PageType type ) { throw new UnsupportedOperationException(); }
        @Override public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) { throw new UnsupportedOperationException(); }
        @Override public Sitemap sitemap() { throw new UnsupportedOperationException(); }
        @Override public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) { throw new UnsupportedOperationException(); }
        @Override public void rebuild() { throw new UnsupportedOperationException(); }
        @Override public IndexHealth health() { throw new UnsupportedOperationException(); }
        @Override public List< StructuralConflict > conflicts() { throw new UnsupportedOperationException(); }
        @Override public Optional< Verification > verificationOf( final String canonicalId ) { throw new UnsupportedOperationException(); }
        @Override public StructuralProjectionSnapshot snapshot() { throw new UnsupportedOperationException(); }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void noSourcesReturnsEmptyBriefingWithBudget() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble( new BriefingRequest( null, null, null, null, null ) );

        assertTrue( b.sections().isEmpty(), "no prompt → no sections" );
        assertTrue( b.items().isEmpty(), "no pins/clusters → no items" );
        assertEquals( 0, b.usedTokens() );
        assertEquals( DEFAULT_BUDGET, b.budgetTokens() );
    }

    @Test
    void promptOnlyFillsSectionsWithinBudget() {
        // 3 sections of 400 chars (~100 tokens each); budget 250 → only 2 fit.
        final String text = "y".repeat( 400 );
        final ContextBundle bundle = new ContextBundle( "how do we deploy",
            List.of( sec( "PageA", "H1", text ), sec( "PageB", "H2", text ), sec( "PageC", "H3", text ) ),
            new BundleCoverage( 3, 3, 0.8, BundleCoverage.STRONG ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> bundle, new StubIndex(), pageManagerFixture(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, null, "how do we deploy", 250, null ) );

        assertEquals( 2, b.sections().size(), "budget 250 admits 2 of 3 sections" );
        assertEquals( 2, b.coverage().sectionCount(), "coverage recounted over kept sections" );
        assertTrue( b.usedTokens() >= 200 && b.usedTokens() <= 250,
            "usedTokens within [200,250], was " + b.usedTokens() );
    }

    @Test
    void pinFullBodyWhenItFits() {
        final PageManager pm = pageManagerFixture();
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "BillingProcess" ), null, null, null, null ) );

        assertEquals( 1, b.items().size() );
        final BriefingItem it = b.items().get( 0 );
        assertEquals( "BillingProcess", it.slug() );
        assertEquals( "pin", it.origin() );
        assertTrue( it.included() );
        assertEquals( pm.getPureText( "BillingProcess", PageProvider.LATEST_VERSION ), it.content() );
        assertEquals( "Billing Process", it.title() );
        assertEquals( "Billing summary.", it.summary() );
    }

    @Test
    void pinDegradesToPointerWhenBudgetExhausted() {
        final PageManager pm = pageManagerFixture();
        stub( pm, "BigPage", page( "Big Page", "Big summary.", 2000 ) );  // ~500 tokens
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pm, DEFAULT_BUDGET, MAX_BUDGET );

        // request budget 10 → floor-clamped to 200; a ~500-token body can't fit.
        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "BigPage" ), null, null, 10, null ) );

        assertEquals( 200, b.budgetTokens(), "budget floor-clamped to 200" );
        assertEquals( 1, b.items().size() );
        final BriefingItem it = b.items().get( 0 );
        assertFalse( it.included(), "over-budget pin degrades to pointer" );
        assertNull( it.content(), "pointer carries no content" );
        assertEquals( 0, b.usedTokens(), "usedTokens unchanged for a pointer" );
        assertEquals( "Big Page", it.title() );
    }

    @Test
    void unknownPinWarnsAndContinues() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "Nope", "BillingProcess" ), null, null, null, null ) );

        assertTrue( b.warnings().stream().anyMatch( w -> w.contains( "unknown pin: Nope" ) ),
            "warns for the unresolvable pin: " + b.warnings() );
        final BriefingItem bill = itemBySlug( b, "BillingProcess" );
        assertTrue( bill != null && bill.included(), "the resolvable pin is still included" );
    }

    @Test
    void pinResolvesCanonicalId() {
        final StubIndex idx = new StubIndex();
        idx.slugFromCanonical.put( "01BILL", "BillingProcess" );
        idx.canonicalFromSlug.put( "BillingProcess", "01BILL" );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );

        // "01BILL" is a canonical id, not a page name → resolved to the BillingProcess slug.
        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "01BILL" ), null, null, null, null ) );

        assertEquals( 1, b.items().size() );
        final BriefingItem it = b.items().get( 0 );
        assertEquals( "BillingProcess", it.slug() );
        assertEquals( "01BILL", it.canonicalId() );
        assertTrue( it.included() );
    }

    @Test
    void pinSupersedesItsOwnBundleSections() {
        // Two 60-token sections from BillingProcess + one from Other; the pin body supersedes its own sections.
        final String secText = "z".repeat( 240 );   // 60 tokens
        final String otherText = "o".repeat( 240 ); // 60 tokens
        final ContextBundle bundle = new ContextBundle( "billing",
            List.of( sec( "BillingProcess", "H1", secText ),
                     sec( "BillingProcess", "H2", secText ),
                     sec( "Other", "HO", otherText ) ),
            new BundleCoverage( 3, 2, 0.8, BundleCoverage.STRONG ) );
        final PageManager pm = pageManagerFixture();
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> bundle, new StubIndex(), pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "BillingProcess" ), null, "billing", 10000, null ) );

        assertEquals( 1, b.sections().size(), "BillingProcess sections superseded, only Other remains" );
        assertEquals( "Other", b.sections().get( 0 ).slug() );
        assertEquals( 1, b.items().size() );
        assertEquals( "BillingProcess", b.items().get( 0 ).slug() );
        assertTrue( b.items().get( 0 ).included() );

        final String body = pm.getPureText( "BillingProcess", PageProvider.LATEST_VERSION );
        assertEquals( TokenEstimator.estimate( otherText ) + TokenEstimator.estimate( body ),
            b.usedTokens(), "refunded the two superseded sections, charged the full body" );
    }

    @Test
    void strictScopeDropsOutOfClusterSections() {
        final StubIndex idx = new StubIndex();
        idx.clusters.put( "billing",
            cluster( "billing", null, List.of( desc( "BillingProcess", Instant.now() ) ) ) );
        final ContextBundle bundle = new ContextBundle( "billing",
            List.of( sec( "BillingProcess", "H1", "in-scope text" ),
                     sec( "Rogue", "HR", "out-of-scope text" ) ),
            new BundleCoverage( 2, 2, 0.8, BundleCoverage.STRONG ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> bundle, idx, pageManagerFixture(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, List.of( "billing" ), "billing", 10000, ScopeMode.STRICT ) );

        assertEquals( 1, b.sections().size(), "STRICT drops the out-of-cluster section" );
        assertEquals( "BillingProcess", b.sections().get( 0 ).slug() );
        assertEquals( 1, b.coverage().sectionCount(), "coverage recounted after the scope drop" );
    }

    @Test
    void preferScopeReordersInScopeFirst() {
        final StubIndex idx = new StubIndex();
        idx.clusters.put( "billing",
            cluster( "billing", null, List.of( desc( "BillingProcess", Instant.now() ) ) ) );
        // Bundle lists Rogue BEFORE BillingProcess; PREFER must reorder in-scope first.
        final ContextBundle bundle = new ContextBundle( "billing",
            List.of( sec( "Rogue", "HR", "out-of-scope text" ),
                     sec( "BillingProcess", "H1", "in-scope text" ) ),
            new BundleCoverage( 2, 2, 0.8, BundleCoverage.STRONG ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> bundle, idx, pageManagerFixture(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, List.of( "billing" ), "billing", 10000, ScopeMode.PREFER ) );

        assertEquals( 2, b.sections().size(), "PREFER keeps both sections" );
        assertEquals( "BillingProcess", b.sections().get( 0 ).slug(), "in-scope section reordered first" );
        assertEquals( "Rogue", b.sections().get( 1 ).slug() );
    }

    @Test
    void clusterMembersHubFirstThenByUpdatedDesc() {
        final Instant older = Instant.parse( "2026-01-01T00:00:00Z" );
        final Instant newer = Instant.parse( "2026-06-01T00:00:00Z" );
        final StubIndex idx = new StubIndex();
        idx.clusters.put( "billing", cluster( "billing", desc( "BillingHub", newer ),
            List.of( desc( "ArticleA", older ), desc( "ArticleB", newer ) ) ) );
        final PageManager pm = pageManagerFixture();
        stub( pm, "BillingHub", page( "Billing Hub", "Hub summary.", 40 ) );
        stub( pm, "ArticleA", page( "Article A", "A summary.", 40 ) );
        stub( pm, "ArticleB", page( "Article B", "B summary.", 40 ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, List.of( "billing" ), null, null, null ) );

        assertEquals( List.of( "BillingHub", "ArticleB", "ArticleA" ),
            b.items().stream().map( BriefingItem::slug ).toList(),
            "hub first, then articles by updated desc" );
    }

    @Test
    void bundleFailureFailsSoftToPins() {
        final BundleAssemblyService failing = q -> { throw new RuntimeException( "boom" ); };
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            failing, new StubIndex(), pageManagerFixture(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "BillingProcess" ), null, "anything", null, null ) );

        assertTrue( b.sections().isEmpty(), "bundle failure yields no sections" );
        assertTrue( b.warnings().stream().anyMatch( w -> w.contains( "degraded" ) ),
            "warns that the briefing degraded: " + b.warnings() );
        final BriefingItem bill = itemBySlug( b, "BillingProcess" );
        assertTrue( bill != null && bill.included(), "pins still assembled after a bundle failure" );
    }

    @Test
    void nullBundleServiceWarnsOnPrompt() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            null, new StubIndex(), pageManagerFixture(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "BillingProcess" ), null, "anything", null, null ) );

        assertTrue( b.warnings().stream().anyMatch(
                w -> w.contains( "bundle service unavailable" ) ),
            "warns when no bundle service is wired: " + b.warnings() );
        final BriefingItem bill = itemBySlug( b, "BillingProcess" );
        assertTrue( bill != null && bill.included(), "pins still assembled without a bundle service" );
    }

    @Test
    void budgetClampsToMax() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, null, null, 999999, null ) );

        assertEquals( MAX_BUDGET, b.budgetTokens(), "requested budget clamped to max" );
    }
}
