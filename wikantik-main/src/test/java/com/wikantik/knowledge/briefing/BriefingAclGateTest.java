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
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefingAclGateTest {

    private static BundleSection sec( final String slug, final String text ) {
        return new BundleSection( "01" + slug, slug, List.of( "H" ), text, 0.9,
            new CitationHandle( "01" + slug, 1, List.of( "H" ), text, "sha" ) );
    }

    private static BriefingItem item( final String slug, final String canonicalId, final boolean included ) {
        return new BriefingItem( slug, canonicalId, slug, "", "pin", included, included ? "body" : null );
    }

    private static ContextBriefing briefing( final List< BundleSection > sections,
                                             final List< BriefingItem > items,
                                             final List< String > warnings ) {
        return new ContextBriefing( "prompt", sections,
            new BundleCoverage( sections.size(), sections.size(), 0.9, BundleCoverage.STRONG ),
            items, warnings, 4000, 100 );
    }

    @Test
    void dropsRestrictedSectionsAndItemsAndRecountsCoverage() {
        final ContextBriefing original = briefing(
            List.of( sec( "PublicPage", "public" ), sec( "SecretPage", "TOP SECRET" ) ),
            List.of( item( "PublicPage", "01PublicPage", true ), item( "SecretPage", "01SecretPage", true ) ),
            List.of() );
        final Predicate< String > canView = slug -> !"SecretPage".equals( slug );

        final ContextBriefing gated = BriefingAclGate.gate( original, List.of(), canView );

        assertEquals( 1, gated.sections().size(), "restricted section dropped" );
        assertEquals( "PublicPage", gated.sections().get( 0 ).slug() );
        assertEquals( 1, gated.items().size(), "restricted item dropped" );
        assertEquals( 1, gated.coverage().sectionCount(), "coverage recounted over kept sections" );
        // STRONG thinned below the 3-section floor by the gate steps down to partial.
        assertEquals( BundleCoverage.PARTIAL, gated.coverage().confidence() );
        assertEquals( "prompt", gated.prompt() );
        assertEquals( 4000, gated.budgetTokens() );
        // usedTokens recomputed from kept content only: section "public" (6 chars→2) + item "body" (1) = 3.
        assertEquals( 3, gated.usedTokens(), "usedTokens recomputed over kept content, not carried over" );
    }

    @Test
    void droppedPinBySlugGetsUnknownPinWarning() {
        final ContextBriefing original = briefing( List.of(),
            List.of( item( "SecretPage", "01SecretPage", true ) ), List.of() );
        final Predicate< String > canView = slug -> false;

        // The caller pinned "SecretPage" by slug; it existed but was ACL-dropped.
        final ContextBriefing gated = BriefingAclGate.gate( original, List.of( "SecretPage" ), canView );

        assertTrue( gated.items().isEmpty() );
        assertTrue( gated.warnings().contains( "unknown pin: SecretPage" ),
            "dropped-restricted pin must be indistinguishable from a nonexistent one: " + gated.warnings() );
    }

    @Test
    void droppedPinByCanonicalIdGetsUnknownPinWarning() {
        final ContextBriefing original = briefing( List.of(),
            List.of( item( "SecretPage", "01SECRET", true ) ), List.of() );
        final Predicate< String > canView = slug -> false;

        // The caller pinned by canonical id "01SECRET"; the matching item was ACL-dropped.
        final ContextBriefing gated = BriefingAclGate.gate( original, List.of( "01SECRET" ), canView );

        assertTrue( gated.warnings().contains( "unknown pin: 01SECRET" ),
            "canonical-id pin match must warn with the caller's input: " + gated.warnings() );
    }

    @Test
    void restrictedExistingAndNonexistentPinAreByteIdentical() {
        // Restricted-existing: item exists (charged tokens by the assembler) but caller can't view it.
        final ContextBriefing restricted = briefing( List.of(),
            List.of( item( "X", "01X", true ) ), List.of() );
        final ContextBriefing gatedRestricted =
            BriefingAclGate.gate( restricted, List.of( "X" ), slug -> false );

        // Nonexistent: no item at all.
        final ContextBriefing nonexistent = briefing( List.of(), List.of(), List.of() );
        final ContextBriefing gatedNonexistent =
            BriefingAclGate.gate( nonexistent, List.of( "X" ), slug -> true );

        assertEquals( gatedNonexistent.warnings(), gatedRestricted.warnings(),
            "restricted and nonexistent pins must yield identical warnings" );
        assertEquals( List.of( "unknown pin: X" ), gatedRestricted.warnings() );
        assertEquals( gatedNonexistent.usedTokens(), gatedRestricted.usedTokens(),
            "restricted pin must not leak its body size via usedTokens" );
        assertEquals( 0, gatedRestricted.usedTokens() );
    }

    @Test
    void duplicatePinWarnsExactlyOnce_restrictedAndNonexistentAlike() {
        final ContextBriefing restricted = briefing( List.of(),
            List.of( item( "X", "01X", true ) ), List.of() );
        final ContextBriefing gatedRestricted =
            BriefingAclGate.gate( restricted, List.of( "X", "X" ), slug -> false );
        assertEquals( 1, countUnknownPin( gatedRestricted, "X" ), "restricted duplicate warns once" );

        final ContextBriefing nonexistent = briefing( List.of(), List.of(), List.of() );
        final ContextBriefing gatedNonexistent =
            BriefingAclGate.gate( nonexistent, List.of( "X", "X" ), slug -> true );
        assertEquals( 1, countUnknownPin( gatedNonexistent, "X" ), "nonexistent duplicate warns once" );
    }

    @Test
    void warningsFollowRequestOrder_notRestrictedStatus() {
        // Restricted item present but not viewable; the other pin is nonexistent.
        final ContextBriefing a = briefing( List.of(),
            List.of( item( "Restricted", "01R", true ) ), List.of() );
        final ContextBriefing gatedA = BriefingAclGate.gate( a,
            List.of( "Restricted", "Nonexistent" ), slug -> false );
        assertEquals( List.of( "unknown pin: Restricted", "unknown pin: Nonexistent" ),
            gatedA.warnings() );

        final ContextBriefing b = briefing( List.of(),
            List.of( item( "Restricted", "01R", true ) ), List.of() );
        final ContextBriefing gatedB = BriefingAclGate.gate( b,
            List.of( "Nonexistent2", "Restricted" ), slug -> false );
        assertEquals( List.of( "unknown pin: Nonexistent2", "unknown pin: Restricted" ),
            gatedB.warnings() );
    }

    @Test
    void usedTokensIsSumOverKeptContent() {
        final BundleSection s1 = sec( "S1", "12345678" );   // 8 chars → 2 tokens
        final BundleSection s2 = sec( "S2", "1234" );       // 4 chars → 1 token
        final BriefingItem included = new BriefingItem( "Inc", "01Inc", "Inc", "", "pin", true,
            "123456789012" );                               // 12 chars → 3 tokens
        final BriefingItem pointer = new BriefingItem( "Ptr", "01Ptr", "Ptr", "", "cluster", false, null );
        final ContextBriefing original = briefing( List.of( s1, s2 ),
            List.of( included, pointer ), List.of() );

        final ContextBriefing gated = BriefingAclGate.gate( original, List.of(), slug -> true );

        assertEquals( 6, gated.usedTokens(), "2 + 1 sections + 3 included body + 0 pointer" );
    }

    private static int countUnknownPin( final ContextBriefing b, final String pin ) {
        return (int) b.warnings().stream().filter( w -> w.equals( "unknown pin: " + pin ) ).count();
    }

    @Test
    void untouchedBriefingPassesThroughUnchanged() {
        final ContextBriefing original = briefing(
            List.of( sec( "A", "a" ), sec( "B", "b" ), sec( "C", "c" ) ),
            List.of( item( "A", "01A", true ) ),
            List.of( "pre-existing warning" ) );

        final ContextBriefing gated = BriefingAclGate.gate( original, List.of( "A" ), slug -> true );

        assertEquals( 3, gated.sections().size() );
        assertEquals( 1, gated.items().size() );
        assertEquals( List.of( "pre-existing warning" ), gated.warnings(),
            "no pin dropped → no new warning appended" );
        assertFalse( gated.warnings().contains( "unknown pin: A" ) );
        assertEquals( BundleCoverage.STRONG, gated.coverage().confidence() );
    }
}
