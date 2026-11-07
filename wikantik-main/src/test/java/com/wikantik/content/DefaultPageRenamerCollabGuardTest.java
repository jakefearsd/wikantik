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
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.adapter.CollabEngineAccess;
import com.wikantik.collab.adapter.CollabTestWiring;
import com.wikantik.collab.adapter.CollabWiring;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.testing.InMemoryCollabStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void aDraftCheckThatFailsDoesNotBlockTheRename() throws Exception {
        final CollabStore broken = mock( CollabStore.class );
        when( broken.sessionForPage( any() ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );
        wiring = CollabTestWiring.wire( engine, broken );
        engine.saveText( "GuardFrom", "the page to move\n" );

        assertEquals( "GuardTo", rename( "GuardFrom", "GuardTo" ) );

        assertNotNull( pm.getPage( "GuardTo" ) );
    }

    @Test
    void unwiredCollabIsANoOp() throws Exception {
        assertNull( CollabEngineAccess.registry( engine ) );
        engine.saveText( "GuardFrom", "the page to move\n" );

        assertEquals( "GuardTo", rename( "GuardFrom", "GuardTo" ) );

        assertNotNull( pm.getPage( "GuardTo" ) );
    }
}
