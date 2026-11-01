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

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Users;
import com.wikantik.auth.acl.AclDirectiveFixtures.Fixture;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.render.RenderingManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.Permission;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforcement does not depend on the order things happen to a page: for every fixture, on the page instance the page
 * manager hands out (shared, cached), the ACL is the same whether it is asked for alone, after a render, or before and
 * after several renders — and a render never changes the ACL already handed out. (Before #104 a render wrote the render
 * path's reading into the page's ACL, and the text scan's reading was accumulated into the same object: render-first
 * enforced one reading, scan-first the union of both.) And what is enforced is {@link AccessRules}' reading: a locked
 * page is for administrators only.
 */
class AccessRulesOrderIndependenceTest {

    private static final Logger LOG = LogManager.getLogger( AccessRulesOrderIndependenceTest.class );

    private static TestEngine engine;

    /** What every order must enforce: a lock where the design locks, the rule lines' ACL elsewhere. */
    private static final Set< String > LOCKED = Set.of( DefaultAclManager.LOCKED_PRINCIPAL.getName()
            + ":comment,delete,edit,modify,rename,upload,view" );

    /** The old fixtures (expected: their old ACL unless locked) and one page per shape. */
    static List< Fixture > all() {
        final List< Fixture > out = new java.util.ArrayList<>( AclDirectiveFixtures.ALL );
        out.addAll( AclDirectiveFixtures.ENFORCEMENT_REPROS );
        for ( final AclDirectiveFixtures.Shaped row : AclDirectiveFixtures.SHAPES ) {
            final Set< String > acl = row.shape() == AccessRules.Shape.CANONICAL ? Set.of( "Admin:view" ) : Set.of();
            out.add( new Fixture( "shape: " + row.name(), row.text(), acl, true ) );
        }
        return out;
    }

    private static Set< String > expected( final Fixture f ) {
        final String shapeName = f.name().startsWith( "shape: " ) ? f.name().substring( 7 ) : null;
        if ( shapeName != null ) {
            final AccessRules.Shape shape = AclDirectiveFixtures.SHAPES.stream().filter( r -> r.name().equals( shapeName ) )
                    .findFirst().orElseThrow().shape();
            return shape == null || shape == AccessRules.Shape.CANONICAL ? f.acl() : LOCKED;
        }
        return AclDirectiveFixtures.EXPECTED_LOCKS.containsKey( f.name() ) ? LOCKED : f.acl();
    }

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        for ( final Fixture f : all() ) {
            for ( final String order : ORDERS ) {
                engine.saveText( pageName( f, order ), f.text() );
            }
        }
        engine.saveText( LOCKED_PAGE, AclDirectiveFixtures.DENY.text() );
        engine.saveText( OPEN_PAGE, "Nothing restricts this page.\n" );
    }

    @AfterAll
    static void stop() {
        engine.deleteQuietly( all().stream().flatMap( f -> ORDERS.stream().map( o -> pageName( f, o ) ) ).toArray( String[]::new ) );
        engine.deleteQuietly( LOCKED_PAGE, OPEN_PAGE );
        engine.stop();
    }

    private static final String LOCKED_PAGE = "AclLockedEnforcement";
    private static final String OPEN_PAGE = "AclOpenEnforcement";

    private static boolean permitted( final Session session, final String pageName, final String action ) {
        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        return engine.getManager( AuthorizationManager.class ).isPermitted( session, PermissionFactory.getPagePermission( page, action ) );
    }

    @Test
    void aLockedPageIsForAdministratorsOnly() throws Exception {
        // Each session is made just before it is used: the test sessions share one mock HTTP session.
        final Session anonymous = WikiSessionTest.anonymousSession( engine );
        assertTrue( permitted( anonymous, OPEN_PAGE, "view" ), "control: anonymous views an open page" );
        assertFalse( permitted( anonymous, LOCKED_PAGE, "view" ), "anonymous view" );
        assertFalse( permitted( anonymous, LOCKED_PAGE, "edit" ), "anonymous edit" );

        final Session editor = WikiSessionTest.authenticatedSession( engine, Users.JANNE, Users.JANNE_PASS );
        assertTrue( permitted( editor, OPEN_PAGE, "view" ) && permitted( editor, OPEN_PAGE, "edit" ),
                "control: the editor edits an open page" );
        assertFalse( permitted( editor, LOCKED_PAGE, "view" ), "editor view" );
        assertFalse( permitted( editor, LOCKED_PAGE, "edit" ), "editor edit" );
        assertEquals( Set.of( OPEN_PAGE ), engine.getManager( AuthorizationManager.class )
                .filterViewable( editor, List.of( OPEN_PAGE, LOCKED_PAGE ) ), "a locked page is filtered out of listings" );

        final Session admin = WikiSessionTest.adminSession( engine );
        assertTrue( permitted( admin, LOCKED_PAGE, "view" ), "admin view" );
        assertTrue( permitted( admin, LOCKED_PAGE, "edit" ), "admin edit" );
    }

    /** One stored page per order, so no order sees another's cached reading. */
    private static final List< String > ORDERS = List.of( "Alone", "RenderFirst", "Around" );

    private static String pageName( final Fixture f, final String order ) {
        return "AclOrder" + order + all().indexOf( f );
    }

    /**
     * A new instance of the stored page — same name, version and modification time, no ACL of its own: what the page
     * cache hands out after its TTL, and what each request may hold while a render and a permission check run on it.
     */
    private static Page page( final Fixture f, final String order ) {
        final Page stored = engine.getManager( PageManager.class ).getPageWithoutMetadata( pageName( f, order ), PageProvider.LATEST_VERSION );
        final Page fresh = Wiki.contents().page( engine, stored.getName() );
        fresh.setVersion( stored.getVersion() );
        fresh.setLastModified( stored.getLastModified() );
        return fresh;
    }

    private static AclManager acls() {
        return engine.getManager( AclManager.class );
    }

    private static void render( final Fixture f, final Page page ) {
        try {
            engine.getManager( RenderingManager.class ).textToHTML( Wiki.context().create( engine, page ), f.text() );
        } catch ( final RuntimeException e ) {
            LOG.info( "rendering fixture '{}' threw, as it does on this text: {}", f.name(), e.toString() );
        }
    }

    static Set< String > pairs( final Acl acl ) {
        final Set< String > out = new HashSet<>();
        if ( acl == null ) {
            return out;
        }
        for ( final Enumeration< AclEntry > entries = acl.aclEntries(); entries.hasMoreElements(); ) {
            final AclEntry entry = entries.nextElement();
            for ( final Enumeration< Permission > perms = entry.permissions(); perms.hasMoreElements(); ) {
                out.add( entry.getPrincipal().getName() + ":" + perms.nextElement().getActions() );
            }
        }
        return out;
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "all" )
    void theAclIsTheSameInEveryOrder( final Fixture f ) {
        final Set< String > alone = pairs( acls().getPermissions( page( f, "Alone" ) ) );

        final Page renderedFirst = page( f, "RenderFirst" );
        render( f, renderedFirst );
        final Set< String > afterRender = pairs( acls().getPermissions( renderedFirst ) );

        final Page around = page( f, "Around" );
        final Acl before = acls().getPermissions( around );
        final Set< String > beforePairs = pairs( before );
        for ( int i = 0; i < 3; i++ ) {
            render( f, around );
        }
        final Acl after = acls().getPermissions( around );

        assertEquals( expected( f ), alone, "check alone" );
        assertEquals( alone, afterRender, "render, then check" );
        assertEquals( alone, beforePairs, "check, renders, check: the first check" );
        assertEquals( alone, pairs( after ), "check, renders, check: the second check" );
        assertSame( before, after, "the ACL handed out is the cached one, not a render's" );
        assertEquals( beforePairs, pairs( before ), "a render does not change the ACL already handed out" );
        assertEquals( alone, pairs( acls().getPermissions( page( f, "Around" ) ) ), "a fresh instance of the same version" );
    }
}
