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
package com.wikantik.auth.acl;

import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory ACL and its entries: who may be added, removed and found, and which permissions an entry holds. */
class AclContractTest {

    private static final PagePermission VIEW = new PagePermission( "wiki:Page", "view" );
    private static final PagePermission EDIT = new PagePermission( "wiki:Other", "edit" );

    private static AclEntryImpl entry( final String user, final PagePermission... perms ) {
        final AclEntryImpl e = new AclEntryImpl();
        e.setPrincipal( new WikiPrincipal( user ) );
        for( final PagePermission p : perms ) {
            e.addPermission( p );
        }
        return e;
    }

    // ---- AclEntryImpl ----------------------------------------------------------------------------------------------

    @Test
    void anEntryTakesPagePermissionsOnceAndRefusesDuplicatesAndOtherPermissionTypes() {
        final AclEntryImpl e = new AclEntryImpl();

        assertTrue( e.addPermission( VIEW ) );
        assertFalse( e.addPermission( new PagePermission( "wiki:Page", "view" ) ), "an equal permission is already there" );
        assertFalse( e.addPermission( new WikiPermission( "wiki", "login" ) ), "only page permissions belong in an ACL entry" );
        assertEquals( 1, Collections.list( e.permissions() ).size() );
    }

    @Test
    void removingAPermissionReportsWhetherItWasPresent() {
        final AclEntryImpl e = entry( "alice", VIEW, EDIT );

        assertTrue( e.removePermission( VIEW ) );
        assertFalse( e.checkPermission( VIEW ) );
        assertTrue( e.checkPermission( EDIT ) );
        assertFalse( e.removePermission( VIEW ), "already removed" );
        assertEquals( 1, Collections.list( e.permissions() ).size() );
    }

    @Test
    void anEntryPrincipalCanBeSetOnlyOnce() {
        final AclEntryImpl e = new AclEntryImpl();
        final Principal alice = new WikiPrincipal( "alice" );

        assertFalse( e.setPrincipal( null ), "null never sets" );
        assertNull( e.getPrincipal() );
        assertTrue( e.setPrincipal( alice ) );
        assertFalse( e.setPrincipal( new WikiPrincipal( "mallory" ) ), "an entry cannot be re-pointed at someone else" );
        assertSame( alice, e.getPrincipal() );
        assertFalse( e.setPrincipal( null ) );
        assertSame( alice, e.getPrincipal() );
    }

    @Test
    void anEntryToStringNamesThePrincipalAndEveryPermission() {
        final String text = entry( "alice", VIEW, EDIT ).toString();

        assertTrue( text.contains( "alice" ), text );
        assertTrue( text.contains( VIEW.toString() ), text );
        assertTrue( text.contains( EDIT.toString() ), text );
    }

    // ---- AclImpl ---------------------------------------------------------------------------------------------------

    @Test
    void anAclHoldsOneEntryPerPrincipalNameAndRefusesASecond() {
        final AclImpl acl = new AclImpl();
        final AclEntryImpl first = entry( "alice", VIEW );

        assertTrue( acl.isEmpty() );
        assertTrue( acl.addEntry( first ) );
        assertFalse( acl.isEmpty() );
        assertFalse( acl.addEntry( entry( "alice", EDIT ) ), "a second entry for the same principal is refused" );
        assertTrue( acl.addEntry( entry( "bob", EDIT ) ) );
        assertEquals( 2, Collections.list( acl.aclEntries() ).size() );
        assertSame( first, acl.getAclEntry( new WikiPrincipal( "alice" ) ) );
        assertTrue( acl.getAclEntry( new WikiPrincipal( "alice" ) ).checkPermission( VIEW ),
                "the refused entry's permissions did not leak into the first" );
        assertFalse( acl.getAclEntry( new WikiPrincipal( "alice" ) ).checkPermission( EDIT ) );
    }

    @Test
    void anEntryWithoutAPrincipalCannotBeAdded() {
        final AclImpl acl = new AclImpl();

        assertThrows( IllegalArgumentException.class, () -> acl.addEntry( new AclEntryImpl() ) );
        assertTrue( acl.isEmpty() );
    }

    @Test
    void removingAnEntryReportsWhetherItWasThere() {
        final AclImpl acl = new AclImpl();
        final AclEntryImpl alice = entry( "alice", VIEW );
        acl.addEntry( alice );

        assertFalse( acl.removeEntry( entry( "bob", VIEW ) ) );
        assertTrue( acl.removeEntry( alice ) );
        assertTrue( acl.isEmpty() );
        assertFalse( acl.removeEntry( alice ) );
        assertNull( acl.getAclEntry( new WikiPrincipal( "alice" ) ) );
    }

    @Test
    void findPrincipalsListsExactlyThePrincipalsWhoseEntriesImplyThePermission() {
        final AclImpl acl = new AclImpl();
        acl.addEntry( entry( "alice", VIEW ) );
        acl.addEntry( entry( "bob", EDIT ) );
        acl.addEntry( entry( "carol", VIEW, EDIT ) );

        final Set< String > viewers = java.util.Arrays.stream( acl.findPrincipals( VIEW ) )
                .map( Principal::getName ).collect( Collectors.toSet() );
        assertEquals( Set.of( "alice", "carol" ), viewers );
        assertArrayEquals( new Principal[ 0 ], acl.findPrincipals( new PagePermission( "wiki:Page", "delete" ) ) );
    }

    @Test
    void anAclToStringListsEachPrincipalWithItsPermissions() {
        final AclImpl acl = new AclImpl();
        acl.addEntry( entry( "alice", VIEW ) );
        acl.addEntry( entry( "bob", EDIT ) );

        final String text = acl.toString();

        assertTrue( text.contains( "user = alice: (" + VIEW + ")" ), text );
        assertTrue( text.contains( "user = bob: (" + EDIT + ")" ), text );
        assertEquals( 2, text.lines().count() );
    }
}
