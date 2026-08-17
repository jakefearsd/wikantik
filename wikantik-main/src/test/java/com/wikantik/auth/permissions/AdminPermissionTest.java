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
package com.wikantik.auth.permissions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scoped access to one /admin/* functional area. */
class AdminPermissionTest {

    private static AdminPermission admin( final String target ) {
        return new AdminPermission( target, AdminPermission.ACCESS_ACTION );
    }

    /**
     * THE critical invariant of this whole change, and the reason it has a dedicated test.
     *
     * <p>{@code AllPermission.implies} returns false for any permission type
     * {@link PermissionChecks#isJSPWikiPermission} does not recognise. If AdminPermission ever
     * falls out of that allowlist, every administrator is denied the ENTIRE /admin/* surface — a
     * total lockout, produced by a one-line omission, in a class nobody would think to re-read.
     */
    @Test
    void allPermissionImpliesAdminPermission() {
        final AllPermission all = new AllPermission( "JSPWiki" );

        assertTrue( all.implies( new AdminPermission( "JSPWiki:insights", AdminPermission.ACCESS_ACTION ) ),
                "an administrator must still reach every admin area, or this change locks everyone out" );
        assertTrue( PermissionChecks.isJSPWikiPermission( admin( "insights" ) ),
                "AdminPermission must stay on the isJSPWikiPermission allowlist" );
    }

    @Test
    void impliesItself() {
        assertTrue( admin( "insights" ).implies( admin( "insights" ) ) );
    }

    /** The entire point: holding one area must not confer another. */
    @Test
    void oneAreaDoesNotImplyAnother() {
        assertFalse( admin( "insights" ).implies( admin( "users" ) ) );
        assertFalse( admin( "insights" ).implies( admin( "connector-credentials" ) ) );
        assertFalse( admin( "insights" ).implies( admin( "apikeys" ) ) );
        assertFalse( admin( "insights" ).implies( admin( "policy" ) ) );
    }

    @Test
    void wildcardAreaImpliesAnyArea() {
        assertTrue( admin( "*" ).implies( admin( "users" ) ) );
        assertTrue( admin( "*" ).implies( admin( "insights" ) ) );
    }

    /**
     * An admin area grant is not a wiki-wide capability. If it implied page or wiki permissions,
     * scoping the admin surface would quietly hand out content rights instead of restricting them.
     */
    @Test
    void doesNotImplyPageOrWikiPermissions() {
        assertFalse( admin( "*" ).implies( new PagePermission( "JSPWiki:Main", "view" ) ) );
        assertFalse( admin( "*" ).implies( new WikiPermission( "JSPWiki", "createPages" ) ) );
        assertFalse( admin( "*" ).implies( new AllPermission( "JSPWiki" ) ) );
    }

    @Test
    void actionsMustBeCovered() {
        final AdminPermission accessOnly = new AdminPermission( "insights", "access" );
        assertTrue( accessOnly.implies( new AdminPermission( "insights", "access" ) ) );
        assertFalse( accessOnly.implies( new AdminPermission( "insights", "purge" ) ),
                "a future verb must not be granted by an access-only grant" );
        assertTrue( new AdminPermission( "insights", "*" ).implies( new AdminPermission( "insights", "purge" ) ) );
    }

    @Test
    void areaAndActionsAreCaseInsensitive() {
        assertTrue( admin( "Insights" ).implies( admin( "insights" ) ) );
        assertEquals( "access", new AdminPermission( "insights", "ACCESS" ).getActions() );
    }

    @Test
    void parsesWikiQualifiedTarget() {
        final AdminPermission p = new AdminPermission( "JSPWiki:insights", "access" );
        assertEquals( "JSPWiki", p.getWiki() );
        assertEquals( "insights", p.getArea() );
    }

    @Test
    void unqualifiedTargetMatchesAnyWiki() {
        assertEquals( "*", admin( "insights" ).getWiki() );
    }

    @Test
    void equalityIgnoresConstructionOrderOfActions() {
        assertEquals( new AdminPermission( "insights", "access,purge" ),
                      new AdminPermission( "insights", "purge,access" ) );
        assertEquals( new AdminPermission( "insights", "access,purge" ).hashCode(),
                      new AdminPermission( "insights", "purge,access" ).hashCode() );
        assertNotEquals( admin( "insights" ), admin( "users" ) );
    }

    @Test
    void blankTargetIsRejected() {
        assertThrows( IllegalArgumentException.class, () -> new AdminPermission( "", "access" ) );
        assertThrows( IllegalArgumentException.class, () -> new AdminPermission( null, "access" ) );
    }
}
