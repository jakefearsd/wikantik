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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.PreferredPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HubSummarySynthesizerTest {

    private final HubSummarySynthesizer s = new HubSummarySynthesizer();

    private AgentHintsBlock hintsWith( final String... titles ) {
        return new AgentHintsBlock( List.of(), java.util.Arrays.stream( titles )
                .map( t -> new PreferredPage( t.toLowerCase().replace( ' ', '_' ), t, "cluster_member" ) )
                .toList() );
    }

    @Test
    void doesNotFireWhenNotHub() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Index of pages on warehouse automation",
                                hintsWith( "A", "B", "C" ),
                                false ) );
    }

    @Test
    void doesNotFireWhenSummaryIsRich() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Authoritative overview of warehouse automation, including ROI tradeoffs.",
                                hintsWith( "A", "B", "C" ),
                                true ) );
    }

    @Test
    void doesNotFireWhenNoPreferPages() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( "Index of pages on warehouse automation",
                                AgentHintsBlock.empty(),
                                true ) );
    }

    @Test
    void firesWhenHubAndGenericAndPagesPresent() {
        final Optional< String > out = s.maybeOverlay(
                "Index of pages on warehouse automation",
                hintsWith( "Warehouse Robotics", "Limitations", "ROI Models", "Suppliers" ),
                true );
        assertTrue( out.isPresent() );
        assertTrue( out.get().contains( "Warehouse Robotics" ) );
        assertTrue( out.get().contains( "Limitations" ) );
        assertTrue( out.get().contains( "ROI Models" ) );
        assertFalse( out.get().contains( "Suppliers" ),
                "should cap at top 3, not 4" );
    }

    @Test
    void caseAndWhitespaceTolerantRegex() {
        assertTrue( s.maybeOverlay( "  AN INDEX OF ARTICLES ABOUT X",
                                    hintsWith( "A", "B", "C" ), true ).isPresent() );
        assertTrue( s.maybeOverlay( "Index of content for the cluster",
                                    hintsWith( "A", "B", "C" ), true ).isPresent() );
    }

    @Test
    void nullSummaryDoesNotMatch() {
        assertEquals( Optional.empty(),
                s.maybeOverlay( null, hintsWith( "A", "B", "C" ), true ) );
    }
}
