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
package com.wikantik.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObsidianVaultWriterTest {
    @Test void writesEntriesManifestAndReadmeWithMatchingHashes() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final String sha;
        try ( ObsidianVaultWriter w = new ObsidianVaultWriter( bos ) ) {
            sha = w.writeText( "a/Page.md", "hello" );
            w.writeStream( "_attachments/Page/x.bin", new ByteArrayInputStream( new byte[]{ 1, 2, 3 } ) );
            w.finish( new ExportManifest( 1, "https://w", "2026-09-29T00:00:00Z", Map.of(),
                    List.of( new ExportManifest.PageEntry( "Page", "01X", 3, "a/Page.md", sha ) ), List.of(), List.of( "warn one" ) ),
                    "# readme" );
        }
        final Map< String, byte[] > entries = unzip( bos.toByteArray() );
        assertEquals( Set.of( "a/Page.md", "_attachments/Page/x.bin", "Wikantik Export.md", ".wikantik/manifest.json" ), entries.keySet() );
        assertEquals( "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha ); // sha256("hello")
        final String manifest = new String( entries.get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
        assertTrue( manifest.contains( "\"sha256\": \"" + sha + "\"" ) );
        assertTrue( manifest.contains( "warn one" ) );
    }

    @Test void duplicatePathRejected() throws Exception {
        try ( ObsidianVaultWriter w = new ObsidianVaultWriter( new ByteArrayOutputStream() ) ) {
            w.writeText( "a.md", "1" );
            assertThrows( IllegalStateException.class, () -> w.writeText( "a.md", "2" ) );
        }
    }

    /** Zip-slip invariant: the writer refuses any entry name that could escape the extraction root. */
    @Test void traversalAndMalformedPathsRejected() throws Exception {
        try ( ObsidianVaultWriter w = new ObsidianVaultWriter( new ByteArrayOutputStream() ) ) {
            for ( final String bad : List.of( "../x.md", "a/../../x.md", "/etc/x.md", "a\\b.md", "a//b.md",
                    "./a.md", "a/./b.md", "a/..", "", "a/" ) ) {
                assertThrows( IllegalArgumentException.class, () -> w.writeText( bad, "x" ), bad );
                assertThrows( IllegalArgumentException.class,
                        () -> w.writeStream( bad, new ByteArrayInputStream( new byte[ 0 ] ) ), bad );
            }
            w.writeText( ".wikantik/ok.md", "fine" ); // a dot-prefixed name is not a "." segment
        }
    }

    static Map< String, byte[] > unzip( final byte[] zip ) throws IOException {
        final Map< String, byte[] > out = new LinkedHashMap<>();
        try ( ZipInputStream zin = new ZipInputStream( new ByteArrayInputStream( zip ) ) ) {
            for ( ZipEntry e; ( e = zin.getNextEntry() ) != null; ) { out.put( e.getName(), zin.readAllBytes() ); }
        }
        return out;
    }
}
