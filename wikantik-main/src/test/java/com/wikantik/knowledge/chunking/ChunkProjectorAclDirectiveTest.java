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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chunker only ever sees a body, but {@code DefaultAclManager} enforces on the full text, frontmatter included, and
 * frontmatter can change which body regions are code. So the save-time projector hands the chunker the body of the
 * <em>stripped full text</em>. (No database: the repository is a mock and the chunks it is asked to apply are inspected.)
 */
class ChunkProjectorAclDirectiveTest {

    private static final String LOGIN = "alice@example.test";
    private static final String D = "[{ALLOW edit " + LOGIN + "}]";

    /** A YAML block scalar opens a fence that runs past the closing --- in the full-text parse. */
    static final String FRONTMATTER_FLIPS_THE_FENCE =
            "---\ntitle: X\nnotes: |\n  ```\n---\nIntro paragraph.\n\n```\n" + D + "\n```\n\nBody prose.\n";

    private ContentChunkRepository repository;
    private ChunkProjector projector;

    @BeforeEach
    void setUp() {
        repository = mock( ContentChunkRepository.class );
        when( repository.findByPage( any() ) ).thenReturn( List.of() );
        projector = new ChunkProjector( new ContentChunker( new ContentChunker.Config( 512, 8, 0, 0 ) ), repository, () -> true );
    }

    private static Context contextFor( final String pageName ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( pageName );
        final Context context = mock( Context.class );
        when( context.getPage() ).thenReturn( page );
        return context;
    }

    private List< Chunk > appliedInserts() {
        final ArgumentCaptor< ChunkDiff.Diff > diff = ArgumentCaptor.forClass( ChunkDiff.Diff.class );
        verify( repository ).apply( eq( "FmPage" ), diff.capture() );
        return diff.getValue().inserts();
    }

    @Test
    void postSaveChunksTheBodyOfTheStrippedFullTextSoARuleEnforcedFromTheFullTextNeverReachesAChunk() {
        projector.postSave( contextFor( "FmPage" ), FRONTMATTER_FLIPS_THE_FENCE );

        final List< Chunk > inserts = appliedInserts();
        assertFalse( inserts.isEmpty() );
        for ( final Chunk chunk : inserts ) {
            assertFalse( chunk.text().contains( LOGIN ), chunk.text() );
        }
        assertTrue( inserts.stream().anyMatch( c -> c.text().contains( "Body prose." ) ), "the page is still chunked" );
    }

    @Test
    void postSaveStillChunksAnOrdinaryPageWithADocumentationExample() {
        projector.postSave( contextFor( "FmPage" ),
                "---\ntitle: X\n---\nProse.\n\nUse `" + D + "` to restrict a page.\n" );

        assertTrue( appliedInserts().stream().anyMatch( c -> c.text().contains( "`" + D + "`" ) ),
                "a documentation example in a code span is page content and stays" );
    }
}
