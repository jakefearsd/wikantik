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
package com.wikantik.content;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.adapter.CollabEngineAccess;
import com.wikantik.collab.adapter.CollabTestWiring;
import com.wikantik.collab.adapter.CollabWiring;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.testing.InMemoryCollabStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MultiEdit Phase 2 Task 6, the rename guard (§0 Minor): the collaboration store holds one draft per page, so a page name
 * holding a draft cannot be a rename's target. Refused before any write; collaboration never blocks a rename otherwise.
 */
class DefaultPageRenamerCollabGuardTest {

    private TestEngine engine;
    private PageManager pm;
    private CollabWiring wiring;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        pm = engine.getManager( PageManager.class );
    }

    @AfterEach
    void tearDown() {
        if( wiring != null ) {
            wiring.close();
        }
        engine.deleteQuietly( "GuardFrom", "GuardDraft", "GuardTo" );
        engine.stop();
    }

    private String rename( final String from, final String to ) throws WikiException {
        final Context ctx = Wiki.context().create( engine, pm.getPage( from ) );
        return engine.getManager( PageRenamer.class ).renamePage( ctx, from, to, true );
    }

    @Test
    void renamingOntoAPageWithADraftIsRefusedBeforeAnyWrite() throws Exception {
        wiring = CollabTestWiring.wire( engine, new InMemoryCollabStore() );
        final CollabSessionRegistry registry = CollabEngineAccess.registry( engine );
        engine.saveText( "GuardFrom", "the page to move\n" );
        final int versionBefore = pm.getPage( "GuardFrom" ).getVersion();
        final Session admin = engine.adminSession();
        // a new page being drafted collaboratively under the name the rename wants
        assertInstanceOf( CollabSessionRegistry.Joined.class, registry.join( "GuardDraft",
            new JoinRequest( AuthorKeys.authorKeyFor( admin ), null, admin, "Admin", "draft text\n", null ) ) );
        assertTrue( registry.hasDraft( "GuardDraft" ) );

        final WikiException e = assertThrows( WikiException.class, () -> rename( "GuardFrom", "GuardDraft" ) );

        assertEquals( "Page 'GuardDraft' has an unpublished collaborative draft; publish or discard it first",
            e.getMessage() );
        assertNotNull( pm.getPage( "GuardFrom" ), "the page did not move" );
        assertEquals( versionBefore, pm.getPage( "GuardFrom" ).getVersion(), "nothing was written" );
        assertNull( pm.getPage( "GuardDraft" ), "no page under the draft's name" );
        assertEquals( "GuardDraft", registry.liveForPage( "GuardDraft" ).orElseThrow().pageName() );
    }

    @Test
    void aRenameOntoANameWithoutADraftProceeds() throws Exception {
        wiring = CollabTestWiring.wire( engine, new InMemoryCollabStore() );
        engine.saveText( "GuardFrom", "the page to move\n" );

        assertEquals( "GuardTo", rename( "GuardFrom", "GuardTo" ) );

        assertNull( pm.getPage( "GuardFrom" ) );
        assertNotNull( pm.getPage( "GuardTo" ) );
    }

    @Test
    void aStoreThatCannotTellRefusesTheRenameRatherThanRiskingADraft() throws Exception {
        final CollabStore broken = mock( CollabStore.class );
        when( broken.sessionForPage( any() ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );
        wiring = CollabTestWiring.wire( engine, broken );
        engine.saveText( "GuardFrom", "the page to move\n" );
        final int versionBefore = pm.getPage( "GuardFrom" ).getVersion();

        final WikiException e = assertThrows( WikiException.class, () -> rename( "GuardFrom", "GuardTo" ) );

        assertTrue( e.getMessage().contains( "collaboration store is unavailable" ), e.getMessage() );
        assertInstanceOf( IllegalStateException.class, e.getCause() );
        assertNotNull( pm.getPage( "GuardFrom" ) );
        assertEquals( versionBefore, pm.getPage( "GuardFrom" ).getVersion(), "nothing was written" );
        assertNull( pm.getPage( "GuardTo" ) );
    }

    /**
     * Ruling Q14 refined: collaboration ships disabled, and a disabled registry refuses joins, so no draft can appear in
     * the window. A store outage then never blocks a rename on a wiki that does not use collaboration.
     */
    @Test
    void aStoreThatCannotTellDoesNotBlockTheRenameWhileCollaborationIsDisabled() throws Exception {
        final CollabStore broken = mock( CollabStore.class );
        when( broken.sessionForPage( any() ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );
        wiring = CollabTestWiring.wire( engine, broken );
        CollabEngineAccess.registry( engine ).setEnabled( false );
        engine.saveText( "GuardFrom", "the page to move\n" );

        assertEquals( "GuardTo", rename( "GuardFrom", "GuardTo" ) );

        assertNull( pm.getPage( "GuardFrom" ) );
        assertNotNull( pm.getPage( "GuardTo" ) );
    }

    @Test
    void aLiveTargetDraftStillRefusesWhileCollaborationIsDisabled() throws Exception {
        wiring = CollabTestWiring.wire( engine, new InMemoryCollabStore() );
        final CollabSessionRegistry registry = CollabEngineAccess.registry( engine );
        engine.saveText( "GuardFrom", "the page to move\n" );
        final Session admin = engine.adminSession();
        assertInstanceOf( CollabSessionRegistry.Joined.class, registry.join( "GuardDraft",
            new JoinRequest( AuthorKeys.authorKeyFor( admin ), null, admin, "Admin", "their new page\n", null ) ) );
        registry.setEnabled( false );   // switched off with a session still live

        final WikiException e = assertThrows( WikiException.class, () -> rename( "GuardFrom", "GuardDraft" ) );

        assertEquals( "Page 'GuardDraft' has an unpublished collaborative draft; publish or discard it first",
            e.getMessage() );
        assertNotNull( pm.getPage( "GuardFrom" ) );
    }

    /**
     * A live new-page draft under the target while the store is down: the in-memory check refuses without the store.
     * Failing open here would let the moved page's body be merged into that draft as an outside change.
     */
    @Test
    void aLiveTargetDraftRefusesTheRenameEvenWhileTheStoreIsDown() throws Exception {
        final InMemoryCollabStore memory = new InMemoryCollabStore();
        final AtomicBoolean down = new AtomicBoolean();
        wiring = CollabTestWiring.wire( engine, failingLookups( memory, down ) );
        final CollabSessionRegistry registry = CollabEngineAccess.registry( engine );
        engine.saveText( "GuardFrom", "the page to move\n" );
        final Session admin = engine.adminSession();
        final CollabSessionRegistry.Joined draft = assertInstanceOf( CollabSessionRegistry.Joined.class,
            registry.join( "GuardDraft", new JoinRequest( AuthorKeys.authorKeyFor( admin ), null, admin, "Admin",
                "their new page\n", null ) ) );
        final String draftBefore = draft.session().view().doc();
        down.set( true );

        final WikiException e = assertThrows( WikiException.class, () -> rename( "GuardFrom", "GuardDraft" ) );

        assertEquals( "Page 'GuardDraft' has an unpublished collaborative draft; publish or discard it first",
            e.getMessage() );
        assertNotNull( pm.getPage( "GuardFrom" ) );
        assertNull( pm.getPage( "GuardDraft" ) );
        assertEquals( draftBefore, draft.session().view().doc(), "the draft holds only its own text" );
    }

    @Test
    void aFrozenDraftOnTheTargetRefusesTheRename() throws Exception {
        final InMemoryCollabStore memory = new InMemoryCollabStore();
        wiring = CollabTestWiring.wire( engine, memory );
        engine.saveText( "GuardFrom", "the page to move\n" );
        // a draft whose page was deleted: frozen for an administrator, stored, not live
        memory.createSession( CollabSession.newRecord( UUID.randomUUID(), "GuardDraft", Optional.empty(), "someone",
            Clock.systemUTC() ).withStatus( SessionRecord.FROZEN ) );
        assertTrue( CollabEngineAccess.registry( engine ).liveForPage( "GuardDraft" ).isEmpty() );

        final WikiException e = assertThrows( WikiException.class, () -> rename( "GuardFrom", "GuardDraft" ) );

        assertEquals( "Page 'GuardDraft' has an unpublished collaborative draft; publish or discard it first",
            e.getMessage() );
        assertNotNull( pm.getPage( "GuardFrom" ) );
    }

    @Test
    void aMissingSourceIsReportedBeforeTheDraftGuard() throws Exception {
        final CollabStore broken = mock( CollabStore.class );
        when( broken.sessionForPage( any() ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );
        wiring = CollabTestWiring.wire( engine, broken );
        final Context ctx = Wiki.context().create( engine, Wiki.contents().page( engine, "GuardNowhere" ) );

        final WikiException e = assertThrows( WikiException.class,
            () -> engine.getManager( PageRenamer.class ).renamePage( ctx, "GuardNowhere", "GuardTo", true ) );

        assertEquals( "No such page GuardNowhere", e.getMessage() );
    }

    /** {@code store} whose page lookups throw while {@code down} is set; every other call goes through. */
    private static CollabStore failingLookups( final CollabStore store, final AtomicBoolean down ) {
        return (CollabStore) Proxy.newProxyInstance( CollabStore.class.getClassLoader(), new Class< ? >[]{ CollabStore.class },
            ( proxy, method, args ) -> {
                if( down.get() && method.getName().equals( "sessionForPage" ) ) {
                    throw new IllegalStateException( "test: collab store down" );
                }
                try {
                    return method.invoke( store, args );
                } catch( final InvocationTargetException ite ) {
                    throw ite.getCause();
                }
            } );
    }

    @Test
    void unwiredCollabIsANoOp() throws Exception {
        assertNull( CollabEngineAccess.registry( engine ) );
        engine.saveText( "GuardFrom", "the page to move\n" );

        assertEquals( "GuardTo", rename( "GuardFrom", "GuardTo" ) );

        assertNotNull( pm.getPage( "GuardTo" ) );
    }
}
