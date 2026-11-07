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
package com.wikantik.collab;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollabConfigTest {

    @Test
    void defaultsMatchTheDesign() {
        final CollabConfig c = CollabConfig.from( new Properties() );
        assertFalse( c.enabled() );
        assertEquals( 20, c.maxParticipants() );
        assertEquals( 65536, c.maxUpdateBytes() );
        assertEquals( 25, c.pollTimeoutSeconds() );
        assertEquals( 45, c.presenceTimeoutSeconds() );
        assertEquals( 30, c.permissionRecheckSeconds() );
        assertEquals( 2000, c.historyInMemoryUpdates() );
        assertEquals( 500, c.snapshotEveryUpdates() );
        assertEquals( 10, c.memoryIdleEvictMinutes() );
        assertEquals( 0, c.draftRetentionDays() );
        assertEquals( 20, c.pushesPerSecond() );
        assertEquals( CollabConfig.defaults(), c );
    }

    @Test
    void explicitValuesAreRead() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.enabled", "true" );
        p.setProperty( "wikantik.collab.maxParticipants", "5" );
        p.setProperty( "wikantik.collab.pushesPerSecond", "7" );
        p.setProperty( "wikantik.collab.draft.retentionDays", "30" );
        final CollabConfig c = CollabConfig.from( p );
        assertTrue( c.enabled() );
        assertEquals( 5, c.maxParticipants() );
        assertEquals( 7, c.pushesPerSecond() );
        assertEquals( 30, c.draftRetentionDays() );
    }

    @Test
    void nonPositiveValuesFallBackToTheDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.maxParticipants", "0" );
        p.setProperty( "wikantik.collab.maxUpdateBytes", "-1" );
        p.setProperty( "wikantik.collab.poll.timeoutSeconds", "0" );
        p.setProperty( "wikantik.collab.presence.timeoutSeconds", "-5" );
        p.setProperty( "wikantik.collab.permission.recheckSeconds", "0" );
        p.setProperty( "wikantik.collab.history.inMemoryUpdates", "0" );
        p.setProperty( "wikantik.collab.snapshot.everyUpdates", "-9" );
        p.setProperty( "wikantik.collab.memory.idleEvictMinutes", "0" );
        p.setProperty( "wikantik.collab.pushesPerSecond", "0" );
        p.setProperty( "wikantik.collab.draft.retentionDays", "-1" );
        assertEquals( CollabConfig.defaults(), CollabConfig.from( p ) );
    }

    @Test
    void zeroRetentionMeansKeepForever() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.collab.draft.retentionDays", "0" );
        assertEquals( 0, CollabConfig.from( p ).draftRetentionDays() );
    }
}
