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
package com.wikantik.cache.memcached;

import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;


@Testcontainers( disabledWithoutDocker = true )
public class MemcachedCachingManagerTest {

    @Container
    static final GenericContainer< ? > memcached =
            new GenericContainer<>( DockerImageName.parse( "memcached:1.6-alpine" ) )
                    .withExposedPorts( 11211 );

    MemcachedCachingManager mcm;

    @BeforeEach
    void setUp() throws Exception {
        mcm = new MemcachedCachingManager();
        final Properties props = new Properties();
        props.setProperty( MemcachedCachingManager.PROP_SERVERS,
                memcached.getHost() + ":" + memcached.getMappedPort( 11211 ) );
        mcm.initialize( null, props );
    }

    @AfterEach
    void tearDown() {
        if( mcm != null ) {
            mcm.shutdown();
        }
    }

    @Test
    void testInitAndShutdown() {
        Assertions.assertTrue( mcm.enabled( CachingManager.CACHE_PAGES ) );
        mcm.shutdown();
        mcm.shutdown(); // second call is a no-op
        Assertions.assertFalse( mcm.enabled( CachingManager.CACHE_PAGES ) );
        mcm = null; // prevent tearDown from double-shutting
    }

    @Test
    void testEnabled() {
        Assertions.assertTrue( mcm.enabled( CachingManager.CACHE_PAGES ) );
        Assertions.assertFalse( mcm.enabled( "trucutru" ) );
    }

    @Test
    void testInfo() {
        Assertions.assertNotNull( mcm.info( CachingManager.CACHE_PAGES ) );
        Assertions.assertNull( mcm.info( "trucutru" ) );
    }

    @Test
    void testPutGetRemoveAndKeys() throws Exception {
        final String retrieveFromBackend = "item";
        mcm.put( CachingManager.CACHE_PAGES, "key", "test" );
        mcm.put( "trucutru", "key", "test" ); // disabled cache — no-op

        Assertions.assertEquals( "test", mcm.get( CachingManager.CACHE_PAGES, "key", () -> retrieveFromBackend ) );

        mcm.remove( CachingManager.CACHE_PAGES, "key" );
        mcm.remove( CachingManager.CACHE_PAGES, null ); // null key — no-op

        // After remove, get falls back to supplier and repopulates cache
        Assertions.assertEquals( "item", mcm.get( CachingManager.CACHE_PAGES, "key", () -> retrieveFromBackend ) );
        // supplier result was stored; shadow set has 1 entry
        Assertions.assertEquals( 1, mcm.keys( CachingManager.CACHE_PAGES ).size() );
        Assertions.assertEquals( 0, mcm.keys( "trucutru" ).size() );

        Assertions.assertNull( mcm.get( CachingManager.CACHE_PAGES, null, () -> retrieveFromBackend ) );
        Assertions.assertNull( mcm.get( "trucutru", "key", () -> retrieveFromBackend ) );
    }

    @Test
    void testPutNullRemovesKey() {
        mcm.put( CachingManager.CACHE_PAGES, "key", "value" );
        Assertions.assertEquals( 1, mcm.keys( CachingManager.CACHE_PAGES ).size() );

        mcm.put( CachingManager.CACHE_PAGES, "key", null ); // null value → remove
        Assertions.assertEquals( 0, mcm.keys( CachingManager.CACHE_PAGES ).size() );
    }

    @Test
    void testKeysSnapshotIsIndependent() {
        mcm.put( CachingManager.CACHE_PAGES, "page1", "v1" );
        mcm.put( CachingManager.CACHE_PAGES, "page2", "v2" );
        final var snapshot = mcm.keys( CachingManager.CACHE_PAGES );
        Assertions.assertEquals( 2, snapshot.size() );

        mcm.put( CachingManager.CACHE_PAGES, "page3", "v3" );
        // Snapshot was taken before page3 was added
        Assertions.assertEquals( 2, snapshot.size() );
    }

    @Test
    void testRegisterListenerIsNoOp() {
        Assertions.assertFalse( mcm.registerListener( CachingManager.CACHE_PAGES, "expired", new AtomicBoolean() ) );
    }

    @Test
    void testGetFallsBackToSupplierOnMiss() throws Exception {
        // No put — get should invoke supplier and store the result
        final String result = mcm.get( CachingManager.CACHE_PAGES, "new-key", () -> "fresh" );
        Assertions.assertEquals( "fresh", result );
        // Now it should be cached
        Assertions.assertEquals( 1, mcm.keys( CachingManager.CACHE_PAGES ).size() );
        Assertions.assertTrue( mcm.keys( CachingManager.CACHE_PAGES ).contains( "new-key" ) );
    }

    @Test
    void testGetNullSupplierDoesNotPopulateCache() throws Exception {
        final String result = mcm.get( CachingManager.CACHE_PAGES, "missing-key", () -> null );
        Assertions.assertNull( result );
        Assertions.assertTrue( mcm.keys( CachingManager.CACHE_PAGES ).isEmpty() );
    }

}
