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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class URISchemeTest {

    @Test
    public void testHttpScheme() {
        assertEquals( "http", URIScheme.HTTP.getId() );
        assertEquals( "http", URIScheme.HTTP.toString() );
    }

    @Test
    public void testHttpsScheme() {
        assertEquals( "https", URIScheme.HTTPS.getId() );
        assertEquals( "https", URIScheme.HTTPS.toString() );
    }

    @Test
    public void testSameMatchesCaseInsensitive() {
        assertTrue( URIScheme.HTTP.same( "http" ) );
        assertTrue( URIScheme.HTTP.same( "HTTP" ) );
        assertTrue( URIScheme.HTTP.same( "Http" ) );
        assertFalse( URIScheme.HTTP.same( "https" ) );
    }

    @Test
    public void testHttpsSameMatchesCaseInsensitive() {
        assertTrue( URIScheme.HTTPS.same( "https" ) );
        assertTrue( URIScheme.HTTPS.same( "HTTPS" ) );
        assertFalse( URIScheme.HTTPS.same( "http" ) );
    }

    @Test
    public void testSameWithNull() {
        assertFalse( URIScheme.HTTP.same( null ) );
    }

    @Test
    public void testSameWithEmpty() {
        assertFalse( URIScheme.HTTP.same( "" ) );
    }

    @Test
    public void testEnumValues() {
        URIScheme[] values = URIScheme.values();
        assertEquals( 2, values.length );
        assertEquals( URIScheme.HTTP, URIScheme.valueOf( "HTTP" ) );
        assertEquals( URIScheme.HTTPS, URIScheme.valueOf( "HTTPS" ) );
    }

    @Test
    public void testIdFieldIsPublic() {
        // The id field is public
        assertEquals( "http", URIScheme.HTTP.id );
        assertEquals( "https", URIScheme.HTTPS.id );
    }

}
