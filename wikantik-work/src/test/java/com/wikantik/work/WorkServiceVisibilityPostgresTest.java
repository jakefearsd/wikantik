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
package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that a new {@link WorkService} method cannot skip project visibility: every public method is
 * classified, every read is driven reflectively as a non-member against a real, populated project.
 */
@RequiresPostgres
class WorkServiceVisibilityPostgresTest {

    /** Methods that change state. A new public method fails the classification test until it is listed here or below. */
    private static final Set< String > WRITES = Set.of( "createProject", "updateProject", "archiveProject",
        "putMember", "removeMember", "createProduct", "updateProduct", "archiveProduct", "createItem", "updateItem",
        "addComment", "editComment", "removeComment", "addLink", "removeLink" );

    /** Methods that only read. */
    private static final Set< String > READS = Set.of( "listProjects", "project", "members", "roleIn", "listProducts",
        "item", "items", "board", "itemSummaries", "boardSummaries", "revisions", "events", "activity", "waitingOn",
        "listProjectsWithRoles" );

    private static final List< String > STRING_FIXTURES = List.of( "PLAT", "PLAT-1" );

    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Actor lena = Actor.human( "lena", Set.of(), false );

    private WorkService service;
    private long commentId;
    private long linkId;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( "work_link", "work_mention", "work_comment", "work_event",
            "work_item_revision", "work_item", "work_member", "work_product", "work_project" );
        final JdbcWorkStore store = new JdbcWorkStore( PostgresTestDb.createDataSource() );
        final Project plat = store.createProject( "PLAT", "Platform", "secret roadmap" );
        store.putMember( plat.id(), "lena", "user", WorkRole.LEAD, "admin", Actor.Kind.HUMAN );
        store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED, DeployGate.HUMAN,
            null ) );
        final Item item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "secret item" ), "lena",
            Actor.Kind.HUMAN );
        // lena is also the assignee so that waitingOn is non-empty for her
        final Item assigned = new Item( item.id(), item.projectId(), item.key(), item.level(), null, null,
            item.kind(), item.title(), item.body(), item.status(), item.priority(), item.rank(), null, "lena",
            item.reporter(), null, null, null, item.version(), item.createdAt(), item.updatedAt(), null );
        store.updateItem( item, assigned, "lena", Actor.Kind.HUMAN, null );
        commentId = store.addComment( item, "lena", Actor.Kind.HUMAN, "secret comment", List.of() ).id();
        linkId = store.addLink( item, "url", "https://example.com/secret", "lena", Actor.Kind.HUMAN ).id();
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC() );
    }

    private static List< Method > publicMethods() {
        return Arrays.stream( WorkService.class.getDeclaredMethods() )
            .filter( m -> Modifier.isPublic( m.getModifiers() ) && !m.isSynthetic() ).toList();
    }

    @Test
    void everyPublicMethodIsClassifiedAsReadOrWrite() {
        final Set< String > all = new TreeSet<>();
        publicMethods().forEach( m -> all.add( m.getName() ) );
        assertTrue( Collections.disjoint( READS, WRITES ), "a method cannot be both a read and a write" );
        final Set< String > classified = new TreeSet<>( READS );
        classified.addAll( WRITES );
        assertEquals( classified, all, "classify every public WorkService method as a read or a write" );
    }

    /** Argument lists for a read: Actor first, then every combination of the String fixtures. */
    private static List< Object[] > argumentSets( final Method m, final Actor actor ) {
        List< Object[] > sets = new ArrayList<>();
        sets.add( new Object[ m.getParameterCount() ] );
        for ( int i = 0; i < m.getParameterCount(); i++ ) {
            final Class< ? > type = m.getParameterTypes()[ i ];
            final List< Object > options = type == Actor.class ? List.of( actor )
                : type == String.class ? new ArrayList<>( STRING_FIXTURES )
                : type == int.class ? List.of( 10 )
                : Arrays.asList( new Object[] { null } );
            final List< Object[] > next = new ArrayList<>();
            for ( final Object[] prefix : sets ) {
                for ( final Object option : options ) {
                    final Object[] copy = prefix.clone();
                    copy[ i ] = option;
                    next.add( copy );
                }
            }
            sets = next;
        }
        return sets;
    }

    private static Object invoke( final Method m, final Object target, final Object[] args ) throws Exception {
        try {
            return m.invoke( target, args );
        } catch ( final InvocationTargetException e ) {
            if ( e.getCause() instanceof Exception cause ) {
                throw cause;
            }
            throw e;
        }
    }

    private static boolean isEmptyResult( final Object result ) {
        return result instanceof Collection< ? > c && c.isEmpty();
    }

    @Test
    void everyReadMethodHidesInvisibleProject() throws Exception {
        for ( final Method m : publicMethods() ) {
            if ( !READS.contains( m.getName() ) ) {
                continue;
            }
            int provedAgainstRealData = 0;
            for ( final Object[] asLead : argumentSets( m, lena ) ) {
                final Object seen;
                try {
                    seen = invoke( m, service, asLead );
                } catch ( final WorkNotFoundException e ) {
                    continue;  // this fixture combination does not address the project; try the next
                }
                if ( seen == null || isEmptyResult( seen ) ) {
                    continue;
                }
                provedAgainstRealData++;
                final Object[] asStranger = asLead.clone();
                for ( int i = 0; i < asStranger.length; i++ ) {
                    if ( asStranger[ i ] instanceof Actor ) {
                        asStranger[ i ] = mallory;
                    }
                }
                try {
                    final Object result = invoke( m, service, asStranger );
                    assertTrue( isEmptyResult( result ), m.getName() + " leaked for a non-member: " + result );
                } catch ( final WorkNotFoundException expected ) {
                    // the required outcome
                }
            }
            assertTrue( provedAgainstRealData > 0,
                m.getName() + " never returned real data to the project lead, so it proved nothing" );
        }
    }

    @Test
    void everyProjectScopedWriteHidesInvisibleProject() {
        final ItemDraft draft = new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null,
            null, null );
        final ItemPatch patch = new ItemPatch( 1, java.util.Map.of( "title", "x" ), null );
        final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
            () -> service.updateProject( mallory, "PLAT", "n", "d", null ),
            () -> service.archiveProject( mallory, "PLAT" ),
            () -> service.putMember( mallory, "PLAT", "mallory", "user", WorkRole.LEAD ),
            () -> service.removeMember( mallory, "PLAT", "lena", "user" ),
            () -> service.createItem( mallory, draft ),
            () -> service.updateItem( mallory, "PLAT-1", patch ),
            () -> service.addComment( mallory, "PLAT-1", "hi" ),
            () -> service.editComment( mallory, commentId, "x" ),
            () -> service.removeComment( mallory, commentId ),
            () -> service.addLink( mallory, "PLAT-1", "url", "https://a.example" ),
            () -> service.removeLink( mallory, linkId ) );
        for ( final var attempt : attempts ) {
            assertThrows( WorkNotFoundException.class, attempt );
        }
    }

    @Test
    void theFixtureIsVisibleToItsLead() throws Exception {
        assertEquals( 1, service.listProjects( lena ).size() );
        assertFalse( service.waitingOn( lena ).isEmpty() );
        assertTrue( service.listProjects( mallory ).isEmpty() );
        assertTrue( service.listProducts( mallory ).isEmpty() );
        assertTrue( new HashSet<>( service.waitingOn( mallory ) ).isEmpty() );
    }
}
