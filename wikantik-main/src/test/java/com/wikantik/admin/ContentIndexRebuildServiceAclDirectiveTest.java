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
package com.wikantik.admin;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ChunkDiff;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuild re-chunks every page from its stored text, so it must hand the chunker the body of the <em>stripped full
 * text</em> exactly as the save-time projector does: the ACL manager enforces on the full text, and frontmatter can
 * change which body regions are code.
 */
class ContentIndexRebuildServiceAclDirectiveTest {

    private static final String LOGIN = "alice@example.test";
    private static final String D = "[{ALLOW edit " + LOGIN + "}]";

    @Test
    void rebuildChunksTheBodyOfTheStrippedFullText() throws Exception {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "FmPage" );
        final PageManager pages = mock( PageManager.class );
        when( pages.getAllPages() ).thenReturn( List.of( page ) );
        when( pages.getPureText( page ) ).thenReturn(
                "---\ntitle: X\nnotes: |\n  ```\n---\nIntro paragraph.\n\n```\n" + D + "\n```\n\nBody prose.\n" );
        final SystemPageRegistry systemPages = mock( SystemPageRegistry.class );
        when( systemPages.isSystemPage( any() ) ).thenReturn( false );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        when( chunkRepo.findByPage( any() ) ).thenReturn( List.of() );
        when( chunkRepo.stats() ).thenReturn( new ContentChunkRepository.AggregateStats( 0, 0, 0, 0, 0, 0 ) );
        final ContentIndexRebuildService service = new ContentIndexRebuildService( pages, systemPages,
                mock( LuceneReindexQueue.class ), chunkRepo, new ContentChunker( new ContentChunker.Config( 900, 8, 0, 0 ) ),
                () -> true, 25L );

        service.triggerRebuild();
        await().atMost( Duration.ofSeconds( 10 ) ).until( () -> service.snapshot().rebuild().state().equals( "IDLE" ) );

        final ArgumentCaptor< ChunkDiff.Diff > diff = ArgumentCaptor.forClass( ChunkDiff.Diff.class );
        verify( chunkRepo ).apply( eq( "FmPage" ), diff.capture() );
        final List< Chunk > inserts = diff.getValue().inserts();
        assertFalse( inserts.isEmpty() );
        for ( final Chunk chunk : inserts ) {
            assertFalse( chunk.text().contains( LOGIN ), chunk.text() );
        }
        assertTrue( inserts.stream().anyMatch( c -> c.text().contains( "Body prose." ) ), "the page is still chunked" );
    }
}
