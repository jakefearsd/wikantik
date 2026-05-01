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
    void claudeJudgeRequiresAllowFlag() {
        // Args.parse itself doesn't enforce the gate (run() does), but it accepts
        // the value verbatim — the gate fires later when run() consults the system
        // property. Just confirm parsing doesn't reject 'claude' here.
        final BootstrapExtractionCli.Args a = BootstrapExtractionCli.Args.parse(
            new String[]{ "--judge", "claude" } );
        assertEquals( "claude", a.judge );
    }
}
