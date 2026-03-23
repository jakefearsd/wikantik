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
package com.wikantik.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StubReferenceManagerTest {

    private StubReferenceManager refMgr;

    @BeforeEach
    void setUp() {
        refMgr = new StubReferenceManager();
    }

    @Test
    void testAddReferencesPopulatesBothMaps() {
        refMgr.addReferences( "PageA", Set.of( "PageB", "PageC" ) );

        // refersTo
        final Collection< String > outbound = refMgr.findRefersTo( "PageA" );
        assertTrue( outbound.contains( "PageB" ) );
        assertTrue( outbound.contains( "PageC" ) );

        // referredBy
        assertTrue( refMgr.findReferrers( "PageB" ).contains( "PageA" ) );
        assertTrue( refMgr.findReferrers( "PageC" ).contains( "PageA" ) );
    }

    @Test
    void testFindReferrersReturnsCorrectInboundLinks() {
        refMgr.addReferences( "Source1", Set.of( "Target" ) );
        refMgr.addReferences( "Source2", Set.of( "Target" ) );

        final Set< String > referrers = refMgr.findReferrers( "Target" );
        assertEquals( 2, referrers.size() );
        assertTrue( referrers.contains( "Source1" ) );
        assertTrue( referrers.contains( "Source2" ) );
    }

    @Test
    void testFindReferrersUnknownPageReturnsEmpty() {
        assertTrue( refMgr.findReferrers( "Unknown" ).isEmpty() );
    }

    @Test
    void testFindRefersToReturnsCorrectOutboundLinks() {
        refMgr.addReferences( "Source", Set.of( "TargetA", "TargetB" ) );

        final Collection< String > targets = refMgr.findRefersTo( "Source" );
        assertEquals( 2, targets.size() );
        assertTrue( targets.contains( "TargetA" ) );
        assertTrue( targets.contains( "TargetB" ) );
    }

    @Test
    void testFindRefersToUnknownPageReturnsEmpty() {
        assertTrue( refMgr.findRefersTo( "Unknown" ).isEmpty() );
    }

    @Test
    void testFindUnreferencedReturnsPagesWithNoInboundRefs() {
        refMgr.addReferences( "Orphan", Set.of( "LinkedTarget" ) );
        refMgr.addReferences( "Linker", Set.of( "LinkedTarget" ) );

        // Orphan has outbound links but nobody links TO it
        // Linker also has no inbound refs
        final Collection< String > unreferenced = refMgr.findUnreferenced();
        assertTrue( unreferenced.contains( "Orphan" ) );
        assertTrue( unreferenced.contains( "Linker" ) );
        assertFalse( unreferenced.contains( "LinkedTarget" ) );
    }

    @Test
    void testFindUncreatedReturnsReferencedButNotCreatedPages() {
        refMgr.addReferences( "ExistingPage", Set.of( "NonExistentPage" ) );

        // NonExistentPage is referenced but never had addReferences called for it
        final Collection< String > uncreated = refMgr.findUncreated();
        assertTrue( uncreated.contains( "NonExistentPage" ) );
        assertFalse( uncreated.contains( "ExistingPage" ) );
    }

    @Test
    void testFindCreatedReturnsAllRefersToKeys() {
        refMgr.addReferences( "Page1", Set.of( "Page2" ) );
        refMgr.addReferences( "Page2", Set.of( "Page3" ) );

        final Set< String > created = refMgr.findCreated();
        assertTrue( created.contains( "Page1" ) );
        assertTrue( created.contains( "Page2" ) );
        assertFalse( created.contains( "Page3" ) );
    }

    @Test
    void testClearPageEntriesRemovesFromBothMaps() {
        refMgr.addReferences( "Source", Set.of( "Target" ) );

        // Verify data exists
        assertFalse( refMgr.findRefersTo( "Source" ).isEmpty() );
        assertTrue( refMgr.findReferrers( "Target" ).contains( "Source" ) );

        refMgr.clearPageEntries( "Source" );

        // refersTo entry should be gone
        assertTrue( refMgr.findRefersTo( "Source" ).isEmpty() );
        // referredBy entry for Target should no longer contain Source
        assertFalse( refMgr.findReferrers( "Target" ).contains( "Source" ) );
    }

    @Test
    void testIsInitializedReturnsTrue() {
        assertTrue( refMgr.isInitialized() );
    }

    @Test
    void testFindReferredByMatchesFindReferrers() {
        refMgr.addReferences( "A", Set.of( "B" ) );

        assertEquals( refMgr.findReferrers( "B" ), refMgr.findReferredBy( "B" ) );
    }

    @Test
    void testUpdateReferencesWithStringDelegatesToAddReferences() {
        refMgr.updateReferences( "X", Set.of( "Y", "Z" ) );

        assertTrue( refMgr.findRefersTo( "X" ).contains( "Y" ) );
        assertTrue( refMgr.findRefersTo( "X" ).contains( "Z" ) );
        assertTrue( refMgr.findReferrers( "Y" ).contains( "X" ) );
    }
}
