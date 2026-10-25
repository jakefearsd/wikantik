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
package com.wikantik.markdown.extensions.fences;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FencePlaceholdersTest {

    @Test
    void signIsStable() {
        final String t = FencePlaceholders.sign( "a -> b\n" );
        assertEquals( t, FencePlaceholders.sign( "a -> b\n" ) );
        assertTrue( t.matches( "[0-9a-f]{64}\\.[0-9a-f]{64}" ), t );
        assertTrue( FencePlaceholders.verify( t, "a -> b\n" ) );
    }

    @Test
    void verifyRejectsTamperedHash() {
        final String t = FencePlaceholders.sign( "x" );
        final char c = t.charAt( 0 ) == '0' ? '1' : '0';
        assertFalse( FencePlaceholders.verify( c + t.substring( 1 ), "x" ) );
    }

    @Test
    void verifyRejectsTamperedMac() {
        final String t = FencePlaceholders.sign( "x" );
        final int last = t.length() - 1;
        final char c = t.charAt( last ) == '0' ? '1' : '0';
        assertFalse( FencePlaceholders.verify( t.substring( 0, last ) + c, "x" ) );
    }

    @Test
    void verifyRejectsOtherSource() {
        assertFalse( FencePlaceholders.verify( FencePlaceholders.sign( "x" ), "y" ) );
        assertFalse( FencePlaceholders.verify( null, "x" ) );
        assertFalse( FencePlaceholders.verify( "garbage", "x" ) );
    }
}
