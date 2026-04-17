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
package com.wikantik;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the two guards around {@link TestEngine#safelyEmptyTestOwnedDir} —
 * the {@code .testengine-owned} marker file and the one-hour file-age check.
 * These prevent the helper from deleting a real corpus when a test
 * accidentally points {@code wikantik.fileSystemProvider.pageDir} at the
 * wrong place.
 */
class TestEngineSafetyTest {

    @Test
    void safelyEmptyDeletesWhenMarkerPresentAndFilesFresh( @TempDir final Path dir ) throws IOException {
        Files.writeString( dir.resolve( TestEngine.TEST_OWNERSHIP_MARKER ), "owned" );
        Files.writeString( dir.resolve( "page1.md" ), "fresh content" );
        Files.createDirectories( dir.resolve( "sub" ) );
        Files.writeString( dir.resolve( "sub/page2.md" ), "fresh nested" );

        TestEngine.safelyEmptyTestOwnedDir( dir.toString() );

        assertFalse( Files.exists( dir ),
                "with marker + recent files, the directory should be removed" );
    }

    @Test
    void safelyEmptyRefusesWhenMarkerMissing( @TempDir final Path dir ) throws IOException {
        Files.writeString( dir.resolve( "page1.md" ), "user data — do not delete" );

        TestEngine.safelyEmptyTestOwnedDir( dir.toString() );

        assertTrue( Files.exists( dir.resolve( "page1.md" ) ),
                "without a marker, emptyWikiDir must leave the directory untouched" );
    }

    @Test
    void safelyEmptyScreamsAndRefusesWhenAnyFileIsOlderThanOneHour( @TempDir final Path dir ) throws IOException {
        Files.writeString( dir.resolve( TestEngine.TEST_OWNERSHIP_MARKER ),
                "this marker is somehow in a prod directory" );
        Files.writeString( dir.resolve( "fresh.md" ), "recent" );
        final Path old = Files.writeString( dir.resolve( "ancient.md" ), "real data" );
        Files.setLastModifiedTime( old, FileTime.from(
                Instant.now().minus( Duration.ofHours( 2 ) ) ) );

        TestEngine.safelyEmptyTestOwnedDir( dir.toString() );

        assertTrue( Files.exists( old ),
                "file older than 1h must not be deleted even with a marker present" );
        assertTrue( Files.exists( dir.resolve( "fresh.md" ) ),
                "other files in the same dir must also be preserved when age guard fires" );
        assertTrue( Files.exists( dir.resolve( TestEngine.TEST_OWNERSHIP_MARKER ) ),
                "the marker itself is preserved so the operator can investigate" );
    }

    @Test
    void safelyEmptyTreatsOldFilesInSubdirectoriesAsStale( @TempDir final Path dir ) throws IOException {
        Files.writeString( dir.resolve( TestEngine.TEST_OWNERSHIP_MARKER ), "owned" );
        Files.createDirectories( dir.resolve( "nested" ) );
        final Path old = Files.writeString( dir.resolve( "nested/deep.md" ), "real nested data" );
        Files.setLastModifiedTime( old, FileTime.from(
                Instant.now().minus( Duration.ofHours( 2 ) ) ) );

        TestEngine.safelyEmptyTestOwnedDir( dir.toString() );

        assertTrue( Files.exists( old ),
                "stale file nested in a subdirectory must still trip the guard" );
    }

    @Test
    void safelyEmptyNoopOnNonExistentDirectory( @TempDir final Path parent ) {
        final Path doesNotExist = parent.resolve( "never-created" );
        assertDoesNotThrow( () -> TestEngine.safelyEmptyTestOwnedDir( doesNotExist.toString() ) );
    }

    @Test
    void safelyEmptyNoopOnNullPath() {
        assertDoesNotThrow( () -> TestEngine.safelyEmptyTestOwnedDir( null ) );
    }
}
