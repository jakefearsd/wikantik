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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheInfoTest {

    @Test
    void testConstructorAndGetters() {
        final CacheInfo info = new CacheInfo( "testCache", 500 );
        assertEquals( "testCache", info.getName() );
        assertEquals( 500, info.getMaxElementsAllowed() );
        assertEquals( 0, info.getHits() );
        assertEquals( 0, info.getMisses() );
    }

    @Test
    void testHitIncrements() {
        final CacheInfo info = new CacheInfo( "test", 100 );
        info.hit();
        info.hit();
        info.hit();
        assertEquals( 3, info.getHits() );
        assertEquals( 0, info.getMisses() );
    }

    @Test
    void testMissIncrements() {
        final CacheInfo info = new CacheInfo( "test", 100 );
        info.miss();
        info.miss();
        assertEquals( 0, info.getHits() );
        assertEquals( 2, info.getMisses() );
    }

    @Test
    void testMixedHitsAndMisses() {
        final CacheInfo info = new CacheInfo( "mixed", 1000 );
        info.hit();
        info.miss();
        info.hit();
        info.hit();
        info.miss();
        assertEquals( 3, info.getHits() );
        assertEquals( 2, info.getMisses() );
    }
}
