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
package com.wikantik.core.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineServiceRegistryTest {

    interface Foo {}
    static final class FooImpl implements Foo {}

    @Test
    void getReturnsRegisteredInstanceAndNullWhenAbsent() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl impl = new FooImpl();
        reg.put( Foo.class, impl );
        assertSame( impl, reg.get( Foo.class ) );
        assertNull( reg.get( Runnable.class ) );
    }

    @Test
    void putOverwritesPriorValue() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl a = new FooImpl();
        final FooImpl b = new FooImpl();
        reg.put( Foo.class, a );
        reg.put( Foo.class, b );
        assertSame( b, reg.get( Foo.class ) );
    }

    @Test
    void isKnownTypeTracksEverWrittenIncludingNullValue() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        assertFalse( reg.isKnownType( Foo.class ) );
        reg.put( Foo.class, null );      // known even though value is null
        assertTrue( reg.isKnownType( Foo.class ) );
        assertNull( reg.get( Foo.class ) );
    }

    @Test
    void identitySemanticsNoSubtypeLookup() {
        final EngineServiceRegistry reg = new EngineServiceRegistry();
        final FooImpl impl = new FooImpl();
        reg.put( FooImpl.class, impl );
        assertNull( reg.get( Foo.class ) );   // interface key was never written
        assertSame( impl, reg.get( FooImpl.class ) );
    }
}
