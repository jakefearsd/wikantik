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

import com.wikantik.auth.DefaultAuthenticationManager;
import com.wikantik.content.NewsPageGenerator;
import com.wikantik.filters.SpamFilter;
import com.wikantik.search.LuceneSearchProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural verification tests for SpotBugs concurrency fixes.
 *
 * <p>These tests verify that fields identified by SpotBugs as having stale-thread-write
 * or non-atomic access issues are now declared {@code volatile} (or use atomic types).
 * Triggering actual JVM memory visibility bugs is non-deterministic and platform-dependent,
 * so we verify the fix structurally via reflection instead.
 */
class ConcurrencyFixesTest {

    /**
     * Bug 2: WikiEngine.isConfigured must be volatile so that the value written during
     * initialize() on one thread is visible to other threads calling isConfigured().
     */
    @Test
    void wikiEngine_isConfigured_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( WikiEngine.class, "isConfigured" );
    }

    /**
     * Bug 3: DefaultAuthenticationManager fields written during initialize() must be
     * volatile so other threads see the configured values.
     */
    @Test
    void defaultAuthenticationManager_allowsCookieAssertions_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( DefaultAuthenticationManager.class, "allowsCookieAssertions" );
    }

    @Test
    void defaultAuthenticationManager_throttleLogins_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( DefaultAuthenticationManager.class, "throttleLogins" );
    }

    @Test
    void defaultAuthenticationManager_allowsCookieAuthentication_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( DefaultAuthenticationManager.class, "allowsCookieAuthentication" );
    }

    /**
     * Bug 4: SpamFilter.hashName and lastUpdate are static fields accessed from multiple
     * threads via getHashFieldName(). They must be volatile to ensure visibility.
     */
    @Test
    void spamFilter_hashName_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( SpamFilter.class, "hashName" );
    }

    @Test
    void spamFilter_lastUpdate_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( SpamFilter.class, "lastUpdate" );
    }

    /**
     * Bug 5: NewsPageGenerator.disabled is written by startupTask() (background thread)
     * and read by backgroundTask() (same or different thread invocation). Must be volatile.
     */
    @Test
    void newsPageGenerator_disabled_shouldBeVolatile() throws Exception {
        assertFieldIsVolatile( NewsPageGenerator.class, "disabled" );
    }

    /**
     * Bug 6: LuceneSearchProvider.LuceneUpdater.lastMissingPageCheck is a 64-bit long
     * written by startupTask() and read by backgroundTask(). On 32-bit JVMs, non-volatile
     * long writes can produce torn reads. Must be volatile.
     */
    @Test
    void luceneUpdater_lastMissingPageCheck_shouldBeVolatile() throws Exception {
        // LuceneUpdater is a private inner class - find it via declared classes
        Class< ? > luceneUpdaterClass = null;
        for( final Class< ? > inner : LuceneSearchProvider.class.getDeclaredClasses() ) {
            if( inner.getSimpleName().equals( "LuceneUpdater" ) ) {
                luceneUpdaterClass = inner;
                break;
            }
        }
        assertTrue( luceneUpdaterClass != null, "LuceneUpdater inner class should exist" );
        assertFieldIsVolatile( luceneUpdaterClass, "lastMissingPageCheck" );
    }

    /**
     * Bug 7: XMLUserDatabase.dom is accessed in synchronized methods (deleteByLoginName,
     * save, rename) and also in findByAttribute and getWikiNames. All access must be
     * consistently synchronized. We verify that findBy and getWikiNames are synchronized.
     */
    @Test
    void xmlUserDatabase_findBy_shouldBeSynchronized() throws Exception {
        final var method = com.wikantik.auth.user.XMLUserDatabase.class.getDeclaredMethod( "findBy", String.class, String.class );
        assertTrue( Modifier.isSynchronized( method.getModifiers() ),
                "XMLUserDatabase.findBy() should be synchronized" );
    }

    @Test
    void xmlUserDatabase_getWikiNames_shouldBeSynchronized() throws Exception {
        final var method = com.wikantik.auth.user.XMLUserDatabase.class.getDeclaredMethod( "getWikiNames" );
        assertTrue( Modifier.isSynchronized( method.getModifiers() ),
                "XMLUserDatabase.getWikiNames() should be synchronized" );
    }

    private void assertFieldIsVolatile( final Class< ? > clazz, final String fieldName ) throws Exception {
        final Field field = clazz.getDeclaredField( fieldName );
        assertTrue( Modifier.isVolatile( field.getModifiers() ),
                clazz.getSimpleName() + "." + fieldName + " should be declared volatile" );
    }

}
