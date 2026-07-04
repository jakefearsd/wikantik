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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke coverage for the CLI's argument parser. Verifies the new per-page
 * pipeline flags land on the {@link BootstrapExtractionCli.Args} fields the
 * runner reads, and that the retired chunk-pipeline flags are now rejected
 * outright (so a stale invocation fails loudly instead of silently doing the
 * wrong thing).
 */
class BootstrapExtractionCliArgsTest {

    @Test
    void newFlagsParse() {
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse( new String[]{
            "--judge", "none",
            "--judge-model", "qwen3.5:9b",
            "--anthropic-key-env", "ANTHROPIC_API_KEY_TEST",
            "--max-entities-per-page", "10",
            "--max-relations-per-page", "5",
            "--dictionary-top-k", "30",
            "--node-embedding-model", "qwen3-embedding:0.6b",
            "--max-pages", "20",
            "--page-pattern", "Knowledge*",
            "--rebuild-node-embeddings",
            "--dry-run",
            "--report", "/tmp/x.json"
        } );
        assertEquals( "none", a.judge );
        assertEquals( "qwen3.5:9b", a.judgeModel );
        assertEquals( "ANTHROPIC_API_KEY_TEST", a.anthropicKeyEnv );
        assertEquals( 10, a.maxEntitiesPerPage );
        assertEquals( 5, a.maxRelationsPerPage );
        assertEquals( 30, a.dictionaryTopK );
        assertEquals( "qwen3-embedding:0.6b", a.nodeEmbeddingModel );
        assertEquals( 20, a.maxPages );
        assertEquals( "Knowledge*", a.pagePattern );
        assertTrue( a.rebuildNodeEmbeddings );
        assertTrue( a.dryRun );
        assertEquals( "/tmp/x.json", a.report );
    }

    @Test
    void defaultsMatchPlan() {
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse( new String[]{} );
        // Plan §4.1 mandates: confidenceThreshold default 0.55, judge default "none",
        // concurrency default 2 (clamped 1..6), maxEntitiesPerPage 12, maxRelationsPerPage 8.
        assertEquals( 0.55, a.confThreshold, 1e-9 );
        assertEquals( "none", a.judge );
        assertEquals( 2, a.concurrency );
        assertEquals( 12, a.maxEntitiesPerPage );
        assertEquals( 8, a.maxRelationsPerPage );
    }

    @Test
    void retiredFlagsRejected() {
        for ( final String flag : new String[]{
            "--prefilter", "--prefilter-dry-run", "--no-prefilter-skip-code",
            "--no-prefilter-skip-nopn", "--no-prefilter-skip-short", "--prefilter-min-tokens",
            "--force", "--stats-only", "--chunker-stats-only", "--chunker-max-tokens",
            "--chunker-merge-forward-tokens", "--pages-dir", "--backend",
            "--max-existing-nodes", "--claude-model"
        } ) {
            assertThrows( IllegalArgumentException.class,
                () -> BootstrapExtractionCli.Args.parse( new String[]{ flag, "x" } ),
                "expected " + flag + " to be rejected" );
        }
    }

    @Test
    void extractorDefaultsToOllama() {
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse( new String[]{} );
        assertEquals( "ollama", a.extractor );
        assertEquals( null, a.extractorModel );
    }

    @Test
    void extractorClaudeParses() {
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse(
            new String[]{ "--extractor", "Claude", "--extractor-model", "claude-opus-4-7" } );
        assertEquals( "claude", a.extractor );      // lower-cased on parse
        assertEquals( "claude-opus-4-7", a.extractorModel );
    }

    @Test
    void unknownExtractorRejected() {
        assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--extractor", "openai" } ) );
    }

    @Test
    void claudeJudgeRequiresAllowFlag() {
        // Args.parse itself doesn't enforce the gate (run() does), but it accepts
        // the value verbatim — the gate fires later when run() consults the system
        // property. Just confirm parsing doesn't reject 'claude' here.
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse(
            new String[]{ "--judge", "claude" } );
        assertEquals( "claude", a.judge );
    }

    // ---- additional validation-branch edge cases ----

    @Test
    void blankJdbcUrlRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--jdbc-url", " " } ) );
        assertTrue( ex.getMessage().contains( "--jdbc-url is required" ), ex.getMessage() );
    }

    @Test
    void blankJdbcUserRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--jdbc-user", " " } ) );
        assertTrue( ex.getMessage().contains( "--jdbc-user is required" ), ex.getMessage() );
    }

    @Test
    void pollSecondsBelowOneRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--poll-seconds", "0" } ) );
        assertTrue( ex.getMessage().contains( "--poll-seconds must be >= 1" ), ex.getMessage() );
    }

    @Test
    void negativeMaxPagesRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--max-pages", "-1" } ) );
        assertTrue( ex.getMessage().contains( "--max-pages must be >= 0" ), ex.getMessage() );
    }

    @Test
    void maxEntitiesPerPageBelowOneRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--max-entities-per-page", "0" } ) );
        assertTrue( ex.getMessage().contains( "--max-entities-per-page must be >= 1" ), ex.getMessage() );
    }

    @Test
    void negativeMaxRelationsPerPageRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--max-relations-per-page", "-1" } ) );
        assertTrue( ex.getMessage().contains( "--max-relations-per-page must be >= 0" ), ex.getMessage() );
    }

    @Test
    void negativeDictionaryTopKRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--dictionary-top-k", "-1" } ) );
        assertTrue( ex.getMessage().contains( "--dictionary-top-k must be >= 0" ), ex.getMessage() );
    }

    @Test
    void confidenceThresholdBelowZeroRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--confidence-threshold", "-0.1" } ) );
        assertTrue( ex.getMessage().contains( "--confidence-threshold must be in [0.0, 1.0]" ), ex.getMessage() );
    }

    @Test
    void confidenceThresholdAboveOneRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--confidence-threshold", "1.1" } ) );
        assertTrue( ex.getMessage().contains( "--confidence-threshold must be in [0.0, 1.0]" ), ex.getMessage() );
    }

    @Test
    void unknownJudgeValueRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--judge", "openai" } ) );
        assertTrue( ex.getMessage().contains( "--judge must be one of: none, ollama, claude" ), ex.getMessage() );
    }

    @Test
    void concurrencyClampsAboveMaxAndBelowMin() {
        final BootstrapExtractionCli.Args high = BootstrapExtractionCli.Args.parse(
            new String[]{ "--concurrency", "99" } );
        assertEquals( BootstrapExtractionCli.Args.CLI_CONCURRENCY_MAX, high.concurrency );

        final BootstrapExtractionCli.Args low = BootstrapExtractionCli.Args.parse(
            new String[]{ "--concurrency", "-5" } );
        assertEquals( 1, low.concurrency );
    }

    @Test
    void helpFlagSkipsAllValidation() {
        // Even with an out-of-range value, --help short-circuits validation.
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse(
            new String[]{ "--max-entities-per-page", "0", "--help" } );
        assertTrue( a.showHelp );
    }

    @Test
    void unknownArgumentRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--totally-bogus" } ) );
        assertTrue( ex.getMessage().contains( "unknown argument: --totally-bogus" ), ex.getMessage() );
    }

    @Test
    void missingValueForFlagThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--jdbc-url" } ) );
        assertTrue( ex.getMessage().contains( "--jdbc-url requires a value" ), ex.getMessage() );
    }

    @Test
    void jdbcPasswordEnvUnsetThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse(
                new String[]{ "--jdbc-password-env", "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ" } ) );
        assertTrue( ex.getMessage().contains( "is unset or empty" ), ex.getMessage() );
    }

    @Test
    void jdbcPasswordEnvReadsPopulatedVar() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv( "PATH" ) != null && !System.getenv( "PATH" ).isBlank() );
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse(
            new String[]{ "--jdbc-password-env", "PATH" } );
        assertEquals( System.getenv( "PATH" ), a.jdbcPassword );
    }

    @Test
    void nonIntegerConcurrencyRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--concurrency", "abc" } ) );
        assertTrue( ex.getMessage().contains( "expects an integer, got: abc" ), ex.getMessage() );
    }

    @Test
    void nonLongTimeoutRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--timeout-ms", "abc" } ) );
        assertTrue( ex.getMessage().contains( "expects a long integer, got: abc" ), ex.getMessage() );
    }

    @Test
    void nonDoubleConfidenceThresholdRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> BootstrapExtractionCli.Args.parse( new String[]{ "--confidence-threshold", "abc" } ) );
        assertTrue( ex.getMessage().contains( "expects a number, got: abc" ), ex.getMessage() );
    }
}
