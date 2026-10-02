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
package com.wikantik.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpooledUploadTest {

    @TempDir
    Path dir;

    @Test
    void sweepStaleDeletesOnlyOldSpoolFiles() throws Exception {
        final Path old = Files.createFile( dir.resolve( "wikantik-import-old.zip" ) );
        final Path fresh = Files.createFile( dir.resolve( "wikantik-import-fresh.zip" ) );
        final Path other = Files.createFile( dir.resolve( "unrelated-old.zip" ) );
        final FileTime twoHoursAgo = FileTime.from( Instant.now().minus( Duration.ofHours( 2 ) ) );
        Files.setLastModifiedTime( old, twoHoursAgo );
        Files.setLastModifiedTime( other, twoHoursAgo );
        assertEquals( 1, SpooledUpload.sweepStale( dir, Duration.ofHours( 1 ) ) );
        assertFalse( Files.exists( old ) );
        assertTrue( Files.exists( fresh ) );
        assertTrue( Files.exists( other ) );
    }

    @Test
    void sweepStaleOnMissingDirIsHarmless() {
        assertEquals( 0, SpooledUpload.sweepStale( dir.resolve( "nope" ), Duration.ofHours( 1 ) ) );
    }

    @Test
    void spoolsAndHashes() throws Exception {
        final SpooledUpload u = SpooledUpload.spool( new ByteArrayInputStream( "abc".getBytes( UTF_8 ) ), "v.zip", 10 );
        try {
            assertEquals( 3, u.size() );
            assertEquals( "v.zip", u.originalName() );
            assertEquals( "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", u.sha256() );
            assertArrayEquals( "abc".getBytes( UTF_8 ), Files.readAllBytes( u.file() ) );
        } finally {
            u.delete();
        }
        assertFalse( Files.exists( u.file() ) );
    }

    @Test
    void overCapThrowsAndLeavesNoTempFile() throws Exception {
        final ImportLimitException e = assertThrows( ImportLimitException.class,
            () -> SpooledUpload.spool( dir, new ByteArrayInputStream( new byte[ 11 ] ), "v.zip", 10 ) );
        assertEquals( ImportLimits.PROP_MAX_UPLOAD_BYTES, e.limitKey() );
        assertTrue( e.getMessage().contains( "wikantik.import.maxUploadBytes" ) );
        assertEquals( "upload exceeds wikantik.import.maxUploadBytes (10)", e.getMessage() );
        try ( Stream< Path > left = Files.list( dir ) ) {
            assertEquals( 0, left.count(), "the spool file for the rejected upload must be removed" );
        }
    }

    @Test
    void exactlyAtCapIsAccepted() throws Exception {
        final SpooledUpload u = SpooledUpload.spool( new ByteArrayInputStream( new byte[ 10 ] ), "v.zip", 10 );
        try {
            assertEquals( 10, u.size() );
        } finally {
            u.delete();
        }
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        final SpooledUpload u = SpooledUpload.spool( new ByteArrayInputStream( new byte[ 1 ] ), "v.zip", 10 );
        u.delete();
        u.delete();
    }
}
