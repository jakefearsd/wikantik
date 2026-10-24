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

import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BriefingWarningsTest {

    private static final Set< String > KNOWN = Set.of( "pins", "clusters" );

    private static ContextBriefing briefing( final List< String > warnings ) {
        return new ContextBriefing( null, List.of(), null, List.of(), warnings, 100, 10 );
    }

    @Test
    void noUnknownArgumentsReturnsTheSameBriefing() {
        final ContextBriefing b = briefing( List.of( "w" ) );
        assertSame( b, BriefingWarnings.withUnknownArguments( b, Set.of( "pins" ), KNOWN ) );
    }

    @Test
    void nullSuppliedReturnsTheSameBriefing() {
        final ContextBriefing b = briefing( List.of() );
        assertSame( b, BriefingWarnings.withUnknownArguments( b, null, KNOWN ) );
    }

    @Test
    void unknownNamesAreAppendedSortedAfterExistingWarnings() {
        final ContextBriefing b = briefing( List.of( "existing" ) );

        final ContextBriefing out = BriefingWarnings.withUnknownArguments(
            b, new LinkedHashSet<>( List.of( "zeta", "pins", "alpha" ) ), KNOWN );

        assertEquals( List.of( "existing", "unknown argument: alpha", "unknown argument: zeta" ), out.warnings() );
        assertEquals( b.budgetTokens(), out.budgetTokens() );
        assertEquals( b.usedTokens(), out.usedTokens() );
    }

    @Test
    void retrievalCoverageSurvivesTheWarningPass() {
        final BundleCoverage retrieved = new BundleCoverage( 9, 5, 0.9, BundleCoverage.STRONG );
        final ContextBriefing b = new ContextBriefing( "p", List.of(),
            new BundleCoverage( 0, 0, 0.9, BundleCoverage.WEAK ), List.of(), List.of(), 100, 10, retrieved );

        final ContextBriefing out = BriefingWarnings.withUnknownArguments( b, Set.of( "bogus" ), KNOWN );

        assertSame( retrieved, out.retrievalCoverage(), "adding a warning must not reset retrieval truth to the delivered coverage" );
    }

    @Test
    void echoedNamesAreCappedAndStripOfControlCharacters() {
        final ContextBriefing out = BriefingWarnings.withUnknownArguments(
            briefing( List.of() ), Set.of( "a\nb" + "x".repeat( 200 ) ), KNOWN );

        final String w = out.warnings().get( 0 );
        assertEquals( "unknown argument: a?b" + "x".repeat( BriefingWarnings.MAX_NAME_CHARS - 3 ), w );
    }
}
