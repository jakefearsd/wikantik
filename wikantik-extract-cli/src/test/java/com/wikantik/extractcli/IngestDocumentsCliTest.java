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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IngestDocumentsCliTest {

    // ---- Args.parse ----

    @Test
    void parsesRequiredArgs() {
        final IngestDocumentsCli.Args a = IngestDocumentsCli.Args.parse( new String[]{
            "--base-url", "http://localhost:8080",
            "--dir", "/tmp/docs",
            "--user", "admin",
            "--password", "secret123"
        } );
        assertEquals( "http://localhost:8080", a.baseUrl );
        assertEquals( "/tmp/docs", a.dir );
        assertEquals( "admin", a.user );
        assertEquals( "secret123", a.password );
        assertFalse( a.force );
    }

    @Test
    void parsesForceFlag() {
        final IngestDocumentsCli.Args a = IngestDocumentsCli.Args.parse( new String[]{
            "--base-url", "http://localhost:8080",
            "--dir", "/tmp",
            "--user", "admin",
            "--password", "pass",
            "--force"
        } );
        assertTrue( a.force );
    }

    @Test
    void rejectsUnknownFlag() {
        assertThrows( IllegalArgumentException.class, () ->
            IngestDocumentsCli.Args.parse( new String[]{
                "--base-url", "x", "--dir", "y",
                "--user", "u", "--password", "p", "--unknown" } ) );
    }

    @Test
    void rejectsMissingBaseUrl() {
        assertThrows( IllegalArgumentException.class, () ->
            IngestDocumentsCli.Args.parse( new String[]{ "--dir", "y", "--user", "u", "--password", "p" } ) );
    }

    @Test
    void rejectsMissingDir() {
        assertThrows( IllegalArgumentException.class, () ->
            IngestDocumentsCli.Args.parse( new String[]{ "--base-url", "http://localhost", "--user", "u", "--password", "p" } ) );
    }

    @Test
    void rejectsMissingUser() {
        assertThrows( IllegalArgumentException.class, () ->
            IngestDocumentsCli.Args.parse( new String[]{ "--base-url", "http://localhost", "--dir", "y", "--password", "p" } ) );
    }

    @Test
    void rejectsMissingPassword() {
        assertThrows( IllegalArgumentException.class, () ->
            IngestDocumentsCli.Args.parse( new String[]{ "--base-url", "http://localhost", "--dir", "y", "--user", "u" } ) );
    }

    // ---- basicAuthHeader ----

    @Test
    void basicAuthHeaderProducesCorrectBase64() {
        final String header = IngestDocumentsCli.basicAuthHeader( "admin", "secret123" );
        final String expected = "Basic " + Base64.getEncoder()
            .encodeToString( "admin:secret123".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( expected, header );
        assertTrue( header.startsWith( "Basic " ), "must start with 'Basic '" );
    }

    @Test
    void basicAuthHeaderEncodesSpecialCharacters() {
        final String header = IngestDocumentsCli.basicAuthHeader( "user@domain", "p@ss:w0rd!" );
        final String decoded = new String(
            Base64.getDecoder().decode( header.substring( "Basic ".length() ) ),
            StandardCharsets.UTF_8 );
        assertEquals( "user@domain:p@ss:w0rd!", decoded );
    }

    // ---- extension filter ----

    @Test
    void supportedExtensionsAreAccepted() {
        final Set<String> exts = Set.of( ".pdf", ".txt", ".md", ".docx", ".pptx", ".xlsx" );
        for ( final String ext : exts ) {
            assertTrue( IngestDocumentsCli.isSupportedExtension( "file" + ext ),
                        "expected supported: " + ext );
            assertTrue( IngestDocumentsCli.isSupportedExtension( "file" + ext.toUpperCase() ),
                        "expected supported (uppercase): " + ext );
        }
    }

    @Test
    void unsupportedExtensionsAreRejected() {
        assertFalse( IngestDocumentsCli.isSupportedExtension( "photo.png" ) );
        assertFalse( IngestDocumentsCli.isSupportedExtension( "archive.zip" ) );
        assertFalse( IngestDocumentsCli.isSupportedExtension( "data.csv" ) );
        assertFalse( IngestDocumentsCli.isSupportedExtension( "noext" ) );
    }

    // ---- walk + tally (seam-injected fake poster) ----

    @Test
    void skipsUnsupportedFilesAndTalliesCorrectly( @TempDir final Path dir ) throws Exception {
        Files.writeString( dir.resolve( "report.pdf" ), "pdf" );
        Files.writeString( dir.resolve( "notes.txt" ), "txt" );
        Files.writeString( dir.resolve( "photo.png" ), "png" );  // must be skipped
        Files.writeString( dir.resolve( "archive.zip" ), "zip" ); // must be skipped
        Files.writeString( dir.resolve( "slides.pptx" ), "pptx" );

        // Fake poster: pdf → created, txt → updated, pptx → unchanged
        final Map<String, String> responses = Map.of(
            "report.pdf", "created",
            "notes.txt",  "updated",
            "slides.pptx","unchanged"
        );
        final List<String> visited = new ArrayList<>();
        final IngestDocumentsCli.FilePoster fakePoster = path -> {
            visited.add( path.getFileName().toString() );
            final String status = responses.get( path.getFileName().toString() );
            if ( status == null ) {
                throw new IllegalStateException( "unexpected file: " + path );
            }
            return status;
        };

        final IngestDocumentsCli.Tally tally = IngestDocumentsCli.runWalk( dir, fakePoster );

        // Only the 3 supported files were visited — not png/zip
        assertEquals( 3, visited.size(), "visited count" );
        assertFalse( visited.contains( "photo.png" ) );
        assertFalse( visited.contains( "archive.zip" ) );

        assertEquals( 1, tally.created() );
        assertEquals( 1, tally.updated() );
        assertEquals( 1, tally.unchanged() );
        assertEquals( 0, tally.failed() );
    }

    @Test
    void countsFailedOnPosterException( @TempDir final Path dir ) throws Exception {
        Files.writeString( dir.resolve( "broken.pdf" ), "pdf" );

        final IngestDocumentsCli.FilePoster failingPoster = path -> {
            throw new RuntimeException( "simulated HTTP error" );
        };

        final IngestDocumentsCli.Tally tally = IngestDocumentsCli.runWalk( dir, failingPoster );

        assertEquals( 0, tally.created() );
        assertEquals( 0, tally.updated() );
        assertEquals( 0, tally.unchanged() );
        assertEquals( 1, tally.failed() );
    }

    @Test
    void walksSubdirectoriesRecursively( @TempDir final Path dir ) throws Exception {
        final Path sub = Files.createDirectory( dir.resolve( "subdir" ) );
        Files.writeString( dir.resolve( "top.pdf" ), "pdf" );
        Files.writeString( sub.resolve( "nested.txt" ), "txt" );

        final AtomicInteger count = new AtomicInteger();
        final IngestDocumentsCli.FilePoster counter = path -> {
            count.incrementAndGet();
            return "created";
        };

        final IngestDocumentsCli.Tally tally = IngestDocumentsCli.runWalk( dir, counter );

        assertEquals( 2, count.get(), "should visit both top-level and nested files" );
        assertEquals( 2, tally.created() );
    }

    @Test
    void tallyTotalIsSum() {
        final IngestDocumentsCli.Tally t = new IngestDocumentsCli.Tally( 2, 3, 1, 4 );
        assertEquals( 10, t.total() );
    }

    // ---- extractStatus ----

    @Test
    void extractStatusParsesStandardResponse() {
        assertEquals( "created", IngestDocumentsCli.extractStatus( "{\"page\":\"Foo\",\"status\":\"created\"}" ) );
    }

    @Test
    void extractStatusHandlesWhitespaceBetweenColonAndValue() {
        assertEquals( "updated", IngestDocumentsCli.extractStatus( "{\"status\" : \"updated\"}" ) );
    }

    @Test
    void extractStatusFallsBackToRawBodyWhenStatusFieldAbsent() {
        assertEquals( "no status field here", IngestDocumentsCli.extractStatus( "no status field here" ) );
    }

    @Test
    void extractStatusFallsBackWhenColonMissingAfterKey() {
        assertEquals( "\"status\"", IngestDocumentsCli.extractStatus( "\"status\"" ) );
    }

    @Test
    void extractStatusFallsBackOnUnterminatedValue() {
        final String malformed = "{\"status\":\"unterminated";
        assertEquals( malformed, IngestDocumentsCli.extractStatus( malformed ) );
    }

    @Test
    void extractStatusTrimsFallbackBody() {
        assertEquals( "plain text", IngestDocumentsCli.extractStatus( "  plain text  \n" ) );
    }

    // ---- buildMultipartBody ----

    @Test
    void buildMultipartBodyIncludesFilenameAndFileContent() {
        final byte[] body = IngestDocumentsCli.buildMultipartBody(
            "B123", "report.pdf", "PDF-BYTES".getBytes( StandardCharsets.UTF_8 ), false );
        final String text = new String( body, StandardCharsets.UTF_8 );

        assertTrue( text.startsWith( "--B123\r\n" ), text );
        assertTrue( text.contains( "Content-Disposition: form-data; name=\"file\"; filename=\"report.pdf\"" ), text );
        assertTrue( text.contains( "Content-Type: application/octet-stream" ), text );
        assertTrue( text.contains( "PDF-BYTES" ), text );
        assertTrue( text.endsWith( "--B123--\r\n" ), text );
        assertFalse( text.contains( "name=\"force\"" ), "force field must be absent when force=false: " + text );
    }

    @Test
    void buildMultipartBodyIncludesForceFieldWhenRequested() {
        final byte[] body = IngestDocumentsCli.buildMultipartBody(
            "B999", "notes.md", "hello".getBytes( StandardCharsets.UTF_8 ), true );
        final String text = new String( body, StandardCharsets.UTF_8 );

        assertTrue( text.contains( "Content-Disposition: form-data; name=\"force\"" ), text );
        assertTrue( text.contains( "\r\ntrue\r\n" ), text );
        // The force part must appear after the file part and before the closing boundary.
        final int forceIdx = text.indexOf( "name=\"force\"" );
        final int closingIdx = text.lastIndexOf( "--B999--" );
        assertTrue( forceIdx > 0 && forceIdx < closingIdx );
    }

    @Test
    void buildMultipartBodyLengthMatchesComponentSizes() {
        final byte[] fileBytes = "0123456789".getBytes( StandardCharsets.UTF_8 );
        final byte[] withoutForce = IngestDocumentsCli.buildMultipartBody( "B", "f.txt", fileBytes, false );
        final byte[] withForce = IngestDocumentsCli.buildMultipartBody( "B", "f.txt", fileBytes, true );
        assertTrue( withForce.length > withoutForce.length,
            "adding the force field must grow the body" );
    }

    // ---- run(): embedded HTTP server, no mocking framework needed ----

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if ( server != null ) {
            server.stop( 0 );
            server = null;
        }
    }

    /** Starts a loopback HTTP server that always answers the given status/body pair. */
    private String startFixedResponseServer( final int httpStatus, final String jsonBody,
                                              final List<String> capturedAuthHeaders ) throws Exception {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/api/ingest", exchange -> {
            capturedAuthHeaders.add( exchange.getRequestHeaders().getFirst( "Authorization" ) );
            // Drain the request body so the client doesn't see a connection reset.
            exchange.getRequestBody().readAllBytes();
            final byte[] resp = jsonBody.getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( httpStatus, resp.length );
            try ( OutputStream os = exchange.getResponseBody() ) {
                os.write( resp );
            }
        } );
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String runCapturingStdout( final IngestDocumentsCli.Args a, final int[] rcOut ) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut( new PrintStream( captured, true, StandardCharsets.UTF_8 ) );
        try {
            rcOut[ 0 ] = IngestDocumentsCli.run( a );
        } finally {
            System.setOut( original );
        }
        return captured.toString( StandardCharsets.UTF_8 );
    }

    @Test
    void runReturnsTwoWhenDirDoesNotExist( @TempDir final Path tmp ) {
        final IngestDocumentsCli.Args a = new IngestDocumentsCli.Args();
        a.baseUrl = "http://127.0.0.1:1";
        a.dir = tmp.resolve( "missing" ).toString();
        a.user = "admin";
        a.password = "secret";

        final int[] rc = new int[1];
        final String out = runCapturingStdout( a, rc );
        assertEquals( 2, rc[ 0 ] );
        assertFalse( out.contains( "walking" ), out );
    }

    @Test
    void runPostsFileWithBasicAuthAndReturnsZeroOnSuccess( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "doc.md" ), "# hello" );
        final List<String> authHeaders = new CopyOnWriteArrayList<>();
        final String baseUrl = startFixedResponseServer( 200, "{\"page\":\"doc\",\"status\":\"created\"}", authHeaders );

        final IngestDocumentsCli.Args a = new IngestDocumentsCli.Args();
        a.baseUrl = baseUrl;
        a.dir = tmp.toString();
        a.user = "admin";
        a.password = "sekrit";

        final int[] rc = new int[1];
        final String out = runCapturingStdout( a, rc );

        assertEquals( 0, rc[ 0 ] );
        assertTrue( out.contains( "[created]   doc.md" ), out );
        assertTrue( out.contains( "total=1  created=1  updated=0  unchanged=0  failed=0" ), out );
        assertEquals( 1, authHeaders.size() );
        assertEquals( IngestDocumentsCli.basicAuthHeader( "admin", "sekrit" ), authHeaders.get( 0 ) );
    }

    @Test
    void runReturnsOneWhenServerRejectsTheUpload( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "doc.md" ), "# hello" );
        final String baseUrl = startFixedResponseServer( 500, "internal error", new CopyOnWriteArrayList<>() );

        final IngestDocumentsCli.Args a = new IngestDocumentsCli.Args();
        a.baseUrl = baseUrl;
        a.dir = tmp.toString();
        a.user = "admin";
        a.password = "sekrit";

        final int[] rc = new int[1];
        final String out = runCapturingStdout( a, rc );

        assertEquals( 1, rc[ 0 ] );
        assertTrue( out.contains( "[FAILED]" ), out );
        assertTrue( out.contains( "failed=1" ), out );
    }

    @Test
    void runWithForceFlagSetsForceFieldOnTheRequest( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "doc.txt" ), "content" );
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        final List<String> capturedBodies = new CopyOnWriteArrayList<>();
        server.createContext( "/api/ingest", exchange -> {
            capturedBodies.add( new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 ) );
            final byte[] resp = "{\"status\":\"updated\"}".getBytes( StandardCharsets.UTF_8 );
            exchange.sendResponseHeaders( 200, resp.length );
            try ( OutputStream os = exchange.getResponseBody() ) { os.write( resp ); }
        } );
        server.start();

        final IngestDocumentsCli.Args a = new IngestDocumentsCli.Args();
        a.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        a.dir = tmp.toString();
        a.user = "admin";
        a.password = "sekrit";
        a.force = true;

        final int[] rc = new int[1];
        runCapturingStdout( a, rc );

        assertEquals( 0, rc[ 0 ] );
        assertEquals( 1, capturedBodies.size() );
        assertTrue( capturedBodies.get( 0 ).contains( "name=\"force\"" ), capturedBodies.get( 0 ) );
    }
}
