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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.Permission;
import java.util.PropertyPermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the allow/deny table of the permission types: which grant implies which request. These are the leaves every
 * authorization decision rests on, so each row is a security statement.
 */
class PermissionImplicationMatrixTest {

    // ---- PagePermission action hierarchy ------------------------------------------------------------------------

    @ParameterizedTest( name = "granting {0} on a page {2} {1}" )
    @CsvSource( {
        // delete = modify + edit + upload + comment + view
        "delete, delete,  implies", "delete, modify,  implies", "delete, edit,    implies", "delete, upload,  implies",
        "delete, comment, implies", "delete, view,    implies", "delete, rename,  does not imply",
        // rename = edit + comment + view
        "rename, rename,  implies", "rename, edit,    implies", "rename, comment, implies", "rename, view,    implies",
        "rename, delete,  does not imply", "rename, modify, does not imply", "rename, upload, does not imply",
        // modify = edit + upload + comment + view
        "modify, modify,  implies", "modify, edit,    implies", "modify, upload,  implies", "modify, comment, implies",
        "modify, view,    implies", "modify, delete,  does not imply", "modify, rename,  does not imply",
        // edit = comment + view
        "edit,   edit,    implies", "edit,   comment, implies", "edit,   view,    implies",
        "edit,   upload,  does not imply", "edit,   modify,  does not imply", "edit,   delete,  does not imply",
        // comment = view
        "comment, comment, implies", "comment, view,   implies", "comment, edit,   does not imply",
        "comment, upload, does not imply",
        // upload = view
        "upload, upload,  implies", "upload, view,    implies", "upload, comment, does not imply",
        "upload, edit,    does not imply",
        // view = view
        "view,   view,    implies", "view,   comment, does not imply", "view,   upload,  does not imply",
        "view,   edit,    does not imply",
    } )
    void aPageGrantImpliesExactlyItsActionHierarchy( final String granted, final String asked, final String verdict ) {
        final boolean expected = "implies".equals( verdict.trim() );

        assertEquals( expected,
                new PagePermission( "w:Page", granted.trim() ).implies( new PagePermission( "w:Page", asked.trim() ) ) );
    }

    @Test
    void aMultiActionPageGrantImpliesTheUnionOfItsActions() {
        final PagePermission grant = new PagePermission( "w:Page", "view,upload" );

        assertTrue( grant.implies( new PagePermission( "w:Page", "view,upload" ) ) );
        assertFalse( grant.implies( new PagePermission( "w:Page", "view,comment" ) ),
                "one unmet action in the request denies the whole request" );
    }

    // ---- PagePermission targets (wiki and page, with wildcards) -------------------------------------------------

    @ParameterizedTest( name = "grant on {0} vs request on {1} -> {2}" )
    @CsvSource( {
        "w:Page,       w:Page,       true",
        "w:*,          w:Anything,   true",
        "*:*,          other:Page,   true",
        "w:Page,       w:Other,      false",
        "w:Page,       x:Page,       false",
        "w:*,          x:Page,       false",
        "w:Foo*,       w:FooBar,     true",
        "w:Foo*,       w:Foo,        true",
        "w:Foo*,       w:BarFoo,     false",
        "w:*Bar,       w:FooBar,     true",
        "w:*Bar,       w:BarFoo,     false",
        "w:*Bar,       w:Bar,        true",
        "w:Foo,        w:FooBar,     false",
    } )
    void aPageGrantCoversATargetOnlyByEqualityOrWildcard( final String grant, final String request, final boolean expected ) {
        assertEquals( expected,
                new PagePermission( grant.trim(), "view" ).implies( new PagePermission( request.trim(), "view" ) ) );
    }

    @Test
    void aPagePermissionImpliesNoOtherPermissionType() {
        final PagePermission everything = new PagePermission( "*:*", "delete" );

        assertFalse( everything.implies( new WikiPermission( "w", "login" ) ) );
        assertFalse( everything.implies( new GroupPermission( "w:Team", "view" ) ) );
        assertFalse( everything.implies( new AdminPermission( "w:users", "read" ) ) );
        assertFalse( everything.implies( new AllPermission( "w" ) ) );
        assertFalse( everything.implies( new PropertyPermission( "user.home", "read" ) ) );
    }

    @Test
    void pagePermissionsAreEqualOnlyWhenWikiPageAndActionsMatchAndHashAccordingly() {
        final PagePermission a = new PagePermission( "w:Page", "view,edit" );

        assertEquals( a, new PagePermission( "w:Page", "edit,view" ) );
        assertEquals( a.hashCode(), new PagePermission( "w:Page", "edit,view" ).hashCode() );
        assertNotEquals( a, new PagePermission( "w:Page", "view" ) );
        assertNotEquals( a, new PagePermission( "w:Other", "view,edit" ) );
        assertNotEquals( a, new PagePermission( "x:Page", "view,edit" ) );
        assertNotEquals( a, new WikiPermission( "w", "login" ) );
        assertNotEquals( a.hashCode(), new PagePermission( "w:Page", "view" ).hashCode() );
    }

    @Test
    void aPagePermissionRefusesEmptyActions() {
        assertThrows( IllegalArgumentException.class, () -> new PagePermission( "w:Page", "" ) );
    }

    // ---- GroupPermission -----------------------------------------------------------------------------------------

    @ParameterizedTest( name = "group grant {0} vs request {1} -> {2}" )
    @CsvSource( {
        "delete, delete, true", "delete, edit,   true", "delete, view,   true",
        "edit,   edit,   true", "edit,   view,   true", "edit,   delete, false",
        "view,   view,   true", "view,   edit,   false", "view,   delete, false",
    } )
    void aGroupGrantImpliesItsActionHierarchy( final String granted, final String asked, final boolean expected ) {
        assertEquals( expected,
                new GroupPermission( "w:Team", granted.trim() ).implies( new GroupPermission( "w:Team", asked.trim() ) ) );
    }

    @Test
    void aGroupGrantNeedsTheWikiAndTheGroupToMatch() {
        final GroupPermission grant = new GroupPermission( "w:Team", "view" );

        assertTrue( grant.implies( new GroupPermission( "w:Team", "view" ) ) );
        assertFalse( grant.implies( new GroupPermission( "w:Other", "view" ) ), "same wiki, different group" );
        assertFalse( grant.implies( new GroupPermission( "x:Team", "view" ) ), "same group, different wiki" );
        assertTrue( new GroupPermission( "w:*", "view" ).implies( new GroupPermission( "w:Other", "view" ) ) );
        assertFalse( new GroupPermission( "w:*", "view" ).implies( new GroupPermission( "x:Other", "view" ) ),
                "a wildcard group does not widen the wiki" );
        assertTrue( new GroupPermission( "*:*", "view" ).implies( new GroupPermission( "x:Other", "view" ) ) );
    }

    @Test
    void aGroupPermissionImpliesNoOtherPermissionType() {
        assertFalse( new GroupPermission( "*:*", "delete" ).implies( new PagePermission( "w:Page", "view" ) ) );
        assertFalse( new GroupPermission( "*:*", "delete" ).implies( new WikiPermission( "w", "login" ) ) );
    }

    @Test
    void theGroupMemberTokenIsNeverImpliedByAnOrdinaryGroupGrantNorImpliesWithoutASubject() {
        final GroupPermission memberGrant = new GroupPermission( "w:<groupmember>", "view" );

        assertFalse( new GroupPermission( "w:Team", "view" ).implies( memberGrant ),
                "a named group does not cover the member token" );
        assertTrue( new GroupPermission( "w:*", "view" ).implies( memberGrant ) == false
                        || new GroupPermission( "w:*", "view" ).implies( memberGrant ),
                "wildcard coverage of the token is whatever isSubset says; the call must not throw" );
        assertFalse( memberGrant.implies( new GroupPermission( "w:Team", "view" ) ),
                "outside a Subject context the member token implies nothing" );
        assertTrue( memberGrant.implies( new GroupPermission( "w:<groupmember>", "view" ) ) );
    }

    @Test
    void groupPermissionsAreEqualOnlyWhenWikiGroupAndActionsMatch() {
        final GroupPermission a = new GroupPermission( "w:Team", "view,edit" );

        assertEquals( a, new GroupPermission( "w:Team", "edit,view" ) );
        assertEquals( a.hashCode(), new GroupPermission( "w:Team", "edit,view" ).hashCode() );
        assertNotEquals( a, new GroupPermission( "w:Team", "view" ) );
        assertNotEquals( a, new GroupPermission( "w:Other", "view,edit" ) );
        assertNotEquals( a.hashCode(), new GroupPermission( "w:Team", "view" ).hashCode() );
        assertNotEquals( a, new PagePermission( "w:Team", "view,edit" ) );
    }

    // ---- AdminPermission -----------------------------------------------------------------------------------------

    @Test
    void anAdminGrantCoversItsAreaAndActionsOnly() {
        final AdminPermission grant = new AdminPermission( "w:users", "read,write" );

        assertTrue( grant.implies( new AdminPermission( "w:users", "read" ) ) );
        assertTrue( grant.implies( new AdminPermission( "w:USERS", "write,read" ) ), "areas compare case-insensitively" );
        assertFalse( grant.implies( new AdminPermission( "w:users", "delete" ) ), "an action the grant lacks" );
        assertFalse( grant.implies( new AdminPermission( "w:groups", "read" ) ), "another area" );
        assertFalse( grant.implies( new AdminPermission( "x:users", "read" ) ), "another wiki" );
        assertFalse( grant.implies( new PagePermission( "w:users", "view" ) ), "another permission type" );
    }

    @Test
    void anAdminWildcardAreaOrActionCoversEverythingInTheWiki() {
        assertTrue( new AdminPermission( "w:*", "read" ).implies( new AdminPermission( "w:groups", "read" ) ) );
        assertFalse( new AdminPermission( "w:*", "read" ).implies( new AdminPermission( "w:groups", "write" ) ) );
        assertTrue( new AdminPermission( "w:users", "*" ).implies( new AdminPermission( "w:users", "anything" ) ) );
        assertFalse( new AdminPermission( "w:users", "*" ).implies( new AdminPermission( "w:groups", "anything" ) ) );
        assertTrue( new AdminPermission( "users", "read" ).implies( new AdminPermission( "x:users", "read" ) ),
                "a bare target means every wiki" );
    }

    @Test
    void adminPermissionsAreEqualOnlyWhenWikiAreaAndActionsMatch() {
        final AdminPermission a = new AdminPermission( "w:users", "read,write" );

        assertEquals( a, new AdminPermission( "w:users", "write,read" ) );
        assertEquals( a.hashCode(), new AdminPermission( "w:users", "write,read" ).hashCode() );
        assertNotEquals( a, new AdminPermission( "w:users", "read" ), "actions" );
        assertNotEquals( a, new AdminPermission( "w:groups", "read,write" ), "area" );
        assertNotEquals( a, new AdminPermission( "x:users", "read,write" ), "wiki" );
        assertNotEquals( a, new PagePermission( "w:users", "view" ) );
        assertNotEquals( a.hashCode(), new AdminPermission( "w:groups", "read,write" ).hashCode() );
        assertNotEquals( a.hashCode(), new AdminPermission( "x:users", "read,write" ).hashCode() );
        assertNotEquals( a.hashCode(), new AdminPermission( "w:users", "read" ).hashCode() );
        assertEquals( "read,write", a.getActions() );
        assertTrue( a.toString().contains( "w:users" ) && a.toString().contains( "read,write" ), a.toString() );
    }

    @Test
    void anAdminTargetWithAnEmptyWikiPartMatchesNoWikiAtAll() {
        final AdminPermission noWiki = new AdminPermission( ":users", "read" );

        assertNull( noWiki.getWiki() );
        assertFalse( noWiki.implies( new AdminPermission( "w:users", "read" ) ) );
    }

    // ---- AllPermission -------------------------------------------------------------------------------------------

    @Test
    void allPermissionCoversEveryWikiPermissionTypeInItsOwnWikiOnly() {
        final AllPermission all = new AllPermission( "w" );
        final Permission[] inWiki = {
            new PagePermission( "w:Page", "delete" ), new WikiPermission( "w", "createGroups" ),
            new GroupPermission( "w:Team", "delete" ), new AdminPermission( "w:users", "read" ),
            new AllPermission( "w" ) };
        final Permission[] elsewhere = {
            new PagePermission( "x:Page", "view" ), new WikiPermission( "x", "login" ),
            new GroupPermission( "x:Team", "view" ), new AdminPermission( "x:users", "read" ),
            new AllPermission( "x" ) };

        for( final Permission p : inWiki ) {
            assertTrue( all.implies( p ), "should imply " + p );
        }
        for( final Permission p : elsewhere ) {
            assertFalse( all.implies( p ), "must not imply " + p );
        }
        assertFalse( all.implies( new PropertyPermission( "user.home", "read" ) ), "not a wiki permission" );
    }

    @Test
    void aWildcardAllPermissionCoversEveryWiki() {
        final AllPermission everywhere = new AllPermission( null );

        assertTrue( everywhere.implies( new PagePermission( "x:Page", "view" ) ) );
        assertTrue( everywhere.implies( new AdminPermission( "x:users", "read" ) ) );
        assertEquals( "*", everywhere.getWiki() );
        assertEquals( new AllPermission( "*" ), everywhere );
    }

    @Test
    void allPermissionEqualityAndHashFollowTheWikiAndItHasNoActions() {
        assertEquals( new AllPermission( "w" ), new AllPermission( "w" ) );
        assertEquals( new AllPermission( "w" ).hashCode(), new AllPermission( "w" ).hashCode() );
        assertEquals( "w".hashCode(), new AllPermission( "w" ).hashCode() );
        assertNotEquals( new AllPermission( "w" ), new AllPermission( "x" ) );
        assertNotEquals( new AllPermission( "w" ), new PagePermission( "w:*", "delete" ) );
        assertNull( new AllPermission( "w" ).getActions() );
    }

    // ---- AllPermissionCollection ---------------------------------------------------------------------------------

    @Test
    void theCollectionImpliesOnlyWhatAStoredPermissionImpliesAndOnlyOnceNonEmpty() {
        final java.security.PermissionCollection empty = new AllPermission( "w" ).newPermissionCollection();
        assertFalse( empty.implies( new PagePermission( "w:Page", "view" ) ), "nothing stored, nothing implied" );

        final java.security.PermissionCollection pc = new PagePermission( "w:*", "view" ).newPermissionCollection();
        pc.add( new PagePermission( "w:Open*", "view" ) );
        pc.add( new WikiPermission( "w", "login" ) );

        assertTrue( pc.implies( new PagePermission( "w:OpenDoor", "view" ) ) );
        assertFalse( pc.implies( new PagePermission( "w:ClosedDoor", "view" ) ) );
        assertTrue( pc.implies( new WikiPermission( "w", "login" ) ) );
        assertFalse( pc.implies( new PropertyPermission( "user.home", "read" ) ) );
        assertEquals( 2, java.util.Collections.list( pc.elements() ).size() );
    }

    @Test
    void theCollectionRefusesForeignPermissionsAndAdditionsOnceReadOnly() {
        final java.security.PermissionCollection pc = new AllPermission( "w" ).newPermissionCollection();

        assertThrows( IllegalArgumentException.class, () -> pc.add( new PropertyPermission( "user.home", "read" ) ) );
        pc.add( new AllPermission( "w" ) );
        pc.setReadOnly();
        assertThrows( SecurityException.class, () -> pc.add( new AllPermission( "x" ) ) );
        assertTrue( pc.implies( new PagePermission( "w:Page", "delete" ) ) );
    }

    // ---- WikiPermission ------------------------------------------------------------------------------------------

    @Test
    void wikiPermissionsAreEqualOnlyForTheSameWikiAndActions() {
        final WikiPermission a = new WikiPermission( "w", "createPages,login" );

        assertEquals( a, new WikiPermission( "w", "login,createPages" ) );
        assertEquals( a.hashCode(), new WikiPermission( "w", "login,createPages" ).hashCode() );
        assertNotEquals( a, new WikiPermission( "x", "createPages,login" ) );
        assertNotEquals( a, new WikiPermission( "w", "login" ) );
        assertNotEquals( a, new PagePermission( "w:*", "view" ) );
        assertNotEquals( a.hashCode(), new WikiPermission( "x", "createPages,login" ).hashCode() );
        assertNotEquals( a.hashCode(), new WikiPermission( "w", "login" ).hashCode() );
    }

    @Test
    void theWikiPermissionTypeIsOneOfTheWikisOwn() {
        assertTrue( PermissionChecks.isJSPWikiPermission( new WikiPermission( "w", "login" ) ) );
        assertTrue( PermissionChecks.isJSPWikiPermission( new PagePermission( "w:p", "view" ) ) );
        assertTrue( PermissionChecks.isJSPWikiPermission( new GroupPermission( "w:g", "view" ) ) );
        assertTrue( PermissionChecks.isJSPWikiPermission( new AdminPermission( "w:a", "read" ) ) );
        assertTrue( PermissionChecks.isJSPWikiPermission( new AllPermission( "w" ) ) );
        assertFalse( PermissionChecks.isJSPWikiPermission( new PropertyPermission( "a", "read" ) ) );
        assertFalse( PermissionChecks.isJSPWikiPermission( null ) );
    }
}
