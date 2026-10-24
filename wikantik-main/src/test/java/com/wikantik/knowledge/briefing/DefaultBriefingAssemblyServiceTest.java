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
        return new DefaultBriefingAssemblyService( q -> new ContextBundle( q, List.of() ), corpusIndex(),
            refs( corpusEdges() ), pm, limits );
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
        // ~500 tokens: can never fit the 200-token floor budget, whereas the small members and StackHub do.
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
        assertTrue( stackHub.included(), "the next cited page is judged on its own size: " + b.items() );
        assertEquals( "cited", stackHub.origin() );
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
