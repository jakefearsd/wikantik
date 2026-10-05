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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link JudgeExperimentCli#run} end to end: a real pending-proposal queue in PostgreSQL, real
 * chunk stitching, and a local HTTP server standing in for the Ollama judge.
 */
@RequiresPostgres
class JudgeExperimentCliRunTest {

    private static final String JUDGE_REPLY =
            "{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"reject\\\",\\\"reason_code\\\":\\\"too_generic\\\","
            + "\\\"rationale\\\":\\\"too broad\\\"}\"}}";

    @TempDir Path tmp;
    private HttpServer ollama;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new Jdbc( PostgresTestDb.createDataSource() );
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_proposals", "kg_content_chunks" );
        ollama = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        ollama.createContext( "/api/chat", ex -> {
            final byte[] body = JUDGE_REPLY.getBytes( StandardCharsets.UTF_8 );
            ex.getResponseHeaders().add( "Content-Type", "application/json" );
            ex.sendResponseHeaders( 200, body.length );
            ex.getResponseBody().write( body );
            ex.close();
        } );
        ollama.start();
    }

    @AfterEach
    void tearDown() {
        ollama.stop( 0 );
    }

    private void seedChunk( final String page, final int idx, final String text ) throws Exception {
        jdbc.update( "INSERT INTO kg_content_chunks (page_name, chunk_index, text, char_count, "
                + "token_count_estimate, content_hash) VALUES (?, ?, ?, ?, ?, ?)", ps -> {
            ps.setString( 1, page );
            ps.setInt( 2, idx );
            ps.setString( 3, text );
            ps.setInt( 4, text.length() );
            ps.setInt( 5, 5 );
            ps.setString( 6, page + idx );
        } );
    }

    private void seedProposal( final String signature, final String type, final String data,
                               final String support, final String page ) throws Exception {
        jdbc.update( "INSERT INTO kg_proposals (proposal_type, source_page, proposed_data, confidence, "
                + "signature, support, support_count) VALUES (?, ?, ?::jsonb, 0.7, ?, ?::jsonb, 1)", ps -> {
            ps.setString( 1, type );
            ps.setString( 2, page );
            ps.setString( 3, data );
            ps.setString( 4, signature );
            ps.setString( 5, support );
        } );
    }

    private JudgeExperimentCli.Args args( final Path out ) {
        final JudgeExperimentCli.Args a = new JudgeExperimentCli.Args();
        a.judge = "ollama";
        a.ollamaUrl = "http://127.0.0.1:" + ollama.getAddress().getPort();
        a.jdbcUrl = PostgresTestDb.getJdbcUrl();
        a.jdbcUser = PostgresTestDb.getUsername();
        a.jdbcPassword = PostgresTestDb.getPassword();
        a.sample = 10;
        a.timeoutMs = 5_000L;
        a.output = out.toString();
        return a;
    }

    @Test
    void runJudgesPendingProposalsAndWritesReport() throws Exception {
        seedChunk( "StitchPage", 0, "First chunk." );
        seedChunk( "StitchPage", 1, "Second chunk." );
        final String support = "[{\"sourcePage\":\"StitchPage\",\"evidenceSpan\":\"First chunk.\","
                + "\"confidence\":0.8,\"extractorCode\":\"x\"}]";
        seedProposal( "node:Kafka:Technology", "new-node", "{\"name\":\"Kafka\",\"nodeType\":\"Technology\"}",
                support, "StitchPage" );
        seedProposal( "edge:a:uses:b", "new-edge",
                "{\"source\":\"A\",\"target\":\"B\",\"relationship\":\"uses\"}",
                "[{\"sourcePage\":\"NoChunksPage\",\"evidenceSpan\":\"e\",\"confidence\":0.5,\"extractorCode\":\"x\"}]",
                "NoChunksPage" );
        final Path out = tmp.resolve( "report.json" );

        assertEquals( 0, JudgeExperimentCli.run( args( out ) ) );

        final JsonObject report = JsonParser.parseString( Files.readString( out ) ).getAsJsonObject();
        assertEquals( 2, report.get( "sampleSize" ).getAsInt() );
        assertEquals( "ollama", report.get( "judge" ).getAsString() );
        assertEquals( 2, report.getAsJsonObject( "noopVerdicts" ).get( "accepted" ).getAsInt() );
        final JsonObject cmp = report.getAsJsonObject( "comparatorVerdicts" );
        assertEquals( 2, cmp.get( "rejected" ).getAsInt() );
        assertEquals( 2, cmp.getAsJsonObject( "reject_reasons" ).get( "too_generic" ).getAsInt() );
        final JsonArray examples = report.getAsJsonArray( "examples" );
        assertEquals( 2, examples.size() );
        for ( final var el : examples ) {
            final JsonObject ex = el.getAsJsonObject();
            assertEquals( "accept", ex.get( "noopVerdict" ).getAsString().split( ":" )[ 0 ] );
            assertEquals( "reject:too_generic (too broad)", ex.get( "comparatorVerdict" ).getAsString() );
        }
    }

    @Test
    void runWithEmptyQueueStillWritesAnEmptyReport() throws Exception {
        final Path out = tmp.resolve( "empty.json" );
        assertEquals( 0, JudgeExperimentCli.run( args( out ) ) );
        final JsonObject report = JsonParser.parseString( Files.readString( out ) ).getAsJsonObject();
        assertEquals( 0, report.get( "sampleSize" ).getAsInt() );
        assertEquals( 0, report.getAsJsonArray( "examples" ).size() );
    }

    @Test
    void runReturnsOneWhenReportCannotBeWritten() {
        // A directory is not a writable report file.
        assertEquals( 1, JudgeExperimentCli.run( args( tmp ) ) );
    }

    @Test
    void runReturnsOneWhenTheDatabaseIsUnreachable() {
        final JudgeExperimentCli.Args a = args( tmp.resolve( "never.json" ) );
        a.jdbcUrl = "jdbc:postgresql://127.0.0.1:1/none";
        assertEquals( 1, JudgeExperimentCli.run( a ) );
        assertFalse( Files.exists( tmp.resolve( "never.json" ) ) );
    }

    @Test
    void runReturnsOneForAnUnsupportedJudge() {
        final JudgeExperimentCli.Args a = args( tmp.resolve( "never.json" ) );
        a.judge = "bogus";
        assertEquals( 1, JudgeExperimentCli.run( a ) );
    }
}
