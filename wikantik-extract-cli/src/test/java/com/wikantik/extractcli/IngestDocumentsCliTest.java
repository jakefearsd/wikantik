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
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
}
