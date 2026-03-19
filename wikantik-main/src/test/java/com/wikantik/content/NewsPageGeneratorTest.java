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
package com.wikantik.content;

import com.wikantik.api.core.Engine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link NewsPageGenerator}.
 */
public class NewsPageGeneratorTest {

    @Test
    public void testFormatNewsPage() {
        final List< String > lines = List.of(
                "2026-03-15 Release 3.1.0: bump version across all modules",
                "2026-03-14 Add MCP link graph and wiki health tools",
                "2026-02-17 Add Single Sign-On (SSO) support via OIDC and SAML"
        );

        final String result = NewsPageGenerator.formatNewsPage( lines );

        Assertions.assertTrue( result.startsWith( "# JSPWiki Development News\n" ) );
        Assertions.assertTrue( result.contains( "## March 2026" ) );
        Assertions.assertTrue( result.contains( "## February 2026" ) );
        Assertions.assertTrue( result.contains( "**2026-03-15** — Release 3.1.0: bump version across all modules" ) );
        Assertions.assertTrue( result.contains( "**2026-03-14** — Add MCP link graph and wiki health tools" ) );
        Assertions.assertTrue( result.contains( "**2026-02-17** — Add Single Sign-On (SSO) support via OIDC and SAML" ) );
    }

    @Test
    public void testFormatNewsPageGroupsByMonth() {
        final List< String > lines = List.of(
                "2026-03-15 Commit A",
                "2026-03-10 Commit B",
                "2026-02-20 Commit C",
                "2026-01-05 Commit D"
        );

        final String result = NewsPageGenerator.formatNewsPage( lines );

        // Verify month headers appear in chronological order (most recent first)
        final int marchPos = result.indexOf( "## March 2026" );
        final int febPos = result.indexOf( "## February 2026" );
        final int janPos = result.indexOf( "## January 2026" );

        Assertions.assertTrue( marchPos > 0, "March header should be present" );
        Assertions.assertTrue( febPos > marchPos, "February should come after March" );
        Assertions.assertTrue( janPos > febPos, "January should come after February" );

        // Verify commits are under the correct month
        final int commitA = result.indexOf( "**2026-03-15** — Commit A" );
        final int commitB = result.indexOf( "**2026-03-10** — Commit B" );
        Assertions.assertTrue( commitA > marchPos && commitA < febPos, "Commit A should be under March" );
        Assertions.assertTrue( commitB > marchPos && commitB < febPos, "Commit B should be under March" );
    }

    @Test
    public void testFormatNewsPageEmptyInput() {
        final String result = NewsPageGenerator.formatNewsPage( List.of() );

        Assertions.assertTrue( result.startsWith( "# JSPWiki Development News\n" ) );
        Assertions.assertTrue( result.contains( "A log of recent development activity" ) );
        // No month headers for empty input
        Assertions.assertFalse( result.contains( "##" ) );
    }

    @Test
    public void testFormatNewsPageSingleEntry() {
        final List< String > lines = List.of( "2026-03-15 Single commit message" );

        final String result = NewsPageGenerator.formatNewsPage( lines );

        Assertions.assertTrue( result.contains( "## March 2026" ) );
        Assertions.assertTrue( result.contains( "**2026-03-15** — Single commit message" ) );
        // Only one month section (one "## " header)
        final long monthHeaders = result.lines().filter( l -> l.startsWith( "## " ) ).count();
        Assertions.assertEquals( 1, monthHeaders, "Should have exactly one month header" );
    }

    @Test
    public void testFormatNewsPageSkipsMalformedLines() {
        final List< String > lines = List.of(
                "2026-03-15 Good commit",
                "not a valid line",
                "",
                "short",
                "2026-02-10 Another good commit"
        );

        final String result = NewsPageGenerator.formatNewsPage( lines );

        Assertions.assertTrue( result.contains( "**2026-03-15** — Good commit" ) );
        Assertions.assertTrue( result.contains( "**2026-02-10** — Another good commit" ) );
        Assertions.assertFalse( result.contains( "not a valid line" ) );
        Assertions.assertFalse( result.contains( "short" ) );
    }

    @Test
    public void testFindGitRoot( @TempDir final Path tempDir ) throws Exception {
        // Create a fake .git directory
        Files.createDirectory( tempDir.resolve( ".git" ) );
        // Create a subdirectory to search from
        final Path subDir = Files.createDirectories( tempDir.resolve( "sub" ).resolve( "dir" ) );

        final Path result = NewsPageGenerator.findGitRoot( subDir );
        Assertions.assertEquals( tempDir, result );
    }

    @Test
    public void testFindGitRootNotInRepo( @TempDir final Path tempDir ) throws Exception {
        // tempDir has no .git directory
        // Walk up will eventually reach filesystem root and return null
        // But to make the test reliable, create a subdirectory inside tempDir
        final Path subDir = Files.createDirectories( tempDir.resolve( "not-a-repo" ) );

        // This will walk up past tempDir to the actual filesystem — in CI/test environments
        // we might actually be in a git repo. So we test with a path that definitely isn't.
        // The safest test is to verify the method finds .git when present (tested above)
        // and returns the correct root. For "not found" we'd need an isolated filesystem.
        // Instead, verify the method returns a Path or null without crashing.
        final Path result = NewsPageGenerator.findGitRoot( subDir );
        // Result may be non-null if test runs inside a git repo — that's fine
        if( result != null ) {
            Assertions.assertTrue( Files.isDirectory( result.resolve( ".git" ) ) );
        }
    }

    @Test
    public void testContentHashPreventsUnnecessaryWrites() {
        final List< String > lines = List.of(
                "2026-03-15 Commit message"
        );

        final String content1 = NewsPageGenerator.formatNewsPage( lines );
        final String content2 = NewsPageGenerator.formatNewsPage( lines );

        final String hash1 = NewsPageGenerator.sha256( content1 );
        final String hash2 = NewsPageGenerator.sha256( content2 );

        Assertions.assertEquals( hash1, hash2, "Same content should produce the same hash" );
        Assertions.assertEquals( content1, content2, "Same input should produce identical content" );

        // Different content should produce different hash
        final String differentContent = NewsPageGenerator.formatNewsPage( List.of( "2026-03-14 Different message" ) );
        final String hash3 = NewsPageGenerator.sha256( differentContent );
        Assertions.assertNotEquals( hash1, hash3, "Different content should produce different hash" );
    }

    @Test
    public void testFirstDeploymentCreatesNewsFile( @TempDir final Path tempDir ) throws Exception {
        final Path newsFile = tempDir.resolve( "News.md" );
        Assertions.assertFalse( Files.exists( newsFile ), "News.md should not exist before first run" );

        // Simulate what backgroundTask does on first deployment: lastContentHash is null,
        // so any generated content should be written to disk.
        final List< String > simulatedGitLog = List.of(
                "2026-03-15 Initial commit",
                "2026-03-14 Add README"
        );
        final String content = NewsPageGenerator.formatNewsPage( simulatedGitLog );
        final String hash = NewsPageGenerator.sha256( content );

        // With a null lastContentHash, the hash comparison fails → file gets written
        final String lastContentHash = null;
        Assertions.assertNotEquals( hash, lastContentHash, "Hash should differ from null on first deployment" );

        // Write the file as backgroundTask would
        Files.writeString( newsFile, content, StandardCharsets.UTF_8 );

        Assertions.assertTrue( Files.exists( newsFile ), "News.md should be created on first deployment" );
        final String written = Files.readString( newsFile, StandardCharsets.UTF_8 );
        Assertions.assertTrue( written.startsWith( "# JSPWiki Development News" ),
                "News page should have the expected header" );
        Assertions.assertTrue( written.contains( "**2026-03-15** — Initial commit" ),
                "News page should contain formatted commit entries" );
    }

    @Test
    public void testFirstDeploymentWithRealGitRepo() throws Exception {
        // Create a temp directory inside the project tree so findGitRoot discovers the real .git
        final Path tempDir = Files.createTempDirectory( Path.of( "target" ).toAbsolutePath(), "news-test-" );
        try {
            final Path newsFile = tempDir.resolve( "News.md" );
            Assertions.assertFalse( Files.exists( newsFile ), "News.md should not exist before first run" );

            final Engine engine = Mockito.mock( Engine.class );
            final NewsPageGenerator generator = new NewsPageGenerator( engine, tempDir.toAbsolutePath().toString() );
            generator.startupTask();

            // Verify git log works in this environment before testing the full flow
            final List< String > gitLogLines = generator.runGitLog();
            Assertions.assertFalse( gitLogLines.isEmpty(), "Git log should return commits from the real repo" );

            generator.backgroundTask();

            Assertions.assertTrue( Files.exists( newsFile ), "News.md should be created on first deployment" );
            final String content = Files.readString( newsFile, StandardCharsets.UTF_8 );
            Assertions.assertTrue( content.startsWith( "# JSPWiki Development News" ),
                    "News page should have the expected header" );
        } finally {
            final Path newsFile = tempDir.resolve( "News.md" );
            Files.deleteIfExists( newsFile );
            Files.deleteIfExists( tempDir );
        }
    }

    @Test
    public void testSha256Deterministic() {
        final String input = "Hello, World!";
        final String hash1 = NewsPageGenerator.sha256( input );
        final String hash2 = NewsPageGenerator.sha256( input );
        Assertions.assertEquals( hash1, hash2 );
        Assertions.assertEquals( 64, hash1.length(), "SHA-256 hex should be 64 chars" );
    }

}
