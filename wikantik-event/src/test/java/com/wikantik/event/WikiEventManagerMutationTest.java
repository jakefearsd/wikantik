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
package com.wikantik.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour of the manager's client matching, preload cache, shutdown and listener ordering. */
class WikiEventManagerMutationTest {

    /** Listener with a chosen hash code and identity equality, to control the ordering comparator's inputs. */
    private static final class HashedListener implements WikiEventListener {
        private final int hash;
        HashedListener( final int hash ) { this.hash = hash; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals( final Object o ) { return this == o; }
        @Override public void actionPerformed( final WikiEvent event ) { }
    }

    private static final class ClientA { }
    private static final class ClientB { }

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        WikiEventManager.shutdown();
    }

    private static WikiEvent event( final Object src ) {
        return new WikiEngineEvent( src, WikiEngineEvent.INITIALIZED );
    }

    // ---- listener ordering (WikiEventListenerComparator) ----

    @Test
    void listenersAreReportedInDescendingHashCodeOrder() {
        final Object client = new Object();
        final HashedListener low = new HashedListener( 5 );
        final HashedListener high = new HashedListener( 10 );
        final HashedListener mid = new HashedListener( 7 );
        for( final HashedListener l : List.of( low, high, mid ) ) {
            WikiEventManager.addWikiEventListener( client, l );
        }
        assertEquals( List.of( high, mid, low ), new ArrayList<>( WikiEventManager.getWikiEventListeners( client ) ) );
    }

    @Test
    void listenersWhoseHashCodesCancelOutAreStillBothReported() {
        final Object client = new Object();
        final HashedListener a = new HashedListener( 1 );
        final HashedListener b = new HashedListener( -1 );
        WikiEventManager.addWikiEventListener( client, a );
        WikiEventManager.addWikiEventListener( client, b );
        assertEquals( Set.of( a, b ), WikiEventManager.getWikiEventListeners( client ) );
    }

    @Test
    void distinctListenersWithTheSameHashCodeAreBothReported() {
        final Object client = new Object();
        final HashedListener a = new HashedListener( 42 );
        final HashedListener b = new HashedListener( 42 );
        WikiEventManager.addWikiEventListener( client, a );
        WikiEventManager.addWikiEventListener( client, b );
        assertEquals( Set.of( a, b ), WikiEventManager.getWikiEventListeners( client ),
                "a hash collision must not make one registered listener vanish from the listing" );
    }

    @Test
    void hashCodesFarApartDoNotOverflowTheComparison() {
        final Object client = new Object();
        final HashedListener max = new HashedListener( Integer.MAX_VALUE );
        final HashedListener min = new HashedListener( Integer.MIN_VALUE );
        final HashedListener zero = new HashedListener( 0 );
        for( final HashedListener l : List.of( min, zero, max ) ) {
            WikiEventManager.addWikiEventListener( client, l );
        }
        assertEquals( List.of( max, zero, min ), new ArrayList<>( WikiEventManager.getWikiEventListeners( client ) ),
                "descending order must hold across the whole int range" );
    }

    // ---- client matching and the preload cache ----

    @Test
    void aListenerPreloadedForAClassGoesToThatClassNotToOthers() {
        final AtomicInteger calls = new AtomicInteger();
        WikiEventManager.addWikiEventListener( ClientA.class, e -> calls.incrementAndGet() );

        final ClientB other = new ClientB();
        WikiEventManager.fireEvent( other, event( other ) );
        assertEquals( 0, calls.get(), "an instance of another class must not inherit the preloaded listener" );
        assertFalse( WikiEventManager.isListening( other ) );

        final ClientA match = new ClientA();
        WikiEventManager.fireEvent( match, event( match ) );
        assertEquals( 1, calls.get() );
    }

    @Test
    void aSingleEntryInThePreloadCacheIsStillMatched() {
        WikiEventManager.addWikiEventListener( ClientA.class, e -> { } );
        assertTrue( WikiEventManager.isListening( new ClientA() ) );
    }

    @Test
    void theMostRecentlyPreloadedMatchingDelegateWinsAndEachIsClaimedOnce() {
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();
        WikiEventManager.addWikiEventListener( ClientA.class, e -> first.incrementAndGet() );
        WikiEventManager.addWikiEventListener( ClientA.class, e -> second.incrementAndGet() );

        final ClientA one = new ClientA();
        WikiEventManager.fireEvent( one, event( one ) );
        assertEquals( 0, first.get() );
        assertEquals( 1, second.get(), "the newest preloaded delegate is claimed first" );

        final ClientA two = new ClientA();
        WikiEventManager.fireEvent( two, event( two ) );
        assertEquals( 1, first.get(), "the older delegate goes to the next new instance" );
        assertEquals( 1, second.get() );
    }

    @Test
    void aClientAlreadySeenKeepsItsOwnDelegateAndDoesNotClaimAnotherPreload() {
        final ClientA seen = new ClientA();
        final AtomicInteger own = new AtomicInteger();
        WikiEventManager.addWikiEventListener( seen, e -> own.incrementAndGet() );
        final AtomicInteger preloaded = new AtomicInteger();
        WikiEventManager.addWikiEventListener( ClientA.class, e -> preloaded.incrementAndGet() );

        WikiEventManager.fireEvent( seen, event( seen ) );

        assertEquals( 1, own.get() );
        assertEquals( 0, preloaded.get() );
    }

    @Test
    void aClassLessDelegatePreloadedWithNullAttachesToTheNextClientOfAnyClass() {
        final AtomicInteger calls = new AtomicInteger();
        WikiEventManager.addWikiEventListener( null, e -> calls.incrementAndGet() );

        final ClientB anyClient = new ClientB();
        WikiEventManager.fireEvent( anyClient, event( anyClient ) );

        assertEquals( 1, calls.get() );
    }

    @Test
    void shutdownDiscardsPreloadedDelegatesToo() {
        WikiEventManager.addWikiEventListener( ClientA.class, e -> { } );
        WikiEventManager.shutdown();
        assertFalse( WikiEventManager.isListening( new ClientA() ),
                "a delegate preloaded before shutdown must not resurface afterwards" );
    }

    @Test
    void anEventFiredOnAClientWithListenersReachesEveryListenerOnce() {
        final Object client = new Object();
        final AtomicInteger a = new AtomicInteger();
        final AtomicInteger b = new AtomicInteger();
        WikiEventManager.addWikiEventListener( client, e -> a.incrementAndGet() );
        WikiEventManager.addWikiEventListener( client, e -> b.incrementAndGet() );
        WikiEventManager.fireEvent( client, event( client ) );
        assertEquals( 1, a.get() );
        assertEquals( 1, b.get() );
    }
}
