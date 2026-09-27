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

import com.wikantik.cache.CacheInfo;
import com.wikantik.cache.CachingManager;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.exception.MemcachedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
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

    @Test
    void testResolveServersTreatsBlankAndNullAsUnset() {
        // ini/wikantik.properties declares "wikantik.cache.memcached.servers =" (blank) as its
        // shipped default (the module is opt-in). A blank or absent value must fall back to
        // localhost:11211, not be passed to AddrUtil as a literal empty server list.
        Assertions.assertEquals( "localhost:11211", MemcachedCachingManager.resolveServers( "" ) );
        Assertions.assertEquals( "localhost:11211", MemcachedCachingManager.resolveServers( null ) );
        Assertions.assertEquals( "cache1:11211,cache2:11211", MemcachedCachingManager.resolveServers( "cache1:11211,cache2:11211" ) );
    }

    /** Reflectively injects a mocked {@link MemcachedClient}, bypassing the real network connection. */
    private static MemcachedClient injectMockClient( final MemcachedCachingManager mcm ) throws Exception {
        final MemcachedClient mockClient = Mockito.mock( MemcachedClient.class );
        final Field clientField = MemcachedCachingManager.class.getDeclaredField( "client" );
        clientField.setAccessible( true );
        clientField.set( mcm, mockClient );
        return mockClient;
    }

    @Test
    void testShutdownSwallowsIOExceptionFromClient() throws Exception {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final MemcachedClient mockClient = injectMockClient( mcm );
        mcm.registerCache( CachingManager.CACHE_PAGES );
        Mockito.doThrow( new java.io.IOException( "boom" ) ).when( mockClient ).shutdown();

        Assertions.assertDoesNotThrow( mcm::shutdown );
        Assertions.assertFalse( mcm.enabled( CachingManager.CACHE_PAGES ) );
    }

    @Test
    void testGetFallsBackToSupplierWhenClientGetThrows() throws Exception {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final MemcachedClient mockClient = injectMockClient( mcm );
        mcm.registerCache( CachingManager.CACHE_PAGES );
        Mockito.when( mockClient.get( Mockito.anyString() ) ).thenThrow( new MemcachedException( "get failed" ) );

        final String result = mcm.<String, RuntimeException>get( CachingManager.CACHE_PAGES, "key1", () -> "fromSupplier" );

        Assertions.assertEquals( "fromSupplier", result );
        final CacheInfo info = mcm.info( CachingManager.CACHE_PAGES );
        Assertions.assertEquals( 1, info.getMisses() );
    }

    @Test
    void testGetSwallowsClientSetFailureAfterSupplierFallback() throws Exception {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final MemcachedClient mockClient = injectMockClient( mcm );
        mcm.registerCache( CachingManager.CACHE_PAGES );
        Mockito.when( mockClient.get( Mockito.anyString() ) ).thenReturn( null );
        Mockito.when( mockClient.set( Mockito.anyString(), Mockito.anyInt(), Mockito.any() ) )
                .thenThrow( new MemcachedException( "set failed" ) );

        final String result = mcm.<String, RuntimeException>get( CachingManager.CACHE_PAGES, "key1", () -> "fromSupplier" );

        Assertions.assertEquals( "fromSupplier", result );
    }

    @Test
    void testPutSwallowsClientSetFailure() throws Exception {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final MemcachedClient mockClient = injectMockClient( mcm );
        mcm.registerCache( CachingManager.CACHE_PAGES );
        Mockito.when( mockClient.set( Mockito.anyString(), Mockito.anyInt(), Mockito.any() ) )
                .thenThrow( new MemcachedException( "set failed" ) );

        Assertions.assertDoesNotThrow( () -> mcm.put( CachingManager.CACHE_PAGES, "key1", "value" ) );
    }

    @Test
    void testRemoveSwallowsClientDeleteFailure() throws Exception {
        final MemcachedCachingManager mcm = new MemcachedCachingManager();
        final MemcachedClient mockClient = injectMockClient( mcm );
        mcm.registerCache( CachingManager.CACHE_PAGES );
        Mockito.when( mockClient.delete( Mockito.anyString() ) ).thenThrow( new MemcachedException( "delete failed" ) );

        Assertions.assertDoesNotThrow( () -> mcm.remove( CachingManager.CACHE_PAGES, "key1" ) );
    }

}
