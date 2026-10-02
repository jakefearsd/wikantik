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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpooledUploadTest {

    private static int countSpoolFiles() throws IOException {
        int n = 0;
        try ( DirectoryStream< Path > ds = Files.newDirectoryStream(
                Paths.get( System.getProperty( "java.io.tmpdir" ) ), "wikantik-import-*" ) ) {
            for ( final Path ignored : ds ) {
                n++;
            }
        }
        return n;
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
        final int before = countSpoolFiles();
        final ImportLimitException e = assertThrows( ImportLimitException.class,
            () -> SpooledUpload.spool( new ByteArrayInputStream( new byte[ 11 ] ), "v.zip", 10 ) );
        assertEquals( ImportLimits.PROP_MAX_UPLOAD_BYTES, e.limitKey() );
        assertTrue( e.getMessage().contains( "wikantik.import.maxUploadBytes" ) );
        assertEquals( "upload exceeds wikantik.import.maxUploadBytes (10)", e.getMessage() );
        assertEquals( before, countSpoolFiles() );
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
