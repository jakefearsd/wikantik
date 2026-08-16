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
package com.wikantik.insights;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPageFactsTest {

    @Test
    void lookupReturnsFactsForAKnownPage() {
        final Instant verifiedAt = Instant.parse( "2026-06-01T00:00:00Z" );
        final PageFacts.PageFact fact = new PageFacts.PageFact( "/wiki/A", "A", "summary of A",
                List.of( "tag1" ), "philosophy", verifiedAt, "authoritative" );
        final PageFacts facts = new MapPageFacts( Map.of( "/wiki/A", fact ) );

        final Optional<PageFacts.PageFact> result = facts.lookup( "/wiki/A" );

        assertTrue( result.isPresent() );
        assertEquals( "A", result.get().title() );
        assertEquals( "philosophy", result.get().cluster() );
        assertEquals( verifiedAt, result.get().verifiedAt() );
    }

    @Test
    void lookupIsEmptyForAnUnknownPage() {
        final PageFacts facts = new MapPageFacts( Map.of() );
        assertTrue( facts.lookup( "/wiki/Nope" ).isEmpty() );
    }
}
