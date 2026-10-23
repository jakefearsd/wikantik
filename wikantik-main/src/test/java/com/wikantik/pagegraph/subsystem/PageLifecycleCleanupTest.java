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
package com.wikantik.pagegraph.subsystem;

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.TestEngine;
import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.api.core.Context;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.comments.CommentStore;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.comments.mentions.MentionService;
import com.wikantik.content.PageRenamer;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.filters.FilterManager;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.knowledge.subsystem.KnowledgeWiringHelper;
import com.wikantik.pagegraph.spine.DefaultStructuralIndexService;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end through the production wiring helpers: a true page delete leaves no rows keyed on
 * the page behind (ownership, comment threads, chunks, the canonical-id row), while a rename
 * keeps every one of them — the canonical-keyed rows because the id is rename-stable, the chunks
 * re-keyed to the new name with their ids (and so their embeddings) intact.
 */
@RequiresPostgres
class PageLifecycleCleanupTest {

    private static final String BODY = "\n## Section\n\nThis section carries enough prose to produce a chunk of "
            + "reasonable size so the min-tokens floor does not merge it away entirely.\n";

    private TestEngine engine;
    private PageManager pageManager;
    private PageOwnerService owners;
    private CommentStore comments;
    private ContentChunkRepository chunks;
    private PageCanonicalIdsDao canonicalIds;
    private DefaultStructuralIndexService structuralIndex;

    @BeforeEach
    void setUp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "page_owners", "comment_mentions", "comments", "comment_threads", "kg_content_chunks" );
        engine = TestEngine.build();
        final CoreSubsystem.Services core = CoreSubsystemBridge.fromLegacyEngine( engine );
        final PersistenceSubsystem.Services persistence = PersistenceSubsystemFactory.create(
                new PersistenceSubsystem.Deps( ds, core.properties() ) );
        owners = persistence.pageOwners();
        comments = persistence.comments();
        canonicalIds = persistence.pageCanonicalIds();
        pageManager = engine.getManager( PageManager.class );
        final FilterManager filterManager = engine.getManager( FilterManager.class );
        structuralIndex = PageGraphWiringHelper.wireStructuralSpine( engine.getWikiProperties(), persistence, core,
                pageManager, filterManager, engine.getManager( ReferenceManager.class ), engine );
        structuralIndex.rebuild();

        chunks = new ContentChunkRepository( ds );
        final ChunkProjector projector = new ChunkProjector(
                new ContentChunker( new ContentChunker.Config( 512, 8, 0, 0 ) ), chunks, () -> true );
        filterManager.addPageFilter( projector, -1005 );
        KnowledgeWiringHelper.wireChunkLifecycle( projector, pageManager, renamer(), engine );
    }

    @AfterEach
    void tearDown() {
        engine.deleteQuietly( "LifecycleGone", "LifecycleOld", "LifecycleNew" );
        engine.stop();
    }

    private PageRenamer renamer() {
        return engine.getPageSubsystem().pageRenamer();
    }

    /** Saves a page with an authored canonical_id, then gives it an owner and a comment thread. */
    private String savePageWithOwnerAndThread( final String name ) throws Exception {
        final String cid = UlidCreator.getUlid().toString();
        engine.saveText( name, "---\ncanonical_id: " + cid + "\ntitle: " + name + "\n---\n" + BODY );
        owners.setOwner( cid, "alice", "admin" );
        comments.createThread( cid, new TextQuoteSelector( "prose", null, null ), "alice", "why?",
                new MentionService( PostgresTestDb.createDataSource(), s -> false ), Optional.empty() );
        assertEquals( Optional.of( cid ), structuralIndex.resolveCanonicalIdFromSlug( name ), "precondition: indexed" );
        assertFalse( chunks.findByPage( name ).isEmpty(), "precondition: chunked" );
        return cid;
    }

    @Test
    void aTrueDeleteLeavesNoRowsKeyedOnThePageBehind() throws Exception {
        final String cid = savePageWithOwnerAndThread( "LifecycleGone" );

        pageManager.deletePage( "LifecycleGone" );

        assertTrue( owners.findRaw( cid ).isEmpty(), "page_owners row left behind" );
        assertTrue( comments.listByCanonicalId( cid, "all" ).isEmpty(), "comment threads left behind" );
        assertTrue( chunks.findByPage( "LifecycleGone" ).isEmpty(), "chunks left behind" );
        assertTrue( canonicalIds.findByCanonicalId( cid ).isEmpty(), "page_canonical_ids row left behind" );
    }

    @Test
    void aRenameKeepsEveryRowAndMovesThePageKeyedOnesToTheNewName() throws Exception {
        final String cid = savePageWithOwnerAndThread( "LifecycleOld" );
        final List< UUID > chunkIds = chunks.listChunkIdsForPage( "LifecycleOld" );
        final Context context = Wiki.context().create( engine, pageManager.getPage( "LifecycleOld" ) );

        renamer().renamePage( context, "LifecycleOld", "LifecycleNew", false );

        assertEquals( "alice", owners.findRaw( cid ).orElseThrow().ownerLogin(), "ownership survives a rename" );
        assertEquals( 1, comments.listByCanonicalId( cid, "all" ).size(), "comment threads survive a rename" );
        assertTrue( chunks.findByPage( "LifecycleOld" ).isEmpty(), "no chunks linger under the old name" );
        assertEquals( chunkIds, chunks.listChunkIdsForPage( "LifecycleNew" ), "chunks re-keyed with their ids" );
        assertEquals( Optional.of( cid ), structuralIndex.resolveCanonicalIdFromSlug( "LifecycleNew" ) );
        assertEquals( "LifecycleNew", canonicalIds.findByCanonicalId( cid ).orElseThrow().currentSlug(),
                "the canonical-id row follows the page to its new slug" );

        // And deleting the page under its new name is a true delete.
        pageManager.deletePage( "LifecycleNew" );

        assertTrue( owners.findRaw( cid ).isEmpty() );
        assertTrue( comments.listByCanonicalId( cid, "all" ).isEmpty() );
        assertTrue( chunks.findByPage( "LifecycleNew" ).isEmpty() );
    }
}
