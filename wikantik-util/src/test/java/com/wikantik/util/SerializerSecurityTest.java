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
package com.wikantik.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * Security tests for {@link Serializer} deserialization filtering.
 * Verifies that an {@link java.io.ObjectInputFilter} whitelist blocks
 * instantiation of non-whitelisted classes during deserialization.
 */
public class SerializerSecurityTest {

    /**
     * A serialized HashMap containing a non-whitelisted type (AtomicInteger)
     * must be rejected with an IOException when the ObjectInputFilter is active.
     */
    @Test
    public void testRejectsNonWhitelistedClass() throws Exception {
        // Craft a payload containing a non-whitelisted class
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream( baos );
        final HashMap<String, Serializable> evil = new HashMap<>();
        evil.put( "payload", new AtomicInteger( 42 ) );
        oos.writeObject( evil );
        oos.close();
        final String base64 = Base64.getEncoder().encodeToString( baos.toByteArray() );

        // Should throw IOException because AtomicInteger is not in the whitelist
        Assertions.assertThrows( IOException.class, () -> Serializer.deserializeFromBase64( base64 ) );
    }

    /**
     * A round-trip of a HashMap containing only String values must succeed.
     * This is a control test that should pass both before and after the fix.
     */
    @Test
    public void testAcceptsSafeClasses() throws Exception {
        final Map<String, Serializable> map = new HashMap<>();
        map.put( "key1", "value1" );
        map.put( "key2", "value2" );
        final String serialized = Serializer.serializeToBase64( map );

        final Map<String, ? extends Serializable> result = Serializer.deserializeFromBase64( serialized );
        Assertions.assertEquals( 2, result.size() );
        Assertions.assertEquals( "value1", result.get( "key1" ) );
        Assertions.assertEquals( "value2", result.get( "key2" ) );
    }

    /**
     * A round-trip of a HashMap containing common value types (Integer, Long,
     * Boolean, Date) must succeed. This verifies the whitelist includes types
     * commonly used in user profile attributes.
     */
    @Test
    public void testAcceptsCommonValueTypes() throws Exception {
        final Map<String, Serializable> map = new HashMap<>();
        map.put( "anInteger", Integer.valueOf( 42 ) );
        map.put( "aLong", Long.valueOf( 123456789L ) );
        map.put( "aBoolean", Boolean.TRUE );
        map.put( "aDate", new Date( 1000000L ) );
        map.put( "aFloat", Float.valueOf( 3.14f ) );
        map.put( "aDouble", Double.valueOf( 2.718 ) );
        final String serialized = Serializer.serializeToBase64( map );

        final Map<String, ? extends Serializable> result = Serializer.deserializeFromBase64( serialized );
        Assertions.assertEquals( 6, result.size() );
        Assertions.assertEquals( Integer.valueOf( 42 ), result.get( "anInteger" ) );
        Assertions.assertEquals( Long.valueOf( 123456789L ), result.get( "aLong" ) );
        Assertions.assertEquals( Boolean.TRUE, result.get( "aBoolean" ) );
        Assertions.assertEquals( new Date( 1000000L ), result.get( "aDate" ) );
        Assertions.assertEquals( Float.valueOf( 3.14f ), result.get( "aFloat" ) );
        Assertions.assertEquals( Double.valueOf( 2.718 ), result.get( "aDouble" ) );
    }

}
