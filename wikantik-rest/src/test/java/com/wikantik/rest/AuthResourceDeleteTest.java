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
package com.wikantik.rest;

import com.wikantik.api.core.Session;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuthResource#sessionHoldsAdminRole(Session)}.
 * <p>
 * Covers the conservative admin self-delete guard: any session that carries
 * the {@code Admin} role is blocked from self-deletion via the self-service
 * path, regardless of how many other admins exist.
 */
class AuthResourceDeleteTest {

    // ----- helpers -----

    private static Principal principalNamed( final String name ) {
        final Principal p = Mockito.mock( Principal.class );
        Mockito.when( p.getName() ).thenReturn( name );
        return p;
    }

    private static Session sessionWithRoles( final Principal... roles ) {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.getRoles() ).thenReturn( roles );
        return s;
    }

    // ----- tests -----

    /**
     * A session whose roles include a principal named {@code "Admin"} must
     * cause {@code sessionHoldsAdminRole} to return {@code true}.
     */
    @Test
    void returnsTrueWhenSessionHoldsAdminRole() {
        final Session session = sessionWithRoles( principalNamed( "Admin" ) );
        assertTrue(
            AuthResource.sessionHoldsAdminRole( session ),
            "Expected true for a session with the Admin role"
        );
    }

    /**
     * A session whose roles contain only non-admin roles (e.g. {@code "Authenticated"})
     * must cause {@code sessionHoldsAdminRole} to return {@code false}.
     */
    @Test
    void returnsFalseWhenSessionLacksAdminRole() {
        final Session session = sessionWithRoles( principalNamed( "Authenticated" ) );
        assertFalse(
            AuthResource.sessionHoldsAdminRole( session ),
            "Expected false for a session with only the Authenticated role"
        );
    }

    /**
     * A session with no roles at all must cause {@code sessionHoldsAdminRole}
     * to return {@code false} (empty stream — no match).
     */
    @Test
    void returnsFalseWhenSessionHasNoRoles() {
        final Session session = sessionWithRoles( /* empty */ );
        assertFalse(
            AuthResource.sessionHoldsAdminRole( session ),
            "Expected false for a session with no roles"
        );
    }

}
