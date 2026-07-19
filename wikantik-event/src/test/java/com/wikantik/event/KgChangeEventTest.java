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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KgChangeEventTest {

    @Test
    void carriesDefensiveCopiesOfBothIdSets() {
        final UUID touched = UUID.randomUUID();
        final UUID removed = UUID.randomUUID();
        final Set< UUID > touchedIn = new HashSet<>( Set.of( touched ) );
        final Set< UUID > removedIn = new HashSet<>( Set.of( removed ) );
        final Object src = new Object();
        final KgChangeEvent event = new KgChangeEvent( src, touchedIn, removedIn );

        touchedIn.clear();
        removedIn.clear();

        assertEquals( Set.of( touched ), event.touchedEntityIds(), "must copy, not alias, the touched set" );
        assertEquals( Set.of( removed ), event.removedEntityIds(), "must copy, not alias, the removed set" );
        assertEquals( KgChangeEvent.KG_CHANGED, event.getType() );
        assertSame( src, event.getSrc() );
    }

    @Test
    void nullSetsNormalizeToEmpty() {
        final KgChangeEvent event = new KgChangeEvent( new Object(), null, null );
        assertTrue( event.touchedEntityIds().isEmpty() );
        assertTrue( event.removedEntityIds().isEmpty() );
    }
}
