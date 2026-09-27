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
package com.wikantik.cache;

import com.wikantik.util.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link EhcacheCachingManager} overrides {@code registerListener}, so no shipped
 * implementation ever exercises {@link CachingManager}'s own default (false-returning)
 * method. A minimal stub implementation exercises it directly.
 */
class CachingManagerDefaultMethodTest {

    private static final class MinimalCachingManager implements CachingManager {
        @Override public void shutdown() { }
        @Override public boolean enabled( final String cacheName ) { return false; }
        @Override public CacheInfo info( final String cacheName ) { return null; }
        @Override public < T extends Serializable > List< T > keys( final String cacheName ) { return List.of(); }
        @Override public < T, E extends Exception > T get( final String cacheName, final Serializable key, final CheckedSupplier< T, E > supplier ) {
            return null;
        }
        @Override public void put( final String cacheName, final Serializable key, final Object val ) { }
        @Override public void remove( final String cacheName, final Serializable key ) { }
    }

    @Test
    void defaultRegisterListenerAlwaysReturnsFalse() {
        final CachingManager manager = new MinimalCachingManager();
        assertFalse( manager.registerListener( "anyCache", "anyListener" ) );
        assertFalse( manager.registerListener( "anyCache", "anyListener", "extra-arg" ) );
    }
}
