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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.PageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PageTitleIndexTest {

    private static PageDescriptor page( final String slug, final String title ) {
        return new PageDescriptor( "id-" + slug, slug, title, PageType.ARTICLE, null, List.of(), List.of(),
                null, Instant.EPOCH, Optional.empty(), false );
    }

    private final PageTitleIndex index = PageTitleIndex.of(
            List.of( page( "LowCostIndexFundInvesting", "Low-Cost Index Fund Investing" ),
                     page( "IndexFundsHub", "IndexFundsHub" ),
                     page( "BondLadders", "Bond Ladders" ),
                     page( "Kubernetes", "Kubernetes" ) ),
            Map.of( "Kubernetes", List.of( "k8s" ) ) );

    private static final List< String > ALL =
            List.of( "LowCostIndexFundInvesting", "IndexFundsHub", "BondLadders", "Kubernetes" );

    @Test void exactBeatsPrefixBeatsSubstring() {
        final PageTitleIndex idx = PageTitleIndex.of(
                List.of( page( "Index", "Index" ), page( "IndexFundsHub", "IndexFundsHub" ),
                         page( "LowCostIndexFundInvesting", "x" ) ), Map.of() );
        assertEquals( List.of( "Index", "IndexFundsHub", "LowCostIndexFundInvesting" ),
                idx.rank( List.of( "LowCostIndexFundInvesting", "IndexFundsHub", "Index" ), "index" ) );
    }

    @Test void spacesInTheQueryAreIgnored() {
        assertEquals( List.of( "IndexFundsHub", "LowCostIndexFundInvesting" ), index.rank( ALL, "index fund" ) );
    }

    @Test void aliasMatchesFindThePage() {
        assertEquals( List.of( "Kubernetes" ), index.rank( ALL, "k8s" ) );
    }

    @Test void titleWithPunctuationIsMatchable() {
        assertEquals( List.of( "LowCostIndexFundInvesting" ), index.rank( ALL, "low-cost" ) );
    }

    @Test void subsequenceIsTheLastTier() {
        assertEquals( List.of( "LowCostIndexFundInvesting" ), index.rank( ALL, "lcifi" ) );
        final PageTitleIndex idx = PageTitleIndex.of(
                List.of( page( "Ladder", "Ladder" ), page( "LowAdDer", "Low Ad Der" ) ), Map.of() );
        // "ladd" is a prefix of Ladder and only a subsequence of lowadder
        assertEquals( List.of( "Ladder", "LowAdDer" ), idx.rank( List.of( "LowAdDer", "Ladder" ), "ladd" ) );
    }

    @Test void unknownNamesStillMatchOnTheirOwnName() {
        assertEquals( List.of( "NotIndexedYet" ), index.rank( List.of( "NotIndexedYet", "BondLadders" ), "notindexed" ) );
    }

    @Test void blankQueryReturnsAllInNaturalOrder() {
        assertEquals( List.of( "BondLadders", "IndexFundsHub", "Kubernetes", "LowCostIndexFundInvesting" ),
                index.rank( ALL, "  " ) );
    }

    @Test void entriesExposeDeCamelCasedNameTitleAndAliases() {
        final PageTitleLookup.TitleEntry k = index.entries().stream()
                .filter( e -> e.slug().equals( "Kubernetes" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "Kubernetes", "k8s" ), k.phrases() );
        final PageTitleLookup.TitleEntry l = index.entries().stream()
                .filter( e -> e.slug().equals( "LowCostIndexFundInvesting" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "Low Cost Index Fund Investing", "Low-Cost Index Fund Investing" ), l.phrases() );
    }
}
