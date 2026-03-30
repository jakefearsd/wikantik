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
package com.wikantik.auth.login;

import com.wikantik.auth.WikiPrincipal;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PrincipalWrapper} — covers all previously uncovered lines.
 */
class PrincipalWrapperTest {

    @Test
    void testGetNameDelegatesToWrappedPrincipal() {
        final WikiPrincipal inner = new WikiPrincipal( "Alice" );
        final PrincipalWrapper wrapper = new PrincipalWrapper( inner );
        assertEquals( "Alice", wrapper.getName() );
    }

    @Test
    void testGetPrincipalReturnsWrapped() {
        final WikiPrincipal inner = new WikiPrincipal( "Bob" );
        final PrincipalWrapper wrapper = new PrincipalWrapper( inner );
        assertSame( inner, wrapper.getPrincipal() );
    }

    @Test
    void testEqualsTwoWrappersWithSamePrincipal() {
        final WikiPrincipal inner = new WikiPrincipal( "Alice" );
        final PrincipalWrapper w1 = new PrincipalWrapper( inner );
        final PrincipalWrapper w2 = new PrincipalWrapper( new WikiPrincipal( "Alice" ) );
        assertEquals( w1, w2 );
    }

    @Test
    void testEqualsTwoWrappersWithDifferentPrincipal() {
        final PrincipalWrapper w1 = new PrincipalWrapper( new WikiPrincipal( "Alice" ) );
        final PrincipalWrapper w2 = new PrincipalWrapper( new WikiPrincipal( "Bob" ) );
        assertNotEquals( w1, w2 );
    }

    @Test
    void testEqualsNonPrincipalWrapperReturnsFalse() {
        final PrincipalWrapper wrapper = new PrincipalWrapper( new WikiPrincipal( "Alice" ) );
        // Passing an object that is not a PrincipalWrapper must return false
        assertNotEquals( wrapper, "Alice" );
        assertNotEquals( wrapper, null );
    }

    @Test
    void testHashCodeBasedOnWrappedPrincipal() {
        final WikiPrincipal inner = new WikiPrincipal( "Alice" );
        final PrincipalWrapper wrapper = new PrincipalWrapper( inner );
        assertEquals( inner.hashCode() * 13, wrapper.hashCode() );
    }

    @Test
    void testHashCodeConsistentAcrossEqualWrappers() {
        final PrincipalWrapper w1 = new PrincipalWrapper( new WikiPrincipal( "Alice" ) );
        final PrincipalWrapper w2 = new PrincipalWrapper( new WikiPrincipal( "Alice" ) );
        assertEquals( w1.hashCode(), w2.hashCode() );
    }
}
