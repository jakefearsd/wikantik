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
package com.wikantik.collab.testing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShrinkerTest {

    private static Set< Integer > ids( final SimTrace t ) {
        return t.steps().stream().map( SimTrace.Step::id ).collect( Collectors.toSet() );
    }

    @Test
    void dropsStepsThatDoNotMatter() {
        final SimTrace t = new SimScheduler( 3, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 80 );
        assertEquals( 80, t.steps().size() );
        final SimTrace shrunk = Shrinker.shrink( t, c -> ids( c ).containsAll( Set.of( 5, 17, 63 ) ) );
        assertEquals( List.of( 5, 17, 63 ), shrunk.steps().stream().map( SimTrace.Step::id ).toList() );
        assertEquals( t.seed(), shrunk.seed() );
        assertEquals( t.profile(), shrunk.profile() );
        assertEquals( t.initialDoc(), shrunk.initialDoc() );
    }

    @Test
    void keepsEveryStepWhenEachOneMatters() {
        final SimTrace t = new SimScheduler( 4, SimProfile.CALM, SimScheduler.STUB, 2 ).run( 12 );
        final AtomicInteger calls = new AtomicInteger();
        final SimTrace shrunk = Shrinker.shrink( t, c -> {
            calls.incrementAndGet();
            return c.steps().size() == 12;
        } );
        assertEquals( t.steps(), shrunk.steps() );
        assertEquals( true, calls.get() >= 12, "every single-step removal must be tried" );
    }
}
