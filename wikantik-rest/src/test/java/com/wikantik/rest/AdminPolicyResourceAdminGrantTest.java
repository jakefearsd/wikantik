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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation for the {@code admin} permission type.
 *
 * <p>These exist because adding the type to the policy model is not enough to make it usable: the
 * REST layer validates {@code permissionType} and {@code actions} against separate allowlists, and
 * missing either one produces a grant that <em>cannot be created at all</em> while every unit test
 * of the permission class itself still passes. That is exactly what happened — the first deployed
 * attempt to create the grant returned
 * {@code "Invalid action 'access' for permission type 'admin'. Valid actions: []"}.
 */
class AdminPolicyResourceAdminGrantTest {

    private final AdminPolicyResource resource = new AdminPolicyResource();

    @Test
    void adminAreaGrantIsAccepted() {
        assertNull( resource.validateGrantFields( "user", "jakemon-shipper", "admin", "insights", "access" ),
                "a scoped admin-area grant must be creatable through the API" );
    }

    @Test
    void accessIsAValidAdminAction() {
        assertNull( resource.validateActions( "admin", "access" ) );
    }

    @Test
    void unknownAdminActionIsRejected() {
        final String error = resource.validateActions( "admin", "purge" );
        assertNotNull( error );
        assertTrue( error.contains( "Invalid action" ), error );
    }

    /**
     * An area grant is narrower than AllPermission but still administrative. Handing it to a
     * built-in broad role would give the whole population an admin surface — and with target
     * {@code *}, every surface.
     */
    @Test
    void adminGrantCannotBeGivenToBroadRoles() {
        for ( final String role : new String[] { "All", "Anonymous", "Asserted", "Authenticated" } ) {
            final String error = resource.validateGrantFields( "role", role, "admin", "*", "access" );
            assertNotNull( error, "broad role '" + role + "' must be refused an admin grant" );
            assertTrue( error.contains( role ), error );
        }
    }

    @Test
    void adminGrantToANamedRoleIsStillAllowed() {
        assertNull( resource.validateGrantFields( "role", "Ops", "admin", "insights", "access" ) );
    }

    /** The pre-existing types must be untouched by the addition. */
    @Test
    void existingPermissionTypesStillValidate() {
        assertNull( resource.validateGrantFields( "role", "Authenticated", "page", "*", "view" ) );
        assertNull( resource.validateGrantFields( "role", "Authenticated", "wiki", "*", "createPages" ) );
        assertNull( resource.validateGrantFields( "role", "Authenticated", "group", "*", "view" ) );
        assertNotNull( resource.validateGrantFields( "role", "Authenticated", "nonsense", "*", "view" ) );
    }
}
