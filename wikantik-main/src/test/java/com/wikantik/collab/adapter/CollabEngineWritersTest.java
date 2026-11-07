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
package com.wikantik.collab.adapter;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.authorize.Role;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.PullWaiter;
import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.content.BlockIdService;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.pagegraph.spine.ClusterRenameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * MultiEdit Phase 2 Task 6 (§13.2, A1, A5): the engine glue. A real engine, the collaboration registry wired over an
 * in-memory store and the real adapters, its page event listener registered on the engine's event sources. Every
 * writer that is not the collaborative editor is merged into the live draft as one server update with an
 * {@code external-change} event; the session's own publish is not; a rename racing a publish lands on the new name; a
 * page delete freezes the draft.
 */
class CollabEngineWritersTest {

    private static final String PAGE = "CollabEngW";
    private static final String BODY = "hello\nworld\n";

    private TestEngine engine;
    private PageManager pm;
    private InMemoryCollabStore store;
    private CollabWiring wiring;
    private CollabSessionRegistry registry;
    private Session admin;
    private String key;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build( TestEngine.getTestProperties( "/wikantik-vers-custom.properties" ) );
        pm = engine.getManager( PageManager.class );
        store = new InMemoryCollabStore();
        wiring = CollabTestWiring.wire( engine, store );
        registry = CollabEngineAccess.registry( engine );
        admin = engine.adminSession();
        key = AuthorKeys.authorKeyFor( admin );
    }

    @AfterEach
    void tearDown() {
        wiring.close();
        engine.deleteQuietly( PAGE, "CollabEngWNew", "CollabEngWDel" );
        engine.stop();
    }

    private Joined join( final String page ) {
        return assertInstanceOf( Joined.class, registry.join( page,
            new JoinRequest( key, null, admin, "Admin", null, null ) ) );
    }

    private PushResponse type( final Joined j, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), key, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    private PublishResponse publish( final Joined j ) {
        return j.session().publish( j.response().participantId(), key, j.session().view().head(), "" );
    }

    private long serverUpdates( final Joined j ) {
        return store.updatesSince( j.response().sessionId(), 0 ).stream()
            .filter( u -> StoredUpdate.SERVER.equals( u.kind() ) ).count();
    }

    /** Every event of the session so far, read as a pull from the start would. */
    private List< CollabEvent > events( final Joined j, final String type ) {
        final List< PullResponse > got = new ArrayList<>();
        final PullWaiter waiter = got::add;
        final PullResponse now = j.session().pullOrPark( j.response().participantId(), key,
            new PullCursor( 0, 0, 0, 0 ), null, waiter );
        final PullResponse r = now != null ? now : got.get( 0 );
        return r.events().stream().filter( e -> e.type().equals( type ) ).toList();
    }

    private String pageBody( final String page ) throws Exception {
        return CollabText.toLf( pm.getPureText( page, -1 ) );
    }

    private void assertMergedOnce( final Joined j, final String expectedFragment ) throws Exception {
        assertTrue( j.session().view().doc().contains( expectedFragment ), j.session().view().doc() );
        assertEquals( 1, serverUpdates( j ), "one server update" );
        assertEquals( 1, events( j, "external-change" ).size(), "one external-change event" );
        assertEquals( j.session().view().doc(), j.session().view().baseBody(), "nothing of the draft's own pending" );
        assertEquals( pageBody( PAGE ), j.session().view().doc(), "the draft follows the page" );
    }

    // ---- §13.2: each non-editor writer --------------------------------------------------------------------------

    @Test
    void theRestAndMcpSaveIsMergedIntoTheLiveDraft() throws Exception {
        engine.saveText( PAGE, BODY );
        final Joined a = join( PAGE );
        new PageSaveHelper( engine, pm ).saveTracked( PAGE, BODY + "from the api\n",
            SaveOptions.builder().author( "Bot" ).build() );
        assertMergedOnce( a, "from the api" );
    }

    @Test
    void aClusterRenameIsMergedIntoTheLiveDraft() throws Exception {
        engine.saveText( PAGE, "---\ncluster: alpha\n---\n" + BODY );
        final Joined a = join( PAGE );
        assertEquals( "alpha", a.session().view().meta().get( "cluster" ) );
        final StructuralIndexService index = mock( StructuralIndexService.class );
        when( index.listPagesByFilter( any( StructuralFilter.class ) ) ).thenReturn( List.of(
            new PageDescriptor( "01HAA0000000000000000000AA", PAGE, PAGE, PageType.ARTICLE, "alpha", List.of(), null,
                Instant.now(), Optional.empty(), false ) ) );
        new ClusterRenameService( pm, index, new PageSaveHelper( engine, pm ) ).apply( "alpha", "beta", "Bot" );

        assertEquals( "beta", a.session().view().meta().get( "cluster" ), "the meta change merged" );
        assertEquals( BODY, a.session().view().doc() );
        assertEquals( 1, events( a, "external-change" ).size() );
    }

    @Test
    void aBlockIdAssignmentIsMergedIntoTheLiveDraft() throws Exception {
        engine.saveText( PAGE, "First paragraph.\n\nSecond paragraph.\n" );
        final Joined a = join( PAGE );
        final BlockIdService.Assigned id = new BlockIdService( pm, new PageSaveHelper( engine, pm ) )
            .assign( PAGE, pm.getPage( PAGE ).getVersion(), 0, null, "Bot" );
        assertMergedOnce( a, "^" + id.id() );
    }

    @Test
    void anUnfilteredWriteAnnouncedAsTheWatcherDoesIsMergedIntoTheLiveDraft() throws Exception {
        engine.saveText( PAGE, BODY );
        final Joined a = join( PAGE );
        pm.putPageText( pm.getPage( PAGE ), BODY + "edited on disk\n" );
        WikiEventManager.fireEvent( engine.getManager( FilterManager.class ),
            new WikiPageEvent( engine, WikiPageEvent.POST_SAVE_BEGIN, PAGE ) );
        assertMergedOnce( a, "edited on disk" );
    }

    @Test
    void anAclRewriteIsMergedIntoTheLiveDraft() throws Exception {
        engine.saveText( PAGE, BODY );
        final Joined a = join( PAGE );
        final Page page = pm.getPage( PAGE );
        final Acl acl = Wiki.acls().acl();
        final AclEntry entry = Wiki.acls().entry();
        // the participant keeps edit, so the A5 re-check after the merge does not evict it
        entry.setPrincipal( Role.ALL );
        entry.addPermission( PermissionFactory.getPagePermission( page, "edit" ) );
        acl.addEntry( entry );
        engine.getManager( AclManager.class ).setPermissions( page, acl );
        assertMergedOnce( a, "ALLOW edit All" );
    }

    @Test
    void ownPublishDoesNotProduceAServerUpdate() throws Exception {
        engine.saveText( PAGE, BODY );
        final Joined a = join( PAGE );
        assertEquals( 200, type( a, "mine " ).status() );

        final PublishResponse r = publish( a );

        assertEquals( 200, r.status() );
        assertEquals( "mine " + BODY, pageBody( PAGE ) );
        assertEquals( 0, serverUpdates( a ), "the publish's own save events merge nothing" );
        assertTrue( events( a, "external-change" ).isEmpty() );
        assertEquals( 1, events( a, "published" ).size() );
    }

    /**
     * Follow-ups 3 and 4 through the real adapter: the save lands, a save filter rewrites the body, and reading the text
     * back fails once. The publish still finishes with the rewrite adopted, and nothing is duplicated.
     */
    @Test
    void aPublishWhoseReadBackFailsAfterAFilterRewriteFinishesWithoutDuplicating() throws Exception {
        engine.saveText( PAGE, BODY );
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                return PAGE.equals( context.getPage().getName() ) ? content + "signed\n" : content;
            }
        }, 100 );
        wiring.close();
        // only the adapter's read-back fails (once): the save's own events settle the session through the same reads
        final PageManager flaky = spy( pm );
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer( inv -> {
            final boolean readBack = StackWalker.getInstance().walk( s -> s.anyMatch( f ->
                f.getClassName().equals( PageSaveHelperPageWriter.class.getName() )
                    && f.getMethodName().equals( "readBack" ) ) );
            if( readBack && failed.compareAndSet( false, true ) ) {
                throw new ProviderException( "test: the read-back hiccups once" );
            }
            return inv.callRealMethod();
        } ).when( flaky ).getPageText( PAGE, 2 );
        wiring = CollabWiring.wireWith( engine, enabled(), store, flaky );
        registry = CollabEngineAccess.registry( engine );
        final Joined a = join( PAGE );
        assertEquals( 200, type( a, "mine " ).status() );

        final PublishResponse r = publish( a );

        assertTrue( failed.get(), "the read-back failed" );
        assertEquals( 200, r.status(), "the save landed: finished, never 422" );
        assertEquals( 2, r.pageVersion() );
        final String stored = pageBody( PAGE );
        assertTrue( stored.startsWith( "mine hello\nworld\n" ) && stored.contains( "signed" ), stored );
        assertEquals( stored, a.session().view().doc(), "the rewrite adopted once, the typing once" );
        assertEquals( stored, a.session().view().baseBody() );
        assertEquals( 2, a.session().view().basePageVersion() );
    }

    private static java.util.Properties enabled() {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "wikantik.collab.enabled", "true" );
        return p;
    }

    // ---- E27, Review Focus #3: a rename during a publish -------------------------------------------------------

    @Test
    void e27_aRenameDuringPublishLandsOnTheNewName() throws Exception {
        engine.saveText( PAGE, BODY );
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                if( PAGE.equals( context.getPage().getName() ) && content.contains( "HOLD" ) ) {
                    entered.countDown();
                    try {
                        release.await( 20, TimeUnit.SECONDS );
                    } catch( final InterruptedException e ) {
                        Thread.currentThread().interrupt();
                    }
                }
                return content;
            }
        }, 100 );
        final Joined a = join( PAGE );
        assertEquals( 200, type( a, "HOLD " ).status() );

        try {
            final CompletableFuture< PublishResponse > first = CompletableFuture.supplyAsync( () -> publish( a ) );
            assertTrue( entered.await( 20, TimeUnit.SECONDS ), "the publish is held in its pre-save filter" );
            final Context ctx = Wiki.context().create( engine, pm.getPage( PAGE ) );
            engine.getManager( PageRenamer.class ).renamePage( ctx, PAGE, "CollabEngWNew", true );
            release.countDown();

            final PublishResponse r = first.get( 20, TimeUnit.SECONDS );
            assertEquals( 409, r.status() );
            assertEquals( "publish-merged-retry", r.reason() );
            assertEquals( "CollabEngWNew", a.session().pageName(), "the draft followed the rename" );
            assertNull( pm.getPage( PAGE ), "the publish did not recreate the old name" );

            final PublishResponse retry = publish( a );
            assertEquals( 200, retry.status() );
            assertEquals( "HOLD " + BODY, pageBody( "CollabEngWNew" ) );
            assertNull( pm.getPage( PAGE ), "the old name still does not exist" );
            assertEquals( "HOLD " + BODY, a.session().view().doc(), "the typing once" );
        } finally {
            release.countDown();
        }
    }

    // ---- deletes (ruling Q10) ---------------------------------------------------------------------------------------

    @Test
    void pageDeleteFreezesTheDraft() throws Exception {
        engine.saveText( "CollabEngWDel", BODY );
        final Joined a = join( "CollabEngWDel" );
        assertEquals( 200, type( a, "unpublished " ).status() );

        pm.deletePage( "CollabEngWDel" );

        assertTrue( a.session().frozen() );
        assertTrue( registry.hasDraft( "CollabEngWDel" ), "frozen, kept for an administrator" );
        assertEquals( 409, type( a, "more" ).status() );
    }

    @Test
    void theWiringRegistersTheRegistryOnTheEngine() {
        assertNotNull( registry );
        assertTrue( CollabWiring.registryFor( engine ).isPresent() );
        assertTrue( registry.isEnabled() );
        wiring.close();
        assertFalse( wiring.isTicking() );
    }
}
