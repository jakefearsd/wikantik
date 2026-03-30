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
package com.wikantik;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LinkCollector}.
 */
class LinkCollectorTest {

    // -----------------------------------------------------------------------
    // mutate — returns input unchanged and accumulates links
    // -----------------------------------------------------------------------

    @Test
    void mutate_returnsInputUnchanged() {
        final LinkCollector collector = new LinkCollector();
        final String input = "http://example.com";

        final String result = collector.mutate( null, input );

        assertEquals( input, result, "mutate must return the input string unchanged" );
    }

    @Test
    void mutate_addsLinkToList() {
        final LinkCollector collector = new LinkCollector();
        collector.mutate( null, "link1" );

        final List< String > links = collector.getLinks();
        assertEquals( 1, links.size() );
        assertEquals( "link1", links.get( 0 ) );
    }

    @Test
    void multipleMutateCalls_accumulateLinksInOrder() {
        final LinkCollector collector = new LinkCollector();
        collector.mutate( null, "alpha" );
        collector.mutate( null, "beta" );
        collector.mutate( null, "gamma" );

        final List< String > links = collector.getLinks();
        assertEquals( 3, links.size() );
        assertEquals( "alpha", links.get( 0 ) );
        assertEquals( "beta", links.get( 1 ) );
        assertEquals( "gamma", links.get( 2 ) );
    }

    // -----------------------------------------------------------------------
    // getLinks — empty collector
    // -----------------------------------------------------------------------

    @Test
    void getLinks_emptyCollectorReturnsEmptyList() {
        final LinkCollector collector = new LinkCollector();
        assertTrue( collector.getLinks().isEmpty() );
    }

}
