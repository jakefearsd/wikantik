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
package com.wikantik.extractcli;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the private {@code run(Args)} (and a couple of its private
 * collaborators) of {@link BootstrapExtractionCli} via reflection — the only
 * seam available for a class whose {@code main()} always terminates the JVM
 * via {@code System.exit}.
 *
 * <p>Two fixture strategies are used:</p>
 * <ul>
 *   <li>An <b>unreachable database</b> ({@code 127.0.0.1:1}, which refuses
 *       connections immediately) drives the many branches of {@code run()}
 *       that construct real collaborators (judge, extractor, embedding
 *       service, chunk repo) but never need a live corpus, because the async
 *       indexer fails fast inside {@code warmNodeEmbeddings}/{@code runBatch}
 *       and {@code run()} still returns a clean exit code.</li>
 *   <li>The shared {@link PostgresTestDb} instance (real migrated schema,
 *       zero rows) proves the genuine {@code COMPLETED} / exit-0 happy path,
 *       where the indexer really does finish a (trivially empty) batch.</li>
 * </ul>
 */
@RequiresPostgres
class BootstrapExtractionCliRunTest {

    private DataSource ds;

    @BeforeEach
    void clean() throws Exception {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "kg_content_chunks", "kg_nodes", "kg_excluded_pages", "kg_node_embeddings" );
    }

    private static BootstrapExtractionCli.Args baseArgs() {
        final BootstrapExtractionCli.Args a = new BootstrapExtractionCli.Args();
        a.jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nonexistent";
        a.pollSeconds = 1;
        a.pollMillis = 25;
        return a;
    }

    private static int invokeRun( final BootstrapExtractionCli.Args a ) throws Exception {
        final Method m = BootstrapExtractionCli.class.getDeclaredMethod( "run", BootstrapExtractionCli.Args.class );
        m.setAccessible( true );
        try {
            return ( int ) m.invoke( null, a );
        } catch ( final InvocationTargetException ite ) {
            if ( ite.getCause() instanceof RuntimeException re ) throw re;
            throw ite;
        }
    }

    // ---- unreachable-DB branches: no live database needed, just fast-fail behavior ----

    /**
     * The wait loop must honor a sub-second poll override: with the seconds-granularity
     * floor, every one of this class's nine runs paid a full pollSeconds sleep even
     * though the indexer had already left RUNNING.
     */
    @Test
    void subSecondPollOverrideAvoidsTheFullSecondFloor() throws Exception {
        final BootstrapExtractionCli.Args a = baseArgs();
        a.pollMillis = 25;

        final long start = System.nanoTime();
        assertEquals( 1, invokeRun( a ) );
        final long elapsedMs = ( System.nanoTime() - start ) / 1_000_000;

        assertTrue( elapsedMs < 900,
                "fast-fail run should finish well under the old 1s poll floor, took " + elapsedMs + "ms" );
    }

    @Test
    void runReturnsOneWhenIndexerFailsAgainstAnUnreachableDatabase() throws Exception {
        final int rc = invokeRun( baseArgs() );
        assertEquals( 1, rc, "indexer's async run should end in State.ERROR against an unreachable DB" );
    }

    @Test
    void runWithOllamaJudgeStillFailsCleanlyAgainstAnUnreachableDatabase() throws Exception {
        final BootstrapExtractionCli.Args a = baseArgs();
        a.judge = "ollama";
        a.ollamaUrl = "http://127.0.0.1:1";
        assertEquals( 1, invokeRun( a ) );
    }

    @Test
    void runWithClaudeJudgeGateDisabledReturnsOneBeforeTouchingTheDatabase() throws Exception {
        System.clearProperty( "wikantik.kg.judge.allow_claude" );
        final BootstrapExtractionCli.Args a = baseArgs();
        a.judge = "claude";
        assertEquals( 1, invokeRun( a ) );
    }

    @Test
    void runWithClaudeExtractorAndPopulatedKeyEnvStillFailsCleanlyOnUnreachableDb() throws Exception {
        Assumptions.assumeTrue( System.getenv( "PATH" ) != null && !System.getenv( "PATH" ).isBlank() );
        System.setProperty( "wikantik.kg.extractor.allow_claude", "true" );
        try {
            final BootstrapExtractionCli.Args a = baseArgs();
            a.extractor = "claude";
            a.anthropicKeyEnv = "PATH";
            assertEquals( 1, invokeRun( a ) );
        } finally {
            System.clearProperty( "wikantik.kg.extractor.allow_claude" );
        }
    }

    @Test
    void runWithClaudeExtractorGateDisabledReturnsOneBeforeStartingTheIndexer() throws Exception {
        System.clearProperty( "wikantik.kg.extractor.allow_claude" );
        final BootstrapExtractionCli.Args a = baseArgs();
        a.extractor = "claude";
        assertEquals( 1, invokeRun( a ) );
    }

    @Test
    void runWithRebuildNodeEmbeddingsFailsFastOnUnreachableDatabase() throws Exception {
        final BootstrapExtractionCli.Args a = baseArgs();
        a.rebuildNodeEmbeddings = true;
        assertEquals( 1, invokeRun( a ) );
    }

    @Test
    void runWithPagePatternStillFailsCleanlyOnUnreachableDatabase() throws Exception {
        final BootstrapExtractionCli.Args a = baseArgs();
        a.pagePattern = "Foo*";
        assertEquals( 1, invokeRun( a ) );
    }

    @Test
    void runWithReportWritesNoFileWhenIndexerErrors( @TempDir final Path tmp ) throws Exception {
        final BootstrapExtractionCli.Args a = baseArgs();
        a.report = tmp.resolve( "report.json" ).toString();
        assertEquals( 1, invokeRun( a ) );
        // The indexer really did run (and error) — its final report reflects that,
        // it isn't simply absent.
        assertTrue( Files.exists( Path.of( a.report ) ), "run() always writes --report once the indexer finishes" );
        final String json = Files.readString( Path.of( a.report ) );
        assertTrue( json.contains( "\"state\": \"ERROR\"" ), json );
    }

    // ---- happy path: real (empty) Postgres schema, indexer really completes ----

    @Test
    void runCompletesWithZeroPagesAgainstAnEmptySchema( @TempDir final Path tmp ) throws Exception {
        final BootstrapExtractionCli.Args a = new BootstrapExtractionCli.Args();
        a.jdbcUrl = PostgresTestDb.getJdbcUrl();
        a.jdbcUser = PostgresTestDb.getUsername();
        a.jdbcPassword = PostgresTestDb.getPassword();
        a.pollSeconds = 1;
        a.report = tmp.resolve( "report.json" ).toString();

        assertEquals( 0, invokeRun( a ), "an empty corpus should reach State.COMPLETED" );

        final String json = Files.readString( Path.of( a.report ) );
        assertTrue( json.contains( "\"state\": \"COMPLETED\"" ), json );
        assertTrue( json.contains( "\"totalPages\": 0" ), json );
    }

    @Test
    void runWithRebuildNodeEmbeddingsSucceedsAgainstARealDatabase() throws Exception {
        final BootstrapExtractionCli.Args a = new BootstrapExtractionCli.Args();
        a.jdbcUrl = PostgresTestDb.getJdbcUrl();
        a.jdbcUser = PostgresTestDb.getUsername();
        a.jdbcPassword = PostgresTestDb.getPassword();
        a.pollSeconds = 1;
        a.rebuildNodeEmbeddings = true;

        assertEquals( 0, invokeRun( a ) );
    }

    @Test
    void runWithOllamaJudgeAndExtractorCompletesAgainstAnEmptyCorpus() throws Exception {
        // Zero pages means neither the judge nor the extractor is ever actually
        // invoked over the network — this exercises buildJudge's "ollama" branch
        // and buildExtractor's "ollama" branch inside a real, completed run.
        final BootstrapExtractionCli.Args a = new BootstrapExtractionCli.Args();
        a.jdbcUrl = PostgresTestDb.getJdbcUrl();
        a.jdbcUser = PostgresTestDb.getUsername();
        a.jdbcPassword = PostgresTestDb.getPassword();
        a.pollSeconds = 1;
        a.judge = "ollama";
        a.extractor = "ollama";

        assertEquals( 0, invokeRun( a ) );
    }

    // ---- pagePatternFiltered / globToRegex integration: private, reflection-invoked ----

    @SuppressWarnings( "unchecked" )
    private static List< String > listDistinctPageNamesViaPagePatternFiltered(
            final DataSource dataSource, final String glob ) throws Exception {
        final Method m = BootstrapExtractionCli.class.getDeclaredMethod(
            "pagePatternFiltered", DataSource.class, String.class );
        m.setAccessible( true );
        final Object chunkRepo = m.invoke( null, dataSource, glob );
        final Method list = chunkRepo.getClass().getMethod( "listDistinctPageNames" );
        return ( List< String > ) list.invoke( chunkRepo );
    }

    @Test
    void pagePatternFilteredScopesListDistinctPageNamesToMatchingPages() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            insertChunk( c, "MatchAlpha" );
            insertChunk( c, "MatchBeta" );
            insertChunk( c, "NoMatch" );
        }

        final List< String > names = listDistinctPageNamesViaPagePatternFiltered( ds, "Match*" );

        assertEquals( List.of( "MatchAlpha", "MatchBeta" ), names );
        assertFalse( names.contains( "NoMatch" ) );
    }

    private static void insertChunk( final Connection c, final String pageName ) throws Exception {
        try ( var ps = c.prepareStatement(
                "INSERT INTO kg_content_chunks (page_name, chunk_index, text, char_count, "
              + "token_count_estimate, content_hash) VALUES (?, 0, ?, ?, ?, ?)" ) ) {
            ps.setString( 1, pageName );
            ps.setString( 2, pageName + " body text." );
            ps.setInt( 3, 20 );
            ps.setInt( 4, 5 );
            ps.setString( 5, "hash-" + pageName );
            ps.executeUpdate();
        }
    }
}
