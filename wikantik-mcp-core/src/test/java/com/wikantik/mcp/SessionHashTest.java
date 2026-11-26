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
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHashTest {

    @Test
    void sameInputGivesSameTwelveHexChars() {
        final String a = SessionHash.of( "session-abc" );
        assertEquals( a, SessionHash.of( "session-abc" ) );
        assertTrue( a.matches( "[0-9a-f]{12}" ), a );
    }

    @Test
    void differentInputGivesDifferentHash() {
        assertNotEquals( SessionHash.of( "session-abc" ), SessionHash.of( "session-abd" ) );
    }

    @Test
    void nullOrBlankIsNone() {
        assertEquals( "none", SessionHash.of( null ) );
        assertEquals( "none", SessionHash.of( "" ) );
        assertEquals( "none", SessionHash.of( "   " ) );
    }

    @Test
    void outputNeverContainsTheInput() {
        assertFalse( SessionHash.of( "abcdef123456" ).contains( "abcdef123456" ) );
    }
}
