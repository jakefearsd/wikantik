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

import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HybridChunkSectionSourceTest {

    private static final UUID A1 = UUID.fromString( "00000000-0000-0000-0000-0000000000a1" );
    private static final UUID A2 = UUID.fromString( "00000000-0000-0000-0000-0000000000a2" );
    private static final UUID B1 = UUID.fromString( "00000000-0000-0000-0000-0000000000b1" );

    private static MentionableChunk chunk( final UUID id, final String page, final List< String > hp, final String text ) {
        return new MentionableChunk( id, page, 0, hp, text );
    }

    @Test
    void groupsToSectionsKeepingBestFusedChunkPerSection() {
        // Fused order A1, A2, B1. A1 and A2 are the same section (PageA > Intro) → A2 deduped.
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        byId.put( A1, chunk( A1, "PageA", List.of( "Intro" ), "first chunk of intro" ) );
        byId.put( A2, chunk( A2, "PageA", List.of( "Intro" ), "second chunk of intro" ) );
        byId.put( B1, chunk( B1, "PageB", List.of( "Details" ), "details chunk" ) );

        final List< CandidateSection > out = HybridChunkSectionSource.groupToSections(
            List.of( A1.toString(), A2.toString(), B1.toString() ), byId );

        assertEquals( 2, out.size() );
        assertEquals( "PageA", out.get( 0 ).slug() );
        assertEquals( List.of( "Intro" ), out.get( 0 ).headingPath() );
        assertEquals( "first chunk of intro", out.get( 0 ).text() );   // best-fused chunk wins, not A2
        assertEquals( "PageB", out.get( 1 ).slug() );
        // Section score decreases with fused position so the bundle preserves fused order.
        assertTrue( out.get( 0 ).denseScore() > out.get( 1 ).denseScore() );
    }

    @Test
    void skipsFusedIdsMissingFromHydration() {
        final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();
        byId.put( B1, chunk( B1, "PageB", List.of( "Details" ), "details" ) );
        // A1 is in the fused order but absent from byId (e.g. dropped during hydration) → skipped.
        final List< CandidateSection > out = HybridChunkSectionSource.groupToSections(
            List.of( A1.toString(), B1.toString() ), byId );
        assertEquals( 1, out.size() );
        assertEquals( "PageB", out.get( 0 ).slug() );
    }
}
