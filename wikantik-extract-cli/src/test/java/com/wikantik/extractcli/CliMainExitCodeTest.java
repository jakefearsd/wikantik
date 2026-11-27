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

import com.wikantik.extractcli.configref.GenerateConfigReferenceCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each CLI's {@code main} ends in {@code System.exit}, so its exit codes can only be observed from outside the JVM:
 * these tests launch the real {@code main} in a child JVM and pin the status and the stream the message goes to.
 */
class CliMainExitCodeTest {

    private record Outcome( int exit, String out, String err ) { }

    private static Outcome launch( final Class< ? > main, final String... args ) throws Exception {
        final List< String > cmd = new ArrayList<>();
        cmd.add( Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString() );
        cmd.add( "-cp" );
        cmd.add( System.getProperty( "java.class.path" ) );
        cmd.add( main.getName() );
        cmd.addAll( List.of( args ) );
        final Path out = Files.createTempFile( "cli-out", ".txt" );
        final Path err = Files.createTempFile( "cli-err", ".txt" );
        try {
            final Process p = new ProcessBuilder( cmd ).redirectOutput( out.toFile() ).redirectError( err.toFile() ).start();
            if ( !p.waitFor( 60, TimeUnit.SECONDS ) ) {
                p.destroyForcibly();
                throw new IllegalStateException( "child JVM hung: " + cmd );
            }
            return new Outcome( p.exitValue(), Files.readString( out, StandardCharsets.UTF_8 ),
                                Files.readString( err, StandardCharsets.UTF_8 ) );
        } finally {
            Files.deleteIfExists( out );
            Files.deleteIfExists( err );
        }
    }

    // ---- IngestDocumentsCli: 0 ok / 1 failures / 2 bad arguments ----

    @Test
    void ingestWithBadArgumentsExitsTwoAndPrintsTheErrorThenUsage() throws Exception {
        final Outcome o = launch( IngestDocumentsCli.class, "--bogus" );
        assertEquals( 2, o.exit() );
        assertTrue( o.err().startsWith( "error: unknown argument: --bogus" ), o.err() );
        assertTrue( o.out().contains( "Usage:" ), o.out() );
    }

    @Test
    void ingestWithAMissingRequiredArgumentExitsTwo() throws Exception {
        final Outcome o = launch( IngestDocumentsCli.class, "--dir", "x" );
        assertEquals( 2, o.exit() );
        assertTrue( o.err().contains( "--base-url is required" ), o.err() );
    }

    @Test
    void ingestHelpPrintsUsageAndExitsZeroWithoutRunning() throws Exception {
        final Outcome o = launch( IngestDocumentsCli.class, "--help" );
        assertEquals( 0, o.exit() );
        assertTrue( o.out().contains( "Exit codes: 0 = all succeeded, 1 = one or more failed, 2 = bad arguments." ), o.out() );
        assertEquals( "", o.err() );
    }

    @Test
    void ingestExitsTwoWhenTheDirectoryDoesNotExist( @TempDir final Path tmp ) throws Exception {
        final Outcome o = launch( IngestDocumentsCli.class, "--base-url", "http://127.0.0.1:1", "--dir",
                                  tmp.resolve( "missing" ).toString(), "--user", "u", "--password", "p" );
        assertEquals( 2, o.exit() );
        assertTrue( o.err().contains( "--dir is not a directory" ), o.err() );
    }

    @Test
    void ingestExitsOneWhenAnUploadCannotBeSent( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "a.md" ), "x" );
        final Outcome o = launch( IngestDocumentsCli.class, "--base-url", "http://127.0.0.1:1", "--dir",
                                  tmp.toString(), "--user", "u", "--password", "p" );
        assertEquals( 1, o.exit() );
        assertTrue( o.out().contains( "failed=1" ), o.out() );
    }

    // ---- ChunkerStatsCli: 0 stats / 1 bad pages-dir / 2 bad arguments ----

    @Test
    void chunkerStatsWithBadArgumentsExitsTwo() throws Exception {
        final Outcome o = launch( ChunkerStatsCli.class, "--chunker-max-tokens", "abc" );
        assertEquals( 2, o.exit() );
        assertTrue( o.err().startsWith( "error: --chunker-max-tokens expects an integer, got: abc" ), o.err() );
        assertTrue( o.out().contains( "Exit codes: 0 = stats printed, 1 = bad pages-dir, 2 = bad arguments." ), o.out() );
    }

    @Test
    void chunkerStatsWithAMissingPagesDirExitsOne( @TempDir final Path tmp ) throws Exception {
        final Outcome o = launch( ChunkerStatsCli.class, "--pages-dir", tmp.resolve( "nope" ).toString() );
        assertEquals( 1, o.exit() );
        assertTrue( o.err().startsWith( "error: --pages-dir does not exist" ), o.err() );
    }

    @Test
    void chunkerStatsOverAnEmptyDirectoryExitsZero( @TempDir final Path tmp ) throws Exception {
        assertEquals( 0, launch( ChunkerStatsCli.class, "--pages-dir", tmp.toString() ).exit() );
    }

    // ---- CorpusDivergenceCli: 64 usage ----

    @Test
    void corpusDivergenceWithTooFewArgumentsExits64() throws Exception {
        final Outcome o = launch( CorpusDivergenceCli.class, "only-one" );
        assertEquals( 64, o.exit() );
        assertEquals( "usage: CorpusDivergenceCli <corpus-dir> <wiki-base-url> [--check]", o.err().strip() );
        assertEquals( "", o.out() );
    }

    // ---- GenerateMainPageCli: default CHECK; 64 usage; 0 / 2 / 3 from run ----

    @Test
    void generateMainPageWithNoArgumentsExits64() throws Exception {
        final Outcome o = launch( GenerateMainPageCli.class );
        assertEquals( 64, o.exit() );
        assertEquals( "Usage: generate-main-page <pagesDir> [--write|--check]", o.err().strip() );
    }

    @Test
    void generateMainPageWithAnUnknownFlagExits64( @TempDir final Path tmp ) throws Exception {
        final Outcome o = launch( GenerateMainPageCli.class, tmp.toString(), "--bogus" );
        assertEquals( 64, o.exit() );
        assertTrue( o.err().contains( "unknown flag: --bogus" ), o.err() );
    }

    @Test
    void generateMainPageDefaultsToCheckSoAMissingMainMdExitsTwoAndWritesNothing( @TempDir final Path tmp ) throws Exception {
        final Outcome o = launch( GenerateMainPageCli.class, tmp.toString() );
        assertEquals( 2, o.exit() );
        assertTrue( o.out().contains( "Main.md does not exist" ), o.out() );
        assertTrue( !Files.exists( tmp.resolve( "Main.md" ) ), "default mode must not write" );
    }

    @Test
    void generateMainPageWriteThenCheckExitsZeroAndLastFlagWins( @TempDir final Path tmp ) throws Exception {
        assertEquals( 0, launch( GenerateMainPageCli.class, tmp.toString(), "--write" ).exit() );
        assertTrue( Files.exists( tmp.resolve( "Main.md" ) ) );
        assertEquals( 0, launch( GenerateMainPageCli.class, tmp.toString(), "--write", "--check" ).exit() );
    }

    @Test
    void generateMainPageExitsThreeForAMissingPagesDirectory( @TempDir final Path tmp ) throws Exception {
        assertEquals( 3, launch( GenerateMainPageCli.class, tmp.resolve( "nope" ).toString() ).exit() );
    }

    // ---- GenerateConfigReferenceCli: default WRITE; 64 usage; 0 / 1 from run ----

    @Test
    void generateConfigReferenceWithNoArgumentsExits64() throws Exception {
        final Outcome o = launch( GenerateConfigReferenceCli.class );
        assertEquals( 64, o.exit() );
        assertEquals( "Usage: generate-config-reference <repoRoot> [--write|--check]", o.err().strip() );
    }

    @Test
    void generateConfigReferenceWithAnUnknownFlagExits64( @TempDir final Path tmp ) throws Exception {
        final Outcome o = launch( GenerateConfigReferenceCli.class, tmp.toString(), "--bogus" );
        assertEquals( 64, o.exit() );
        assertTrue( o.err().contains( "unknown flag: --bogus" ), o.err() );
    }

    @Test
    void generateConfigReferenceDefaultsToWriteAndCheckThenReportsInSync( @TempDir final Path tmp ) throws Exception {
        put( tmp, "wikantik-main/src/main/resources/ini/wikantik.properties", "# [A]\n#  d\n#  Type: boolean\nwikantik.a = true\n" );
        put( tmp, "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties", "# [B]\n#  d\n#  Type: boolean\nmcp.b = true\n" );
        put( tmp, "wikantik-tools/src/main/resources/wikantik-tools.properties", "# [C]\n#  d\n#  Type: boolean\ntools.c = true\n" );
        Files.createDirectories( tmp.resolve( "docs/wikantik-pages" ) );

        final Outcome write = launch( GenerateConfigReferenceCli.class, tmp.toString() );
        assertEquals( 0, write.exit() );
        assertTrue( write.out().startsWith( "wrote " ), write.out() );
        assertTrue( Files.exists( tmp.resolve( "docs/ConfigurationReference.md" ) ) );

        final Outcome check = launch( GenerateConfigReferenceCli.class, tmp.toString(), "--check" );
        assertEquals( 0, check.exit() );
        assertEquals( "in sync", check.out().strip() );

        Files.writeString( tmp.resolve( "docs/ConfigurationReference.md" ), "stale\n" );
        final Outcome stale = launch( GenerateConfigReferenceCli.class, tmp.toString(), "--check" );
        assertEquals( 1, stale.exit() );
        assertTrue( stale.out().startsWith( "stale: " ), stale.out() );
    }

    private static void put( final Path root, final String rel, final String content ) throws IOException {
        final Path p = root.resolve( rel );
        Files.createDirectories( p.getParent() );
        Files.writeString( p, content, StandardCharsets.ISO_8859_1 );
    }

    // ---- AclShapeAuditCli: 0 / 1 locked / 2 refused / 64 usage ----

    @Test
    void aclAuditWithNoArgumentsExits64AndPrintsUsageOnStderr() throws Exception {
        final Outcome o = launch( AclShapeAuditCli.class );
        assertEquals( 64, o.exit() );
        assertTrue( o.err().startsWith( "usage: AclShapeAuditCli" ), o.err() );
    }

    @Test
    void aclAuditExitsOneWhenAPageIsLockedAndZeroWhenNone( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "Plain.md" ), "text\n" );
        assertEquals( 0, launch( AclShapeAuditCli.class, "--dir", tmp.toString() ).exit() );
        Files.writeString( tmp.resolve( "Denied.md" ), "[{DENY view Bob}]\n" );
        assertEquals( 1, launch( AclShapeAuditCli.class, "--dir", tmp.toString() ).exit() );
    }

    // ---- ReaderTextSnapshotCli: 0 / 3 grows / 1 usage / 2 failure ----

    @Test
    void readerTextSnapshotUsageExitsOneAndFailureExitsTwo( @TempDir final Path tmp ) throws Exception {
        assertEquals( 1, launch( ReaderTextSnapshotCli.class ).exit() );
        assertEquals( 2, launch( ReaderTextSnapshotCli.class, "diff", tmp.resolve( "a" ).toString(), tmp.resolve( "b" ).toString() ).exit() );
    }

    @Test
    void readerTextDiffExitsThreeWhenAPageGrows( @TempDir final Path tmp ) throws Exception {
        final Path a = tmp.resolve( "a.json" );
        final Path b = tmp.resolve( "b.json" );
        Files.writeString( a, "{\"P\":{\"body\":\"1\",\"stripped\":\"s1\",\"strippedLength\":1,\"comments\":[],\"tags\":[]}}" );
        Files.writeString( b, "{\"P\":{\"body\":\"2\",\"stripped\":\"s2\",\"strippedLength\":2,\"comments\":[],\"tags\":[]}}" );
        final Outcome o = launch( ReaderTextSnapshotCli.class, "diff", a.toString(), b.toString() );
        assertEquals( 3, o.exit() );
        assertEquals( "GROWS P comments 0→0 tags +[] -[]", o.out().strip() );
    }
}
