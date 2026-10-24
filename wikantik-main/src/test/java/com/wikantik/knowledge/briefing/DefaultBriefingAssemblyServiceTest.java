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
import com.wikantik.api.bundle.BundleOptions;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.briefing.BriefingConfig.BriefingLimits;
import com.wikantik.knowledge.briefing.BriefingTestFixtures.StubIndex;
import com.wikantik.util.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wikantik.knowledge.briefing.BriefingTestFixtures.cluster;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.corpusEdges;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.corpusIndex;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.desc;
import static com.wikantik.knowledge.briefing.BriefingTestFixtures.refs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static BriefingItem itemBySlug( final ContextBriefing b, final String slug ) {
        return b.items().stream().filter( i -> slug.equals( i.slug() ) ).findFirst().orElse( null );
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
    void pinnedBodyCarriesNoAccessRuleDirectives() {
        final PageManager pm = pageManagerFixture();
        stub( pm, "RuledPage", "---\ntitle: Ruled\nsummary: Ruled summary.\n---\n"
            + "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\nBody of the ruled page.\n" );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "RuledPage" ), null, null, null, null ) );

        final BriefingItem it = itemBySlug( b, "RuledPage" );
        assertTrue( it != null && it.included(), "the pin is included" );
        assertFalse( it.content().contains( "leak-probe@example.test" ), it.content() );
        assertTrue( it.content().contains( "Body of the ruled page." ) );
        assertEquals( "Ruled", it.title(), "frontmatter is still read" );
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
    void unknownPinSkippedWithoutWarning_warningOwnedByGate() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "Nope", "BillingProcess" ), null, null, null, null ) );

        // The assembler no longer emits "unknown pin" — that is BriefingAclGate's job (oracle defence).
        assertFalse( b.warnings().stream().anyMatch( w -> w.contains( "unknown pin" ) ),
            "assembler must NOT emit pin warnings: " + b.warnings() );
        assertNull( itemBySlug( b, "Nope" ), "unresolvable pin yields no item" );
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

    // ------------------------------------------- tiered partition (corpus: roller)
    //
    // Requesting "roller" over the eight-page corpus gives tier 1 = RollerHub, RollerMcp,
    // RollerTelemetrySpec; tier 2 (cited) = TelemetryContract, StackHub; everything else
    // (CommerceDataFlow, StockroomHub, Phase7Plan) is out of scope.

    private static ContextBundle bundleOf( final BundleSection... sections ) {
        return new ContextBundle( "q", List.of( sections ),
            new BundleCoverage( sections.length, sections.length, 0.8, BundleCoverage.STRONG ) );
    }

    private static BriefingRequest rollerRequest( final ScopeMode mode ) {
        return new BriefingRequest( null, List.of( "roller" ), "roller", 10000, mode );
    }

    private static List< String > headings( final ContextBriefing b ) {
        return b.sections().stream().map( s -> s.slug() + "/" + s.headingPath().get( 0 ) ).toList();
    }

    @Test
    void strictScopeDropsOutOfClusterSections() {
        final ContextBundle bundle = bundleOf(
            sec( "CommerceDataFlow", "H1", "out-of-scope text" ),
            sec( "TelemetryContract", "H2", "cited text" ),
            sec( "RollerMcp", "H3", "member text" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.STRICT ) );

        assertEquals( List.of( "RollerMcp/H3", "TelemetryContract/H2" ), headings( b ),
            "STRICT drops the out-of-scope section but keeps the tier-2 (cited) one, tier 1 first" );
        assertEquals( 2, b.coverage().sectionCount(), "coverage recounted after the scope drop" );
        assertTrue( b.items().stream().noneMatch( i -> "elsewhere".equals( i.origin() ) ),
            "STRICT never emits elsewhere pointers: " + slugsOf( b ) );
    }

    @Test
    void strictWithNothingInScopeYieldsNoSectionsAndNoElsewhere() {
        final ContextBundle bundle = bundleOf(
            sec( "CommerceDataFlow", "H1", "out one" ), sec( "Phase7Plan", "H2", "out two" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.STRICT ) );

        assertTrue( b.sections().isEmpty(), "nothing in scope, nothing kept: " + headings( b ) );
        assertEquals( 0, b.coverage().sectionCount() );
        assertTrue( b.items().stream().noneMatch( i -> "elsewhere".equals( i.origin() ) ),
            "and the dropped pages are not mentioned either: " + slugsOf( b ) );
    }

    @Test
    void retrievalCoverageIsThePrePartitionBundleCoverage() {
        // Strong retrieval, but every hit lies outside the requested cluster: PREFER moves them all
        // to "elsewhere", so nothing is delivered as a section. The delivered coverage says so;
        // the retrieval coverage (what the query log records) still says retrieval was strong.
        final BundleCoverage found = new BundleCoverage( 4, 2, 0.91, BundleCoverage.STRONG );
        final ContextBundle bundle = new ContextBundle( "q", List.of(
            sec( "CommerceDataFlow", "H1", "out one" ), sec( "Phase7Plan", "H2", "out two" ),
            sec( "CommerceDataFlow", "H3", "out three" ), sec( "Phase7Plan", "H4", "out four" ) ), found );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertTrue( b.sections().isEmpty(), headings( b ).toString() );
        assertEquals( 0, b.coverage().sectionCount(), "coverage keeps meaning what was delivered as sections" );
        assertEquals( found, b.retrievalCoverage(), "retrievalCoverage is the bundle's own coverage, pre-partition" );
        assertEquals( BundleCoverage.STRONG, b.retrievalCoverage().confidence() );
    }

    @Test
    void strictWithOnlyUnknownClustersYieldsNoSections() {
        final ContextBundle bundle = bundleOf( sec( "CommerceDataFlow", "H1", "out" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( new BriefingRequest( null, List.of( "nonesuch" ), "q", 10000, ScopeMode.STRICT ) );

        assertTrue( b.sections().isEmpty(), "a requested-but-empty scope puts every section out of scope" );
        assertTrue( b.items().isEmpty() );
        assertTrue( b.warnings().contains( "unknown cluster: nonesuch" ), b.warnings().toString() );
    }

    @Test
    void preferScopeKeepsTiersAsSectionsAndOutAsElsewhere() {
        // Pool in rank order, tiers interleaved: out, tier 2, tier 1, out, tier 1.
        final ContextBundle bundle = bundleOf(
            sec( "CommerceDataFlow", "HC", "out one" ),
            sec( "TelemetryContract", "HT", "cited text" ),
            sec( "RollerMcp", "HM", "member text" ),
            sec( "Phase7Plan", "HP", "out two" ),
            sec( "RollerHub", "HH", "hub text" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( List.of( "RollerMcp/HM", "RollerHub/HH", "TelemetryContract/HT" ), headings( b ),
            "sections are tier 1 then tier 2, each in rank order; out-of-scope sections are not bodies" );
        assertEquals( 3, b.coverage().sectionCount() );

        final List< BriefingItem > elsewhere = b.items().stream()
            .filter( i -> "elsewhere".equals( i.origin() ) ).toList();
        assertEquals( List.of( "CommerceDataFlow", "Phase7Plan" ), elsewhere.stream().map( BriefingItem::slug ).toList() );
        assertTrue( elsewhere.stream().noneMatch( BriefingItem::included ), "elsewhere items are pointers" );
        assertTrue( elsewhere.stream().allMatch( i -> i.content() == null ) );
        assertEquals( List.of( "simple-agility", "stockroom" ),
            elsewhere.stream().map( BriefingItem::cluster ).toList(), "each names whose page it is" );
        assertEquals( "01CommerceDataFlow", elsewhere.get( 0 ).canonicalId() );
        assertEquals( "CommerceDataFlow", elsewhere.get( 0 ).title() );
        assertEquals( "HC", elsewhere.get( 0 ).summary(), "the summary is the matched section's heading path" );
        assertEquals( List.of( "cluster", "cluster", "cluster", "cited", "cited", "elsewhere", "elsewhere" ),
            b.items().stream().map( BriefingItem::origin ).toList(),
            "items read pins, members, cited, elsewhere" );
    }

    @Test
    void elsewhereSummaryJoinsTheHeadingPath() {
        final ContextBundle bundle = bundleOf( new BundleSection( "01Phase7Plan", "Phase7Plan",
            List.of( "Plan", "Risks" ), "text", 0.9, new CitationHandle( "01Phase7Plan", 1, List.of( "Plan", "Risks" ), "text", "sha" ) ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( "Plan › Risks", itemBySlug( b, "Phase7Plan" ).summary() );
    }

    @Test
    void preferWithOnlyUnknownClustersKeepsSectionsAndWarns() {
        // A typo in a project's session-start config must not cost every session its content:
        // with no scoped page at all, PREFER has nothing to prefer and degrades to unscoped.
        final ContextBundle bundle = bundleOf( sec( "CommerceDataFlow", "H1", "out" ), sec( "Phase7Plan", "H2", "also out" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( new BriefingRequest( null, List.of( "nonesuch" ), "q", 10000, ScopeMode.PREFER ) );

        assertEquals( List.of( "CommerceDataFlow/H1", "Phase7Plan/H2" ), headings( b ), "the sections are kept as bodies" );
        assertEquals( 2, b.coverage().sectionCount() );
        assertTrue( b.items().stream().noneMatch( i -> "elsewhere".equals( i.origin() ) ),
            "nothing is demoted to elsewhere: " + slugsOf( b ) );
        assertTrue( b.warnings().contains( "unknown cluster: nonesuch" ), b.warnings().toString() );
    }

    @Test
    void preferWithOnlyUnknownClustersStillAppliesThePerPageCap() {
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 2, 36, true, 12, 8, 30 );
        final ContextBundle bundle = bundleOf( sec( "PageA", "H0", "a" ), sec( "PageA", "H1", "b" ),
            sec( "PageA", "H2", "c" ), sec( "PageB", "H0", "d" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), limits, q -> bundle )
            .assemble( new BriefingRequest( null, List.of( "nonesuch" ), "q", 10000, ScopeMode.PREFER ) );

        assertEquals( List.of( "PageA/H0", "PageA/H1", "PageB/H0" ), headings( b ) );
    }

    @Test
    void scopeComputationFailureDegradesToUnscopedWithWarning() {
        // The reference data is the one collaborator whose failure used to escape: a scope that cannot
        // be computed must cost the agent its project pages, not its whole briefing.
        final ReferenceManager brokenRefs = mock( ReferenceManager.class );
        when( brokenRefs.findRefersTo( any() ) ).thenThrow( new IllegalStateException( "reference data unavailable" ) );
        final ContextBundle bundle = bundleOf( sec( "CommerceDataFlow", "H1", "out of cluster" ) );

        final ContextBriefing b = new DefaultBriefingAssemblyService(
            q -> bundle, corpusIndex(), brokenRefs, corpusPageManager(), BriefingLimits.defaults() )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( List.of( "CommerceDataFlow/H1" ), headings( b ), "the prompt's sections survive, unscoped" );
        assertTrue( b.items().isEmpty(), "no scope, so no cluster, cited or elsewhere items: " + slugsOf( b ) );
        assertTrue( b.warnings().contains( "scope computation failed; clusters skipped" ), b.warnings().toString() );
    }

    @Test
    void elsewhereDeduplicatesBySlugFirstOccurrenceWins() {
        final ContextBundle bundle = bundleOf(
            sec( "CommerceDataFlow", "First", "a" ), sec( "Phase7Plan", "P", "b" ),
            sec( "CommerceDataFlow", "Second", "c" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( List.of( "CommerceDataFlow", "Phase7Plan" ), slugsOf( b ).subList( 5, 7 ) );
        assertEquals( 7, b.items().size(), "five scoped items plus one elsewhere item per distinct page" );
        assertEquals( "First", itemBySlug( b, "CommerceDataFlow" ).summary() );
    }

    @Test
    void elsewhereCappedAtMax() {
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 4, 36, true, 12, 2, 30 );
        final ContextBundle bundle = bundleOf(
            sec( "CommerceDataFlow", "H1", "a" ), sec( "StockroomHub", "H2", "b" ), sec( "Phase7Plan", "H3", "c" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), limits, q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( List.of( "CommerceDataFlow", "StockroomHub" ),
            b.items().stream().filter( i -> "elsewhere".equals( i.origin() ) ).map( BriefingItem::slug ).toList(),
            "elsewhereMax of 2 keeps the two best-ranked out-of-scope pages" );
    }

    @Test
    void elsewhereSpendsNoBudgetAndIgnoresThePointerCap() {
        final StubIndex idx = new StubIndex();
        // The member has no live page, so no item is built: any spend is from the section alone.
        idx.clusters.put( "billing",
            cluster( "billing", null, List.of( desc( "BillingProcess", Instant.now() ) ) ) );
        idx.register( desc( "Rogue", Instant.now() ) );
        final String memberText = "m".repeat( 400 );
        final ContextBundle bundle = bundleOf(
            sec( "BillingProcess", "H1", memberText ), sec( "Rogue", "HR", "r".repeat( 4000 ) ) );
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 4, 36, true, 12, 8, 0 );
        final PageManager pm = mock( PageManager.class );

        final ContextBriefing b = new DefaultBriefingAssemblyService(
            q -> bundle, idx, refs( Map.of() ), pm, limits )
            .assemble( new BriefingRequest( null, List.of( "billing" ), "q", 10000, ScopeMode.PREFER ) );

        assertEquals( List.of( "Rogue" ), slugsOf( b ), "pointersMax of 0 does not touch elsewhere" );
        assertEquals( TokenEstimator.estimate( memberText ), b.usedTokens(),
            "the out-of-scope section's text is never charged" );
        assertFalse( b.warnings().stream().anyMatch( w -> w.startsWith( "pointer list capped" ) ), b.warnings().toString() );
    }

    @Test
    void elsewhereSkipsAPageThatIsAlreadyAnItem() {
        // Phase7Plan is out of scope for "roller" but is pinned: it already appears in full.
        final ContextBundle bundle = bundleOf( sec( "Phase7Plan", "HP", "out" ), sec( "CommerceDataFlow", "HC", "out" ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( new BriefingRequest( List.of( "Phase7Plan" ), List.of( "roller" ), "q", 10000, ScopeMode.PREFER ) );

        assertEquals( 1, b.items().stream().filter( i -> "Phase7Plan".equals( i.slug() ) ).count(),
            "one item for the pinned page, no elsewhere duplicate: " + slugsOf( b ) );
        assertEquals( "pin", itemBySlug( b, "Phase7Plan" ).origin() );
        assertEquals( "elsewhere", itemBySlug( b, "CommerceDataFlow" ).origin() );
    }

    // ------------------------------------------------ candidate pool, per-page cap

    @Test
    void candidatePoolRequestedFromBundle() {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final ContextBundle bundle = bundleOf( sec( "RollerMcp", "H1", "text" ) );
        when( svc.assemble( eq( "roller" ), any( BundleOptions.class ) ) ).thenReturn( bundle );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), svc )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        verify( svc ).assemble( eq( "roller" ), argThat( ( final BundleOptions o ) ->
            o.maxSections() != null && o.maxSections() == 36 && o.mode() == null ) );
        verify( svc, never() ).assemble( "roller" );
        assertEquals( 1, b.sections().size() );
    }

    @Test
    void perPageCapKeepsFirstNSectionsOfAPageInRankOrder() {
        final List< BundleSection > pool = new ArrayList<>();
        for ( int i = 0; i < 6; i++ ) pool.add( sec( "RollerMcp", "M" + i, "member " + i ) );
        pool.add( sec( "RollerHub", "H0", "hub 0" ) );
        pool.add( sec( "RollerHub", "H1", "hub 1" ) );
        final ContextBundle bundle = new ContextBundle( "q", pool,
            new BundleCoverage( 8, 2, 0.8, BundleCoverage.STRONG ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( rollerRequest( ScopeMode.PREFER ) );

        assertEquals( List.of( "RollerMcp/M0", "RollerMcp/M1", "RollerMcp/M2", "RollerMcp/M3",
                               "RollerHub/H0", "RollerHub/H1" ), headings( b ),
            "a page keeps its first four sections by rank; the displaced two are dropped, and the next page follows" );
        assertEquals( 6, b.coverage().sectionCount() );
    }

    @Test
    void perPageCapIsAppliedBeforeTheTokenBudget() {
        // Each section is ~100 tokens; the 350-token budget admits three. Cap-then-budget keeps
        // M0, M1 and H0 (all fit). Budget-then-cap would spend the budget on M0, M1, M2, stop, and
        // never reach RollerHub.
        final String text = "y".repeat( 400 );
        final List< BundleSection > pool = new ArrayList<>();
        for ( int i = 0; i < 6; i++ ) pool.add( sec( "RollerMcp", "M" + i, text ) );
        pool.add( sec( "RollerHub", "H0", text ) );
        final ContextBundle bundle = new ContextBundle( "q", pool,
            new BundleCoverage( 7, 2, 0.8, BundleCoverage.STRONG ) );
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 2, 36, true, 12, 8, 30 );

        final ContextBriefing b = corpusService( corpusPageManager(), limits, q -> bundle )
            .assemble( new BriefingRequest( null, List.of( "roller" ), "q", 350, ScopeMode.PREFER ) );

        assertEquals( List.of( "RollerMcp/M0", "RollerMcp/M1", "RollerHub/H0" ), headings( b ),
            "cap 2 per page first: M0, M1, H0 survive and all three fit; M2..M5 never compete for budget" );
    }

    @Test
    void noClustersAppliesCapOnly() {
        final List< BundleSection > pool = new ArrayList<>();
        for ( int i = 0; i < 12; i++ ) pool.add( sec( "PageA", "H" + i, "text " + i ) );
        final ContextBundle bundle = new ContextBundle( "q", pool,
            new BundleCoverage( 12, 1, 0.8, BundleCoverage.STRONG ) );

        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults(), q -> bundle )
            .assemble( new BriefingRequest( null, null, "q", 10000, ScopeMode.PREFER ) );

        assertEquals( List.of( "PageA/H0", "PageA/H1", "PageA/H2", "PageA/H3" ), headings( b ) );
        assertTrue( b.items().isEmpty(), "no scope, so nothing is out of scope: " + slugsOf( b ) );
    }

    /** A {@link PageManager} that knows a small body for every page of the eight-page corpus. */
    private static PageManager corpusPageManager() {
        final PageManager pm = pageManagerFixture();
        for ( final String slug : corpusEdges().keySet() ) {
            stub( pm, slug, page( slug, slug + " summary.", 40 ) );
        }
        return pm;
    }

    private static DefaultBriefingAssemblyService corpusService( final PageManager pm,
                                                                 final BriefingLimits limits ) {
        return corpusService( pm, limits, q -> new ContextBundle( q, List.of() ) );
    }

    private static DefaultBriefingAssemblyService corpusService( final PageManager pm, final BriefingLimits limits,
                                                                 final BundleAssemblyService bundles ) {
        return new DefaultBriefingAssemblyService( bundles, corpusIndex(), refs( corpusEdges() ), pm, limits );
    }

    private static List< String > slugsOf( final ContextBriefing b ) {
        return b.items().stream().map( BriefingItem::slug ).toList();
    }

    @Test
    void clusterMembersHubFirstThenByInDegree() {
        final ContextBriefing b = corpusService( corpusPageManager(), BriefingLimits.defaults() ).assemble(
            new BriefingRequest( null, List.of( "roller" ), null, null, null ) );

        // RollerTelemetrySpec is the newest Roller page, so recency alone would list it before
        // RollerMcp; in-degree (RollerMcp is linked by two members, the spec by one) puts it second.
        assertEquals( List.of( "RollerHub", "RollerMcp", "RollerTelemetrySpec", "TelemetryContract", "StackHub" ),
            slugsOf( b ), "members hub-first by in-degree, then the pages they cite" );
        assertEquals( List.of( "cluster", "cluster", "cluster", "cited", "cited" ),
            b.items().stream().map( BriefingItem::origin ).toList() );
        assertTrue( b.items().stream().allMatch( BriefingItem::included ), "everything fits the default budget" );
        assertEquals( List.of( "simple-agility", "simple-agility" ),
            b.items().stream().filter( i -> "cited".equals( i.origin() ) ).map( BriefingItem::cluster ).toList(),
            "a cited item names the cluster it lives in" );
        assertTrue( b.warnings().isEmpty(), "a clean scope warns about nothing: " + b.warnings() );
    }

    @Test
    void citedPageDegradesToPointerUnderBudget() {
        final PageManager pm = corpusPageManager();
        // ~500 tokens: can never fit the 200-token floor budget, whereas the small members and StackHub would.
        stub( pm, "TelemetryContract", page( "Telemetry Contract", "The contract.", 2000 ) );

        final ContextBriefing b = corpusService( pm, BriefingLimits.defaults() ).assemble(
            new BriefingRequest( null, List.of( "roller" ), null, 10, null ) );

        final BriefingItem contract = itemBySlug( b, "TelemetryContract" );
        assertEquals( "cited", contract.origin() );
        assertFalse( contract.included(), "a cited page that does not fit degrades to a pointer" );
        assertNull( contract.content() );
        assertEquals( "simple-agility", contract.cluster() );
        assertEquals( "Telemetry Contract", contract.title() );
        final BriefingItem stackHub = itemBySlug( b, "StackHub" );
        assertEquals( "cited", stackHub.origin() );
        assertFalse( stackHub.included(),
            "admission is rank-ordered: the lower-ranked cited page is a pointer however small it is: " + b.items() );
    }

    /** One page's worth of body that can never fit the 200-token floor budget (~500 tokens). */
    private static String bigPage( final String slug ) {
        return page( slug, slug + " summary.", 2000 );
    }

    @Test
    void lowerRankedMemberIsNotIncludedOnceAHigherRankedOneDegraded() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        final Instant base = Instant.parse( "2026-10-01T00:00:00Z" );
        final PageDescriptor hub = desc( "BillingHub", List.of( "billing" ), "Hub.", base );
        final PageDescriptor tiny = desc( "TinyNote", List.of( "billing" ), "Tiny.", base );
        idx.clusters.put( "billing", cluster( "billing", hub, List.of( tiny ) ) );
        stub( pm, "BillingHub", bigPage( "BillingHub" ) );                // does not fit: degrades
        stub( pm, "TinyNote", page( "Tiny Note", "Tiny.", 10 ) );          // would fit on its own
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, refs( Map.of() ), pm, BriefingLimits.defaults() );

        final ContextBriefing b = svc.assemble( new BriefingRequest( null, List.of( "billing" ), null, 200, null ) );

        assertEquals( List.of( "BillingHub", "TinyNote" ), slugsOf( b ), "both are still listed, in rank order" );
        assertTrue( b.items().stream().noneMatch( BriefingItem::included ),
            "standing context is a rank-ordered prefix, not whatever happens to be small: " + b.items() );
        assertTrue( b.items().stream().allMatch( i -> "cluster".equals( i.origin() ) ) );
        verify( pm, never() ).getPureText( "TinyNote", PageProvider.LATEST_VERSION );
    }

    @Test
    void citedAdmissionIsRankOrderedIndependentlyOfTier1() {
        final PageManager pm = corpusPageManager();
        stub( pm, "TelemetryContract", bigPage( "TelemetryContract" ) );   // first cited page: too large

        final ContextBriefing b = corpusService( pm, BriefingLimits.defaults() ).assemble(
            new BriefingRequest( null, List.of( "roller" ), null, 200, null ) );

        assertEquals( List.of( "RollerHub", "RollerMcp", "RollerTelemetrySpec" ),
            b.items().stream().filter( i -> "cluster".equals( i.origin() ) ).map( BriefingItem::slug ).toList() );
        assertTrue( b.items().stream().filter( i -> "cluster".equals( i.origin() ) ).allMatch( BriefingItem::included ),
            "tier 1 fits whole, so the cited tier starts with a clean slate: " + b.items() );
        assertEquals( List.of( "TelemetryContract", "StackHub" ),
            b.items().stream().filter( i -> "cited".equals( i.origin() ) ).map( BriefingItem::slug ).toList() );
        assertTrue( b.items().stream().filter( i -> "cited".equals( i.origin() ) ).noneMatch( BriefingItem::included ),
            "the tiny second cited page follows the degraded first one as a pointer: " + b.items() );
        verify( pm, never() ).getPureText( "StackHub", PageProvider.LATEST_VERSION );
    }

    @Test
    void aDegradedTierOneDoesNotDegradeTheCitedTier() {
        final PageManager pm = corpusPageManager();
        stub( pm, "RollerHub", bigPage( "RollerHub" ) );                   // tier 1 degrades at its first member

        final ContextBriefing b = corpusService( pm, BriefingLimits.defaults() ).assemble(
            new BriefingRequest( null, List.of( "roller" ), null, 200, null ) );

        assertTrue( b.items().stream().filter( i -> "cluster".equals( i.origin() ) ).noneMatch( BriefingItem::included ),
            "every member after the degraded hub is a pointer: " + b.items() );
        assertTrue( b.items().stream().filter( i -> "cited".equals( i.origin() ) ).allMatch( BriefingItem::included ),
            "tier 2 is judged on its own, and the small contract pages still fit: " + b.items() );
    }

    @Test
    void pinsAreNotAffectedByTierDegradation() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        final PageDescriptor hub = desc( "BillingHub", List.of( "billing" ), "Hub.", Instant.now() );
        idx.clusters.put( "billing", cluster( "billing", hub, List.of() ) );
        stub( pm, "BillingHub", bigPage( "BillingHub" ) );                  // degrades tier 1
        stub( pm, "PinBig", bigPage( "PinBig" ) );                          // degrades a pin
        stub( pm, "PinSmall", page( "Pin Small", "Small.", 10 ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, refs( Map.of() ), pm, BriefingLimits.defaults() );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "PinBig", "PinSmall" ), List.of( "billing" ), null, 200, null ) );

        assertFalse( itemBySlug( b, "PinBig" ).included(), "a pin that does not fit is a pointer" );
        assertTrue( itemBySlug( b, "PinSmall" ).included(),
            "a pin after a degraded page is judged on its own size, as before: " + b.items() );
        assertFalse( itemBySlug( b, "BillingHub" ).included() );
    }

    @Test
    void pointerListCappedWithWarning() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 12; i++ ) {
            final String slug = String.format( "Big%02d", i );
            articles.add( desc( slug, Instant.now() ) );
            stub( pm, slug, page( slug, slug + " summary.", 2000 ) );   // ~500 tokens: never fits
        }
        idx.clusters.put( "billing", cluster( "billing", null, articles ) );
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 4, 36, true, 12, 8, 5 );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, refs( Map.of() ), pm, limits );

        final ContextBriefing b = svc.assemble( new BriefingRequest( null, List.of( "billing" ), null, 200, null ) );

        assertEquals( 5, b.items().stream().filter( i -> !i.included() ).count(), "exactly pointersMax pointers" );
        assertEquals( 5, b.items().size(), "members past the cap are dropped, not emitted as full items" );
        assertEquals( 1, b.warnings().stream().filter( w -> w.equals( "pointer list capped at 5" ) ).count(),
            "one cap warning, however many pointers were refused: " + b.warnings() );
    }

    @Test
    void pinPointersAreNotCountedAgainstThePointerCap() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        stub( pm, "PinA", page( "Pin A", "A.", 2000 ) );
        stub( pm, "PinB", page( "Pin B", "B.", 2000 ) );
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 3; i++ ) {
            final String slug = String.format( "Big%02d", i );
            articles.add( desc( slug, Instant.now() ) );
            stub( pm, slug, page( slug, slug + " summary.", 2000 ) );
        }
        idx.clusters.put( "billing", cluster( "billing", null, articles ) );
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 4, 36, true, 12, 8, 1 );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, refs( Map.of() ), pm, limits );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "PinA", "PinB" ), List.of( "billing" ), null, 200, null ) );

        assertEquals( List.of( "pin", "pin", "cluster" ),
            b.items().stream().map( BriefingItem::origin ).toList(),
            "both pin pointers survive; only one cluster pointer fits the cap of 1" );
        assertTrue( b.warnings().contains( "pointer list capped at 1" ), b.warnings().toString() );
    }

    @Test
    void citedPointersAreNotStarvedByTheClusterPointerCap() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        final Instant base = Instant.parse( "2026-10-01T00:00:00Z" );
        final PageDescriptor hub = desc( "BillingHub", List.of( "billing" ), "Hub.", base );
        idx.register( hub );
        stub( pm, "BillingHub", page( "Billing Hub", "Hub.", 40 ) );   // the one page that fits
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 9; i++ ) {
            final String slug = String.format( "Member%02d", i );
            final PageDescriptor article = desc( slug, List.of( "billing" ), slug + " summary.", base.plusSeconds( i ) );
            idx.register( article );
            articles.add( article );
            stub( pm, slug, page( slug, slug + " summary.", 2000 ) );   // ~500 tokens: never fits
        }
        idx.clusters.put( "billing", cluster( "billing", hub, articles ) );
        for ( final String slug : List.of( "ContractOne", "ContractTwo" ) ) {
            idx.register( desc( slug, List.of( "shared" ), slug + " summary.", base ) );
            stub( pm, slug, page( slug, slug + " summary.", 2000 ) );
        }
        // The hub cites both contracts; no member links to them otherwise.
        final BriefingLimits limits = new BriefingLimits( 6000, 24000, 4, 36, true, 12, 8, 5 );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx,
            refs( Map.of( "BillingHub", List.of( "ContractOne", "ContractTwo" ) ) ), pm, limits );

        final ContextBriefing b = svc.assemble( new BriefingRequest( null, List.of( "billing" ), null, 200, null ) );

        final List< BriefingItem > clusterItems = b.items().stream().filter( i -> "cluster".equals( i.origin() ) ).toList();
        final List< BriefingItem > citedItems = b.items().stream().filter( i -> "cited".equals( i.origin() ) ).toList();
        assertEquals( 5, clusterItems.stream().filter( i -> !i.included() ).count(),
            "exactly pointersMax cluster pointers: " + slugsOf( b ) );
        assertEquals( List.of( "BillingHub" ), clusterItems.stream().filter( BriefingItem::included )
            .map( BriefingItem::slug ).toList(), "only the hub fits, and a full item is not a pointer" );
        assertEquals( List.of( "ContractOne", "ContractTwo" ), citedItems.stream().map( BriefingItem::slug ).toList(),
            "the cluster pointer cap must not starve the cited tier: " + slugsOf( b ) );
        assertTrue( citedItems.stream().noneMatch( BriefingItem::included ), "the contracts do not fit, so they are pointers" );
        assertTrue( citedItems.stream().allMatch( i -> "shared".equals( i.cluster() ) ) );
        assertEquals( 1, b.warnings().stream().filter( w -> w.equals( "pointer list capped at 5" ) ).count(),
            "one cap warning, from the cluster tier: " + b.warnings() );
        final int lastCluster = slugsOf( b ).lastIndexOf( clusterItems.get( clusterItems.size() - 1 ).slug() );
        assertTrue( slugsOf( b ).indexOf( "ContractOne" ) > lastCluster, "cited items follow every cluster item: " + slugsOf( b ) );
    }

    @Test
    void legacyConstructorStillWorksWithoutReferenceManager() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), corpusIndex(), corpusPageManager(), DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing scoped = svc.assemble(
            new BriefingRequest( null, List.of( "roller" ), null, null, null ) );

        assertEquals( 3, scoped.items().size(), "the members are still assembled" );
        assertTrue( scoped.items().stream().noneMatch( i -> "cited".equals( i.origin() ) ),
            "no reference data, no cited tier" );
        assertTrue( scoped.warnings().contains( "reference data unavailable; cited pages skipped" ),
            scoped.warnings().toString() );
        assertEquals( DEFAULT_BUDGET, scoped.budgetTokens(), "legacy budgets carry through" );

        final ContextBriefing unscoped = svc.assemble(
            new BriefingRequest( List.of( "BillingProcess" ), null, null, null, null ) );
        assertTrue( unscoped.warnings().isEmpty(),
            "the missing-reference-data warning belongs to cluster-scoped requests only: " + unscoped.warnings() );
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
    void pinsCappedWithWarning() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );
        final List< String > pins = new ArrayList<>();
        for ( int i = 0; i < 26; i++ ) pins.add( "Pin" + i );

        final ContextBriefing b = svc.assemble( new BriefingRequest( pins, null, null, null, null ) );

        assertTrue( b.warnings().stream().anyMatch( w -> w.equals( "too many pins; first 25 included" ) ),
            "26 pins → cap warning: " + b.warnings() );
    }

    @Test
    void clustersCappedWithWarning() {
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), new StubIndex(), pageManagerFixture(),
            DEFAULT_BUDGET, MAX_BUDGET );
        final List< String > clusters = new ArrayList<>();
        for ( int i = 0; i < 11; i++ ) clusters.add( "cluster" + i );

        final ContextBriefing b = svc.assemble( new BriefingRequest( null, clusters, null, null, null ) );

        assertTrue( b.warnings().stream().anyMatch( w -> w.equals( "too many clusters; first 10 included" ) ),
            "11 clusters → cap warning: " + b.warnings() );
    }

    @Test
    void clusterMembersCappedWithWarning() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        final List< PageDescriptor > articles = new ArrayList<>();
        for ( int i = 0; i < 201; i++ ) {
            final String slug = String.format( "Mem%03d", i );
            articles.add( desc( slug, Instant.now() ) );
            when( pm.getPage( slug ) ).thenReturn( mock( Page.class ) );
        }
        idx.clusters.put( "billing", cluster( "billing", null, articles ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( null, List.of( "billing" ), null, null, null ) );

        assertEquals( 200, b.items().size(), "cluster member list capped at 200" );
        assertTrue( b.warnings().stream().anyMatch(
                w -> w.equals( "too many members in cluster billing; first 200 included" ) ),
            "201 members → cap warning: " + b.warnings() );
    }

    @Test
    void pointerFastPathSkipsMemberBodyReadsWhenBudgetExhausted() {
        final StubIndex idx = new StubIndex();
        final PageManager pm = pageManagerFixture();
        // A pin whose body (~186 tokens) all but fills the 200-token floor budget.
        stub( pm, "FillPage", page( "Fill", "Fill summary.", 700 ) );
        // Cluster members exist (getPage non-null) but their bodies must NOT be read.
        idx.clusters.put( "billing", cluster( "billing", desc( "MemberHub", Instant.now() ),
            List.of( desc( "MemberA", Instant.now() ) ) ) );
        when( pm.getPage( "MemberHub" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPage( "MemberA" ) ).thenReturn( mock( Page.class ) );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), idx, pm, DEFAULT_BUDGET, MAX_BUDGET );

        final ContextBriefing b = svc.assemble(
            new BriefingRequest( List.of( "FillPage" ), List.of( "billing" ), null, 10, null ) );

        // Members surfaced as pointers straight from the descriptor, no body read.
        verify( pm, never() ).getPureText( "MemberHub", PageProvider.LATEST_VERSION );
        verify( pm, never() ).getPureText( "MemberA", PageProvider.LATEST_VERSION );
        final BriefingItem hub = itemBySlug( b, "MemberHub" );
        final BriefingItem a = itemBySlug( b, "MemberA" );
        assertTrue( hub != null && !hub.included(), "hub surfaced as pointer" );
        assertTrue( a != null && !a.included(), "article surfaced as pointer" );
        assertNull( hub.content(), "pointer carries no body" );
        assertEquals( "MemberHub", hub.title(), "pointer title from descriptor" );
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
