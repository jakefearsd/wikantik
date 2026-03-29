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
package com.wikantik.auth.authorize;

import java.security.Principal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Role}, an immutable Principal representing wiki roles.
 * Role equality drives authorization decisions, so these tests protect the
 * security model.
 */
public class RoleTest
{
    // ---------------------------------------------------------------
    // Built-in role constants
    // ---------------------------------------------------------------

    @Test
    public void testBuiltInRoleAll()
    {
        assertNotNull( Role.ALL );
        assertEquals( "All", Role.ALL.getName() );
    }

    @Test
    public void testBuiltInRoleAnonymous()
    {
        assertNotNull( Role.ANONYMOUS );
        assertEquals( "Anonymous", Role.ANONYMOUS.getName() );
    }

    @Test
    public void testBuiltInRoleAsserted()
    {
        assertNotNull( Role.ASSERTED );
        assertEquals( "Asserted", Role.ASSERTED.getName() );
    }

    @Test
    public void testBuiltInRoleAuthenticated()
    {
        assertNotNull( Role.AUTHENTICATED );
        assertEquals( "Authenticated", Role.AUTHENTICATED.getName() );
    }

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    @Test
    public void testConstructWithName()
    {
        final Role role = new Role( "CustomRole" );
        assertEquals( "CustomRole", role.getName() );
    }

    @Test
    public void testPackagePrivateConstructorNullName()
    {
        // The package-private no-arg constructor sets name to null (for serialization).
        final Role role = new Role();
        assertNull( role.getName() );
    }

    @Test
    public void testRoleImplementsPrincipal()
    {
        final Role role = new Role( "TestRole" );
        assertInstanceOf( Principal.class, role );
    }

    // ---------------------------------------------------------------
    // getName()
    // ---------------------------------------------------------------

    @Test
    public void testGetName()
    {
        final Role role = new Role( "Editor" );
        assertEquals( "Editor", role.getName() );
    }

    @Test
    public void testGetNameBuiltIn()
    {
        assertEquals( "All", Role.ALL.getName() );
        assertEquals( "Anonymous", Role.ANONYMOUS.getName() );
        assertEquals( "Asserted", Role.ASSERTED.getName() );
        assertEquals( "Authenticated", Role.AUTHENTICATED.getName() );
    }

    // ---------------------------------------------------------------
    // isBuiltInRole(Role)
    // ---------------------------------------------------------------

    @Test
    public void testIsBuiltInRoleAll()
    {
        assertTrue( Role.isBuiltInRole( Role.ALL ) );
    }

    @Test
    public void testIsBuiltInRoleAnonymous()
    {
        assertTrue( Role.isBuiltInRole( Role.ANONYMOUS ) );
    }

    @Test
    public void testIsBuiltInRoleAsserted()
    {
        assertTrue( Role.isBuiltInRole( Role.ASSERTED ) );
    }

    @Test
    public void testIsBuiltInRoleAuthenticated()
    {
        assertTrue( Role.isBuiltInRole( Role.AUTHENTICATED ) );
    }

    @Test
    public void testIsBuiltInRoleNewRoleWithBuiltInName()
    {
        // A freshly constructed Role with the same name as a built-in
        // should still be recognized as built-in (equality is name-based).
        assertTrue( Role.isBuiltInRole( new Role( "All" ) ) );
        assertTrue( Role.isBuiltInRole( new Role( "Anonymous" ) ) );
        assertTrue( Role.isBuiltInRole( new Role( "Asserted" ) ) );
        assertTrue( Role.isBuiltInRole( new Role( "Authenticated" ) ) );
    }

    @Test
    public void testIsBuiltInRoleCustomRoleReturnsFalse()
    {
        assertFalse( Role.isBuiltInRole( new Role( "Admin" ) ) );
        assertFalse( Role.isBuiltInRole( new Role( "Editor" ) ) );
        assertFalse( Role.isBuiltInRole( new Role( "Moderator" ) ) );
    }

    // ---------------------------------------------------------------
    // isReservedName(String)
    // ---------------------------------------------------------------

    @Test
    public void testIsReservedNameAll()
    {
        assertTrue( Role.isReservedName( "All" ) );
    }

    @Test
    public void testIsReservedNameAnonymous()
    {
        assertTrue( Role.isReservedName( "Anonymous" ) );
    }

    @Test
    public void testIsReservedNameAsserted()
    {
        assertTrue( Role.isReservedName( "Asserted" ) );
    }

    @Test
    public void testIsReservedNameAuthenticated()
    {
        assertTrue( Role.isReservedName( "Authenticated" ) );
    }

    @Test
    public void testIsReservedNameCustomNameReturnsFalse()
    {
        assertFalse( Role.isReservedName( "Admin" ) );
        assertFalse( Role.isReservedName( "Editor" ) );
        assertFalse( Role.isReservedName( "SomeRole" ) );
    }

    @Test
    public void testIsReservedNameIsCaseSensitive()
    {
        // Only exact case matches are reserved
        assertFalse( Role.isReservedName( "all" ) );
        assertFalse( Role.isReservedName( "ALL" ) );
        assertFalse( Role.isReservedName( "anonymous" ) );
        assertFalse( Role.isReservedName( "ANONYMOUS" ) );
        assertFalse( Role.isReservedName( "asserted" ) );
        assertFalse( Role.isReservedName( "ASSERTED" ) );
        assertFalse( Role.isReservedName( "authenticated" ) );
        assertFalse( Role.isReservedName( "AUTHENTICATED" ) );
    }

    // ---------------------------------------------------------------
    // equals()
    // ---------------------------------------------------------------

    @Test
    public void testEqualsSameName()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Admin" );
        assertEquals( r1, r2 );
    }

    @Test
    public void testEqualsDifferentName()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Editor" );
        assertNotEquals( r1, r2 );
    }

    @Test
    public void testEqualsNull()
    {
        final Role r1 = new Role( "Admin" );
        assertNotEquals( null, r1 );
        // Also test explicitly via equals(null)
        assertFalse( r1.equals( null ) );
    }

    @Test
    public void testEqualsNonRoleObject()
    {
        final Role r1 = new Role( "Admin" );
        assertFalse( r1.equals( "Admin" ) );
        assertFalse( r1.equals( Integer.valueOf( 42 ) ) );
    }

    @Test
    public void testEqualsReflexive()
    {
        final Role r1 = new Role( "Admin" );
        assertEquals( r1, r1 );
    }

    @Test
    public void testEqualsSymmetric()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Admin" );
        assertEquals( r1, r2 );
        assertEquals( r2, r1 );
    }

    @Test
    public void testEqualsTransitive()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Admin" );
        final Role r3 = new Role( "Admin" );
        assertEquals( r1, r2 );
        assertEquals( r2, r3 );
        assertEquals( r1, r3 );
    }

    @Test
    public void testEqualsBuiltInWithNewInstance()
    {
        // A new Role("All") should equal the static Role.ALL constant
        assertEquals( Role.ALL, new Role( "All" ) );
        assertEquals( Role.ANONYMOUS, new Role( "Anonymous" ) );
        assertEquals( Role.ASSERTED, new Role( "Asserted" ) );
        assertEquals( Role.AUTHENTICATED, new Role( "Authenticated" ) );
    }

    @Test
    public void testEqualsBuiltInRolesNotEqualToEachOther()
    {
        assertNotEquals( Role.ALL, Role.ANONYMOUS );
        assertNotEquals( Role.ALL, Role.ASSERTED );
        assertNotEquals( Role.ALL, Role.AUTHENTICATED );
        assertNotEquals( Role.ANONYMOUS, Role.ASSERTED );
        assertNotEquals( Role.ANONYMOUS, Role.AUTHENTICATED );
        assertNotEquals( Role.ASSERTED, Role.AUTHENTICATED );
    }

    // ---------------------------------------------------------------
    // hashCode()
    // ---------------------------------------------------------------

    @Test
    public void testHashCodeConsistentWithEquals()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Admin" );
        assertEquals( r1, r2 );
        assertEquals( r1.hashCode(), r2.hashCode() );
    }

    @Test
    public void testHashCodeBasedOnName()
    {
        final Role role = new Role( "TestRole" );
        assertEquals( "TestRole".hashCode(), role.hashCode() );
    }

    @Test
    public void testHashCodeBuiltInRoles()
    {
        assertEquals( "All".hashCode(), Role.ALL.hashCode() );
        assertEquals( "Anonymous".hashCode(), Role.ANONYMOUS.hashCode() );
        assertEquals( "Asserted".hashCode(), Role.ASSERTED.hashCode() );
        assertEquals( "Authenticated".hashCode(), Role.AUTHENTICATED.hashCode() );
    }

    @Test
    public void testHashCodeDifferentForDifferentNames()
    {
        final Role r1 = new Role( "Admin" );
        final Role r2 = new Role( "Editor" );
        // Not strictly required by the contract, but with String.hashCode
        // these are virtually guaranteed to differ.
        assertNotEquals( r1.hashCode(), r2.hashCode() );
    }

    @Test
    public void testHashCodeNewInstanceMatchesConstant()
    {
        assertEquals( Role.ALL.hashCode(), new Role( "All" ).hashCode() );
    }

    // ---------------------------------------------------------------
    // toString()
    // ---------------------------------------------------------------

    @Test
    public void testToStringFormat()
    {
        final Role role = new Role( "Admin" );
        assertEquals( "[com.wikantik.auth.authorize.Role: Admin]", role.toString() );
    }

    @Test
    public void testToStringBuiltInRoles()
    {
        assertEquals( "[com.wikantik.auth.authorize.Role: All]", Role.ALL.toString() );
        assertEquals( "[com.wikantik.auth.authorize.Role: Anonymous]", Role.ANONYMOUS.toString() );
        assertEquals( "[com.wikantik.auth.authorize.Role: Asserted]", Role.ASSERTED.toString() );
        assertEquals( "[com.wikantik.auth.authorize.Role: Authenticated]", Role.AUTHENTICATED.toString() );
    }

    @Test
    public void testToStringCustomRole()
    {
        final Role role = new Role( "MyCustomRole" );
        assertEquals( "[com.wikantik.auth.authorize.Role: MyCustomRole]", role.toString() );
    }

    // ---------------------------------------------------------------
    // Edge cases / security-critical scenarios
    // ---------------------------------------------------------------

    @Test
    public void testBuiltInRoleConstantsAreDistinctInstances()
    {
        // Each built-in constant should be a separate object
        assertNotSame( Role.ALL, Role.ANONYMOUS );
        assertNotSame( Role.ALL, Role.ASSERTED );
        assertNotSame( Role.ALL, Role.AUTHENTICATED );
        assertNotSame( Role.ANONYMOUS, Role.ASSERTED );
        assertNotSame( Role.ANONYMOUS, Role.AUTHENTICATED );
        assertNotSame( Role.ASSERTED, Role.AUTHENTICATED );
    }

    @Test
    public void testCustomRoleNotBuiltIn()
    {
        // A role that sounds privileged but is not a built-in name
        // must not be treated as built-in
        assertFalse( Role.isBuiltInRole( new Role( "SuperAdmin" ) ) );
        assertFalse( Role.isReservedName( "SuperAdmin" ) );
    }

    @Test
    public void testRoleWithWhitespaceName()
    {
        final Role role = new Role( " All " );
        // Whitespace-padded name is NOT the same as the built-in "All"
        assertNotEquals( Role.ALL, role );
        assertFalse( Role.isBuiltInRole( role ) );
        assertFalse( Role.isReservedName( " All " ) );
    }

    @Test
    public void testRoleWithEmptyName()
    {
        final Role role = new Role( "" );
        assertEquals( "", role.getName() );
        assertFalse( Role.isBuiltInRole( role ) );
        assertFalse( Role.isReservedName( "" ) );
    }
}
