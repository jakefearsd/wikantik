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

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.frontmatter.ParsedPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers( disabledWithoutDocker = true )
class ChunkProjectorTest {

    private static DataSource dataSource;
    private ContentChunkRepository repo;
    private ContentChunker chunker;
    private ChunkProjector projector;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
        repo = new ContentChunkRepository( dataSource );
        chunker = new ContentChunker( new ContentChunker.Config( 300, 512, 80, 8 ) );
        projector = new ChunkProjector( chunker, repo, () -> true );
    }

    @Test
    void saveNewPageWritesChunks() {
        projector.projectPage( "P", Map.of(),
            "Body with enough prose content to produce a single chunk of reasonable size "
            + "so that the min-tokens floor does not cause merge-forward to swallow this "
            + "buffer and we end up with at least one persisted chunk row in the database." );
        assertEquals( 1, repo.findByPage( "P" ).size() );
    }

    @Test
    void resaveUnchangedPageIsNoop() {
        final String body = "Stable body content used twice in a row to test no-op behaviour. "
            + "We need enough prose to cross the min-tokens threshold so a real chunk is emitted.";
        projector.projectPage( "P", Map.of(), body );
        final String hashBefore = repo.findByPage( "P" ).get( 0 ).contentHash();
        projector.projectPage( "P", Map.of(), body );
        assertEquals( hashBefore, repo.findByPage( "P" ).get( 0 ).contentHash() );
        assertEquals( 1, repo.findByPage( "P" ).size() );
    }

    @Test
    void editChangesOnlyAffectedChunks() {
        final String first = "## First\n\n"
            + "First section paragraph with plenty of prose content so this is a real standalone "
            + "chunk well above the min-tokens threshold and well beyond the merge-forward floor.\n\n";
        final String secondV1 = "## Second\n\n"
            + "Second section v1 paragraph with plenty of prose content so this is a real standalone "
            + "chunk well above the min-tokens threshold and well beyond the merge-forward floor.\n";
        projector.projectPage( "P", Map.of(), first + secondV1 );
        final List< ChunkDiff.Stored > before = repo.findByPage( "P" );
        assertEquals( 2, before.size() );
        final UUID firstId = before.get( 0 ).id();

        final String secondV2 = "## Second\n\n"
            + "Second section v2 updated paragraph with plenty of prose content so this is a real standalone "
            + "chunk well above the min-tokens threshold and well beyond the merge-forward floor.\n";
        projector.projectPage( "P", Map.of(), first + secondV2 );
        final List< ChunkDiff.Stored > after = repo.findByPage( "P" );
        assertEquals( 2, after.size() );
        assertEquals( firstId, after.get( 0 ).id(), "first chunk id preserved" );
        assertNotEquals( before.get( 1 ).contentHash(), after.get( 1 ).contentHash(),
            "second chunk hash updated" );
    }

    @Test
    void chunkerExceptionIsCaughtAndLogged() {
        final ContentChunker throwing = new ContentChunker(
            new ContentChunker.Config( 300, 512, 80, 8 ) ) {
            @Override
            public List< Chunk > chunk( final String pageName, final ParsedPage p ) {
                throw new RuntimeException( "boom" );
            }
        };
        final ChunkProjector p2 = new ChunkProjector( throwing, repo, () -> true );
        assertDoesNotThrow( () -> p2.projectPage( "P", Map.of(), "body" ) );
        assertTrue( repo.findByPage( "P" ).isEmpty() );
    }

    @Test
    void disabledFlagSkipsChunking() {
        final ChunkProjector off = new ChunkProjector( chunker, repo, () -> false );
        off.projectPage( "P", Map.of(), "Body content sufficient to emit a chunk under normal settings." );
        assertTrue( repo.findByPage( "P" ).isEmpty() );
    }
}
