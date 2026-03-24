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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;


/**
 * Tests that do not require a live memcached server or Docker.
 */
public class MemcachedCachingManagerUnitTest {

    @Test
    void testToMemcachedKeyIsDeterministicAndSafe() {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final String key1 = mcm.toMemcachedKey( CachingManager.CACHE_PAGES, "My Page Name" );
        final String key2 = mcm.toMemcachedKey( CachingManager.CACHE_PAGES, "My Page Name" );
        final String keyOtherCache = mcm.toMemcachedKey( CachingManager.CACHE_ATTACHMENTS, "My Page Name" );

        Assertions.assertEquals( key1, key2, "same input must produce same key" );
        Assertions.assertNotEquals( key1, keyOtherCache, "different cache must produce different key" );
        Assertions.assertEquals( 65, key1.length(), "w + 64 hex chars = 65 chars" );
        Assertions.assertTrue( key1.startsWith( "w" ) );
        Assertions.assertTrue( key1.substring( 1 ).matches( "[0-9a-f]{64}" ) );
    }

    @Test
    void testCacheWhenDisabled() throws Exception {
        final Properties props = new Properties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        final MemcachedCachingManager disabled = new MemcachedCachingManager();
        disabled.initialize( null, props );
        Assertions.assertFalse( disabled.enabled( CachingManager.CACHE_PAGES ) );
        Assertions.assertTrue( disabled.keys( CachingManager.CACHE_PAGES ).isEmpty() );
        Assertions.assertNull( disabled.info( CachingManager.CACHE_PAGES ) );
        disabled.shutdown();
    }

    @Test
    void testKeyAndCacheAreNotNullReturnsFalseForUnknownCache() {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        Assertions.assertFalse( mcm.keyAndCacheAreNotNull( "unknownCache", "key" ) );
        Assertions.assertFalse( mcm.keyAndCacheAreNotNull( CachingManager.CACHE_PAGES, null ) );
    }

}
