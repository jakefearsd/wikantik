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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.bundle.RetrievalMode;
import com.wikantik.api.managers.PageManager;
import com.wikantik.livevalues.StubLiveValues;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Bundle (and so briefing) sections are stored with the raw token and served expanded, anchors included. */
class BundleLiveValuesTest {

    private static final String TOKEN = StubLiveValues.TOKEN;
    private static final String VALUE = StubLiveValues.VALUE;

    private final StubLiveValues stub = new StubLiveValues();

    private DefaultBundleAssemblyService service( final java.util.function.Supplier< com.wikantik.api.livevalues.LiveValues > lv ) {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( "Sizes", -1 ) ).thenReturn( "---\ntype: article\n---\n## Over " + TOKEN + " articles\n\nWe hold " + TOKEN + " articles.\n" );
        final SectionCandidateSource source = q -> SectionCandidates.of( List.of(
                new CandidateSection( "Sizes", List.of( "Over " + TOKEN + " articles" ), "We hold " + TOKEN + " articles.", 0.9 ) ), -1.0 );
        return new DefaultBundleAssemblyService(
                new RetrievalRouting( Map.of( RetrievalMode.HYBRID, source ), RetrievalMode.HYBRID ), ( q, s ) -> s,
                new CitationResolvers( slug -> Optional.of( "01SIZES" ), slug -> 3,
                        BundleServiceWiring.sectionLinks( pm, new Properties(), lv ) ),
                5, BundleCoverageCalculator.defaults(), KneeCutoff.disabled(), lv );
    }

    @Test
    void sectionTextHeadingsCitationAndAnchorAreExpanded() {
        final BundleSection s = service( () -> stub ).assemble( "size" ).sections().get( 0 );

        assertEquals( "We hold " + VALUE + " articles.", s.text() );
        assertEquals( List.of( "Over " + VALUE + " articles" ), s.headingPath() );
        assertEquals( s.text(), s.citation().span() );
        assertEquals( List.of( "Over " + VALUE + " articles" ), s.citation().headingPath() );
        final String anchor = com.wikantik.knowledge.chunking.SectionAnchors
                .byHeadingPath( "## Over " + VALUE + " articles\n\nText.\n" ).get( s.headingPath() );
        assertEquals( "/wiki/Sizes#" + anchor, s.url(), "exact anchor of the expanded heading" );
        assertFalse( s.url().contains( "{" ) );
    }

    @Test
    void citationHashStaysOverTheRawSpanSoAValueChangeDoesNotStaleIt() {
        final String before = service( () -> stub ).assemble( "size" ).sections().get( 0 ).citation().spanSha256();
        stub.value( "1,200+" );
        final BundleSection after = service( () -> stub ).assemble( "size" ).sections().get( 0 );

        assertEquals( before, after.citation().spanSha256() );
        assertTrue( after.text().contains( "1,200+" ), "the served text follows the value" );
    }

    @Test
    void withoutTheServiceSectionsAreServedVerbatim() {
        final BundleSection s = service( () -> null ).assemble( "size" ).sections().get( 0 );

        assertTrue( s.text().contains( TOKEN ) );
        assertTrue( s.headingPath().get( 0 ).contains( TOKEN ) );
    }
}
