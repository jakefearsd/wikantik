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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class HnswParamsTest {

    @Test
    void usesDefaultsWhenAbsent() {
        final HnswParams p = HnswParams.fromProperties( new Properties() );
        assertEquals( 16, p.m() );
        assertEquals( 64, p.efConstruction() );
        assertEquals( 100, p.efSearch() );
    }

    @Test
    void readsOverrides() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.search.dense.lucene.m", "32" );
        props.setProperty( "wikantik.search.dense.lucene.ef_construction", "128" );
        props.setProperty( "wikantik.search.dense.lucene.ef_search", "200" );
        final HnswParams p = HnswParams.fromProperties( props );
        assertEquals( 32, p.m() );
        assertEquals( 128, p.efConstruction() );
        assertEquals( 200, p.efSearch() );
    }

    @Test
    void rejectsNonPositive() {
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 0, 64, 100 ) );
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 16, 0, 100 ) );
        assertThrows( IllegalArgumentException.class, () -> new HnswParams( 16, 64, 0 ) );
    }
}
