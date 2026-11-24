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
package com.wikantik.api.livevalues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveValueValidationTest {

    @Test
    void knownNames() {
        assertTrue( LiveValueExpander.isKnownName( "articles" ) );
        assertTrue( LiveValueExpander.isKnownName( "cluster.a-b/c-d.pages" ) );
        assertFalse( LiveValueExpander.isKnownName( "cluster.a/b/c.pages" ) );
        assertFalse( LiveValueExpander.isKnownName( "cluster.x.bogus" ) );
        assertFalse( LiveValueExpander.isKnownName( "updated" ) );
    }

    @Test
    void malformedTokens() {
        assertTrue( LiveValueExpander.malformedTokens( "{{wiki:articles}} {{wiki:pages|round=5}}" ).isEmpty() );
        assertEquals( List.of( "{{wiki:articles|round=0}}" ),
                LiveValueExpander.malformedTokens( "a {{wiki:articles|round=0}} b" ) );
        assertEquals( 1, LiveValueExpander.malformedTokens( "{{wiki:articles ok {{wiki:pages}}" ).size() );
        assertEquals( 1, LiveValueExpander.malformedTokens( "{{wiki:}}" ).size() );
        assertTrue( LiveValueExpander.malformedTokens( null ).isEmpty() );
    }
}
