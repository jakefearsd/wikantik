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

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.api.knowledge.Verdict;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeExperimentCliTest {

    @Test
    void argsRequireJudge() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--output", "/tmp/x.json" } ) );
        assertTrue( ex.getMessage().contains( "--judge is required" ),
            () -> "unexpected: " + ex.getMessage() );
    }

    @Test
    void argsRequireOutput() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--judge", "ollama" } ) );
        assertTrue( ex.getMessage().contains( "--output is required" ),
            () -> "unexpected: " + ex.getMessage() );
    }

    @Test
    void argsRejectNoneJudge() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "none", "--output", "/tmp/x.json" } ) );
        assertTrue( ex.getMessage().contains( "ollama" ) );
    }

    @Test
    void argsParseOk() {
        final JudgeExperimentCli.Args a = JudgeExperimentCli.Args.parse( new String[]{
            "--judge", "ollama",
            "--judge-model", "qwen3.5:9b",
            "--sample", "25",
            "--output", "/tmp/judge.json"
        } );
        assertEquals( "ollama", a.judge );
        assertEquals( "qwen3.5:9b", a.judgeModel );
        assertEquals( 25, a.sample );
        assertEquals( "/tmp/judge.json", a.output );
    }

    @Test
    void hydrateNodeProposal() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:Kafka:Technology",
            "new-node",
            "{\"name\":\"Kafka\",\"nodeType\":\"Technology\"}",
            "[{\"sourcePage\":\"P1\",\"evidenceSpan\":\"Kafka is fast\",\"confidence\":0.9,\"extractorCode\":\"ollama:gemma\"}]",
            "P1",
            0.9 );
        assertNotNull( p );
        assertEquals( ConsolidatedProposal.Kind.NEW_NODE, p.kind() );
        assertEquals( "Kafka", p.displayName() );
        assertEquals( "Technology", p.type() );
        assertEquals( 1, p.support().size() );
        assertEquals( "P1", p.support().get( 0 ).sourcePage() );
    }

    @Test
    void hydrateEdgeProposal() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "edge:A:B:depends_on",
            "new-edge",
            "{\"source\":\"A\",\"target\":\"B\",\"relationship\":\"depends_on\"}",
            "[{\"sourcePage\":\"P\",\"evidenceSpan\":\"A depends on B\",\"confidence\":0.8,\"extractorCode\":\"ollama:gemma\"}]",
            "P",
            0.8 );
        assertNotNull( p );
        assertEquals( ConsolidatedProposal.Kind.NEW_EDGE, p.kind() );
        assertEquals( "A", p.source() );
        assertEquals( "B", p.target() );
        assertEquals( "depends_on", p.predicate() );
    }

    @Test
    void hydrateSkipsBlankSignature() {
        assertNull( JudgeExperimentCli.hydrate(
            "  ", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateSkipsEdgeMissingFields() {
        assertNull( JudgeExperimentCli.hydrate(
            "edge:bad", "new-edge",
            "{\"source\":\"A\"}",
            "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateFallsBackOnEmptySupport() {
        // Empty support array + a fallback source_page → synthesize one stub
        // SupportEvidence so the proposal is still judgable.
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            null, "FallbackPage", 0.5 );
        assertNotNull( p );
        assertEquals( 1, p.support().size() );
        assertEquals( "FallbackPage", p.support().get( 0 ).sourcePage() );
    }

    @Test
    void judgeStatsTracksJudgeFailedAccepts() {
        final JudgeExperimentCli.JudgeStats stats = new JudgeExperimentCli.JudgeStats( "ollama:test" );
        stats.record( new Verdict.Accept( 0.9, "real accept" ) );
        stats.record( new Verdict.Accept( 0.5, "judge_failed: HTTP 500" ) );
        stats.record( new Verdict.Reject( "too_generic", "vague" ) );
        stats.record( new Verdict.Reject( "too_generic", "vague again" ) );
        stats.record( new Verdict.Reject( "weak_support", "thin" ) );
        assertEquals( 2, stats.accepted );
        assertEquals( 1, stats.judgeFailed );
        assertEquals( 3, stats.rejected );
        assertEquals( 2, stats.rejectReasons.get( "too_generic" ) );
        assertEquals( 1, stats.rejectReasons.get( "weak_support" ) );
    }

    // ---- Args: additional edge cases ----

    @Test
    void argsRejectUnknownJudgeValue() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "openai", "--output", "/tmp/x.json" } ) );
        assertTrue( ex.getMessage().contains( "must be 'ollama' or 'claude'" ), ex.getMessage() );
    }

    @Test
    void argsRejectSampleBelowOne() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "ollama", "--output", "/tmp/x.json", "--sample", "0" } ) );
        assertTrue( ex.getMessage().contains( "--sample must be >= 1" ), ex.getMessage() );
    }

    @Test
    void argsRejectTimeoutBelowOneThousandMs() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "ollama", "--output", "/tmp/x.json", "--timeout-ms", "500" } ) );
        assertTrue( ex.getMessage().contains( "--timeout-ms must be >= 1000" ), ex.getMessage() );
    }

    @Test
    void argsHelpSkipsValidation() {
        final JudgeExperimentCli.Args a = JudgeExperimentCli.Args.parse( new String[]{ "--help" } );
        assertTrue( a.showHelp );
    }

    @Test
    void argsShortHelpFlag() {
        assertTrue( JudgeExperimentCli.Args.parse( new String[]{ "-h" } ).showHelp );
    }

    @Test
    void argsRejectUnknownFlag() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--bogus" } ) );
        assertTrue( ex.getMessage().contains( "unknown argument: --bogus" ), ex.getMessage() );
    }

    @Test
    void argsMissingValueForFlagThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{ "--sample" } ) );
        assertTrue( ex.getMessage().contains( "--sample requires a value" ), ex.getMessage() );
    }

    @Test
    void argsNonIntegerSampleRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "ollama", "--output", "/tmp/x.json", "--sample", "abc" } ) );
        assertTrue( ex.getMessage().contains( "requires an integer, got: abc" ), ex.getMessage() );
    }

    @Test
    void argsNonLongTimeoutRejected() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse(
                new String[]{ "--judge", "ollama", "--output", "/tmp/x.json", "--timeout-ms", "abc" } ) );
        assertTrue( ex.getMessage().contains( "requires an integer, got: abc" ), ex.getMessage() );
    }

    @Test
    void argsJdbcPasswordEnvUnsetThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> JudgeExperimentCli.Args.parse( new String[]{
                "--judge", "ollama", "--output", "/tmp/x.json",
                "--jdbc-password-env", "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ" } ) );
        assertTrue( ex.getMessage().contains( "is unset or empty" ), ex.getMessage() );
    }

    @Test
    void argsJdbcPasswordEnvReadsPopulatedVar() {
        Assumptions.assumeTrue( System.getenv( "PATH" ) != null && !System.getenv( "PATH" ).isBlank() );
        final JudgeExperimentCli.Args a = JudgeExperimentCli.Args.parse( new String[]{
            "--judge", "ollama", "--output", "/tmp/x.json", "--jdbc-password-env", "PATH" } );
        assertEquals( System.getenv( "PATH" ), a.jdbcPassword );
    }

    @Test
    void argsJudgeLowercasedOnParse() {
        final JudgeExperimentCli.Args a = JudgeExperimentCli.Args.parse(
            new String[]{ "--judge", "OLLAMA", "--output", "/tmp/x.json" } );
        assertEquals( "ollama", a.judge );
    }

    // ---- hydrate: additional parseSupport / stringOrNull edge cases ----

    @Test
    void hydrateWithMalformedSupportJsonYieldsEmptySupportButStillHydrates() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "not-json{{{", "SourcePage", 0.5 );
        assertNotNull( p );
        assertTrue( p.support().isEmpty() );
    }

    @Test
    void hydrateClampsOutOfRangeConfidenceInSupportEntries() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "[{\"sourcePage\":\"P\",\"evidenceSpan\":\"e\",\"confidence\":5.0,\"extractorCode\":\"x\"}]",
            "P", 0.5 );
        assertNotNull( p );
        assertEquals( 1, p.support().size() );
        assertEquals( 1.0, p.support().get( 0 ).confidence(), 1e-9 );
    }

    @Test
    void hydrateClampsNegativeConfidenceInSupportEntries() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "[{\"sourcePage\":\"P\",\"evidenceSpan\":\"e\",\"confidence\":-2.0,\"extractorCode\":\"x\"}]",
            "P", 0.5 );
        assertEquals( 0.0, p.support().get( 0 ).confidence(), 1e-9 );
    }

    @Test
    void hydrateSkipsSupportEntriesMissingPageOrCode() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:Concept", "new-node",
            "{\"name\":\"X\",\"nodeType\":\"Concept\"}",
            "[{\"evidenceSpan\":\"no page\",\"confidence\":0.5,\"extractorCode\":\"x\"},"
          + "{\"sourcePage\":\"P\",\"evidenceSpan\":\"no code\",\"confidence\":0.5}]",
            "P", 0.5 );
        assertTrue( p.support().isEmpty() );
    }

    @Test
    void hydrateDefaultsNodeTypeToConceptWhenAbsent() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:X:?", "new-node",
            "{\"name\":\"X\"}",
            "[]", "P", 0.5 );
        assertNotNull( p );
        assertEquals( "Concept", p.type() );
    }

    @Test
    void hydrateSkipsNodeProposalMissingName() {
        assertNull( JudgeExperimentCli.hydrate(
            "node:noname", "new-node", "{\"nodeType\":\"Concept\"}", "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateSkipsWhenProposedDataIsNotAJsonObject() {
        assertNull( JudgeExperimentCli.hydrate(
            "node:bad-data", "new-node", "[1,2,3]", "[]", "P", 0.5 ) );
    }

    @Test
    void hydrateUnknownProposalTypeDefaultsToNewNode() {
        final ConsolidatedProposal p = JudgeExperimentCli.hydrate(
            "node:legacy", "legacy-type",
            "{\"name\":\"Legacy\",\"nodeType\":\"Technology\"}",
            "[]", "P", 0.5 );
        assertNotNull( p );
        assertEquals( ConsolidatedProposal.Kind.NEW_NODE, p.kind() );
        assertEquals( "Legacy", p.displayName() );
    }

    // ---- JudgeStats: toMap / summary ----

    @Test
    void judgeStatsToMapReflectsAllCounters() {
        final JudgeExperimentCli.JudgeStats stats = new JudgeExperimentCli.JudgeStats( "ollama:test" );
        stats.record( new Verdict.Accept( 0.9, "ok" ) );
        stats.record( new Verdict.Reject( "too_generic", "vague" ) );
        stats.record( new Verdict.Rewrite(
            ConsolidatedProposal.newNode( "node:X:Concept", "X", "Concept", List.of(), 0.5 ),
            "canonicalize" ) );

        final Map< String, Object > map = stats.toMap();
        assertEquals( "ollama:test", map.get( "code" ) );
        assertEquals( 1, map.get( "accepted" ) );
        assertEquals( 1, map.get( "rejected" ) );
        assertEquals( 1, map.get( "rewritten" ) );
        assertEquals( 0, map.get( "judge_failed_accepts" ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Integer > reasons = ( Map< String, Integer > ) map.get( "reject_reasons" );
        assertEquals( 1, reasons.get( "too_generic" ) );
    }

    @Test
    void judgeStatsSummaryFormatsAllCounters() {
        final JudgeExperimentCli.JudgeStats stats = new JudgeExperimentCli.JudgeStats( "noop" );
        stats.record( new Verdict.Accept( 0.9, "judge_failed: timeout" ) );
        stats.record( new Verdict.Reject( "weak_support", "thin" ) );
        assertEquals( "accept=1 reject=1 rewrite=0 judge_failed=1", stats.summary() );
    }

    // ---- sampleProposals: Mockito-driven DataSource/Connection/ResultSet ----

    @Test
    void sampleProposalsHydratesRowsFromResultSet() throws Exception {
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );

        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        // Two rows: one valid node proposal, one with a blank signature (must be dropped).
        when( rs.next() ).thenReturn( true, true, false );
        when( rs.getString( "signature" ) ).thenReturn( "node:Kafka:Technology", "  " );
        when( rs.getString( "proposal_type" ) ).thenReturn( "new-node", "new-node" );
        when( rs.getString( "proposed_data" ) ).thenReturn(
            "{\"name\":\"Kafka\",\"nodeType\":\"Technology\"}", "{\"name\":\"Blank\"}" );
        when( rs.getString( "support" ) ).thenReturn( "[]", "[]" );
        when( rs.getString( "source_page" ) ).thenReturn( "P1", "P2" );
        when( rs.getDouble( "confidence" ) ).thenReturn( 0.8, 0.5 );

        final List< JudgeExperimentCli.Sampled > sampled = JudgeExperimentCli.sampleProposals( ds, 10 );

        assertEquals( 1, sampled.size(), "the blank-signature row must be dropped by hydrate()" );
        assertEquals( "Kafka", sampled.get( 0 ).proposal().displayName() );
        Mockito.verify( ps ).setInt( 1, 10 );
    }

    @Test
    void sampleProposalsReturnsEmptyListWhenNoRows() throws Exception {
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( false );

        assertTrue( JudgeExperimentCli.sampleProposals( ds, 5 ).isEmpty() );
    }

    @Test
    void sampleProposalsWrapsSqlExceptionAsRuntimeException() throws Exception {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new java.sql.SQLException( "connection refused" ) );

        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> JudgeExperimentCli.sampleProposals( ds, 5 ) );
        assertTrue( ex.getMessage().contains( "sample query failed" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( "connection refused" ), ex.getMessage() );
    }

    // ---- writeReport ----

    @Test
    void writeReportProducesParsablePrettyJsonWithExamples() throws Exception {
        final JudgeExperimentCli.ExampleRow row = new JudgeExperimentCli.ExampleRow(
            "node:Kafka:Technology", "NEW_NODE", "Kafka", "Technology",
            null, null, null, 0.9, 1, "accept: ok", "reject:too_generic (vague)" );
        final JudgeExperimentCli.Report report = new JudgeExperimentCli.Report(
            "2026-07-01T00:00:00Z", "ollama", "ollama:gemma", 1,
            Map.of( "accepted", 1 ), Map.of( "rejected", 1 ), List.of( row ) );

        final Path tmp = Files.createTempFile( "judge-experiment", ".json" );
        try {
            JudgeExperimentCli.writeReport( tmp.toString(), report );
            final String json = Files.readString( tmp );
            assertTrue( json.contains( "\"judge\": \"ollama\"" ), json );
            assertTrue( json.contains( "\"displayName\": \"Kafka\"" ), json );
            assertTrue( json.contains( "\"noopVerdict\": \"accept: ok\"" ), json );
            assertTrue( json.contains( "\"source\": null" ), "serializeNulls should keep null fields: " + json );
        } finally {
            Files.deleteIfExists( tmp );
        }
    }

    // ---- buildComparator (private; reflection is the only seam) ----

    private static ProposalJudge invokeBuildComparator( final JudgeExperimentCli.Args a ) throws Exception {
        final Method m = JudgeExperimentCli.class.getDeclaredMethod( "buildComparator", JudgeExperimentCli.Args.class );
        m.setAccessible( true );
        try {
            return ( ProposalJudge ) m.invoke( null, a );
        } catch ( final InvocationTargetException ite ) {
            if ( ite.getCause() instanceof RuntimeException re ) throw re;
            throw ite;
        }
    }

    private static JudgeExperimentCli.Args baseArgs( final String judge ) {
        final JudgeExperimentCli.Args a = new JudgeExperimentCli.Args();
        a.judge = judge;
        a.judgeModel = "test-model";
        a.ollamaUrl = "http://127.0.0.1:1";
        a.timeoutMs = 60_000L;
        return a;
    }

    @Test
    void buildComparatorOllamaSucceedsWithoutNetworkAccess() throws Exception {
        final ProposalJudge judge = invokeBuildComparator( baseArgs( "ollama" ) );
        assertNotNull( judge );
        assertTrue( judge.code().startsWith( "ollama:" ), judge.code() );
    }

    @Test
    void buildComparatorClaudeGateDisabledThrows() {
        System.clearProperty( "wikantik.kg.judge.allow_claude" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> invokeBuildComparator( baseArgs( "claude" ) ) );
        assertTrue( ex.getMessage().contains( "gated cost guard" ), ex.getMessage() );
    }

    @Test
    void buildComparatorClaudeMissingKeyEnvThrows() {
        System.setProperty( "wikantik.kg.judge.allow_claude", "true" );
        try {
            final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> invokeBuildComparator( baseArgs( "claude" ) ) );
            assertTrue( ex.getMessage().contains( "--anthropic-key-env" ), ex.getMessage() );
        } finally {
            System.clearProperty( "wikantik.kg.judge.allow_claude" );
        }
    }

    @Test
    void buildComparatorClaudeUnsetEnvVarThrows() {
        System.setProperty( "wikantik.kg.judge.allow_claude", "true" );
        try {
            final JudgeExperimentCli.Args a = baseArgs( "claude" );
            a.anthropicKeyEnv = "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ";
            final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> invokeBuildComparator( a ) );
            assertTrue( ex.getMessage().contains( "unset or empty" ), ex.getMessage() );
        } finally {
            System.clearProperty( "wikantik.kg.judge.allow_claude" );
        }
    }

    @Test
    void buildComparatorClaudeSucceedsWithGateAndPopulatedKeyEnv() throws Exception {
        Assumptions.assumeTrue( System.getenv( "PATH" ) != null && !System.getenv( "PATH" ).isBlank() );
        System.setProperty( "wikantik.kg.judge.allow_claude", "true" );
        try {
            final JudgeExperimentCli.Args a = baseArgs( "claude" );
            a.anthropicKeyEnv = "PATH";
            final ProposalJudge judge = invokeBuildComparator( a );
            assertNotNull( judge );
        } finally {
            System.clearProperty( "wikantik.kg.judge.allow_claude" );
        }
    }

    @Test
    void buildComparatorUnknownJudgeValueThrows() {
        // Args.parse() itself never lets an unknown value through — construct the
        // bag directly to exercise buildComparator's defensive default branch.
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> invokeBuildComparator( baseArgs( "openai" ) ) );
        assertTrue( ex.getMessage().contains( "must be 'ollama' or 'claude'" ), ex.getMessage() );
    }

    // ---- run(): reachable branches without a live Postgres ----

    private static int invokeRun( final JudgeExperimentCli.Args a ) throws Exception {
        final Method m = JudgeExperimentCli.class.getDeclaredMethod( "run", JudgeExperimentCli.Args.class );
        m.setAccessible( true );
        return ( int ) m.invoke( null, a );
    }

    @Test
    void runReturnsOneWhenComparatorGateFails( @TempDir final Path tmp ) throws Exception {
        System.clearProperty( "wikantik.kg.judge.allow_claude" );
        final JudgeExperimentCli.Args a = baseArgs( "claude" );
        a.output = tmp.resolve( "report.json" ).toString();
        assertEquals( 1, invokeRun( a ) );
        assertFalse( Files.exists( Path.of( a.output ) ), "no report should be written when the judge gate fails" );
    }

    @Test
    void runReturnsOneWhenDatabaseIsUnreachable( @TempDir final Path tmp ) throws Exception {
        // Port 1 on loopback refuses immediately — exercises dataSource() + the
        // ContentChunkRepository construction + sampleProposals()'s failure branch
        // inside run(), without needing a live Postgres instance.
        final JudgeExperimentCli.Args a = baseArgs( "ollama" );
        a.jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nonexistent";
        a.output = tmp.resolve( "report.json" ).toString();
        assertEquals( 1, invokeRun( a ) );
        assertFalse( Files.exists( Path.of( a.output ) ) );
    }
}
