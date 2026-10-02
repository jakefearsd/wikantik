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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultArchiveReaderTest {

    private VaultArchive read( final byte[] zip ) throws Exception {
        return read( zip, ImportLimits.defaults() );
    }

    private VaultArchive read( final byte[] zip, final ImportLimits limits ) throws Exception {
        final Path p = TestVaults.write( zip );
        try {
            return new VaultArchiveReader( limits ).read( p );
        } finally {
            Files.deleteIfExists( p );
        }
    }

    private static Map< String, String > ordered( final String... kv ) {
        final Map< String, String > m = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @Test
    void readsNotesAndFilesInPathOrder() throws Exception {
        final VaultArchive a = read( TestVaults.zipText( ordered( "b/Note.md", "# B", "A.md", "# A", "b/img.png", "x" ) ) );
        assertEquals( List.of( "A.md", "b/Note.md" ), a.notes().stream().map( VaultNote::path ).toList() );
        assertEquals( "# A", a.notes().get( 0 ).text() );
        assertEquals( List.of( "b/img.png" ), a.files().stream().map( VaultFile::path ).toList() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "../evil.md", "a/../../evil.md", "/abs.md", "C:/x.md", "a/./b.md", "a//b.md" } )
    void zipSlipRejected( final String name ) {
        final VaultArchiveException e = assertThrows( VaultArchiveException.class,
            () -> read( TestVaults.zipText( ordered( name, "x" ) ) ) );
        assertTrue( e.getMessage().contains( name ), e.getMessage() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "a\tb.md", "line\nbreak.md", "dir\r/x.md", "Folder\u0001/n.md", "x\u001f.png", "del\u007f.md" } )
    void controlCharacterInNameRejected( final String name ) {
        final VaultArchiveException e = assertThrows( VaultArchiveException.class,
            () -> read( TestVaults.zipText( ordered( name, "x" ) ) ) );
        assertTrue( e.getMessage().contains( "control character" ), e.getMessage() );
    }

    @Test
    void backslashNameRejected() {
        assertThrows( VaultArchiveException.class,
            () -> read( TestVaults.zipWithRawName( "Notes\\A.md".getBytes( US_ASCII ), "x".getBytes( UTF_8 ) ) ) );
    }

    @Test
    void cp437NameRejected() {
        final VaultArchiveException e = assertThrows( VaultArchiveException.class,
            () -> read( TestVaults.zipWithRawName( new byte[]{ 'M', ( byte ) 0x81, '.', 'm', 'd' }, "x".getBytes( UTF_8 ) ) ) );
        assertTrue( e.getMessage().contains( "UTF-8" ) );
    }

    @Test
    void nulInNameRejected() {
        assertThrows( VaultArchiveException.class,
            () -> read( TestVaults.zipWithRawName( new byte[]{ 'a', 0, '.', 'm', 'd' }, "x".getBytes( UTF_8 ) ) ) );
    }

    @Test
    void bombRatioRejected() {
        final byte[] zip = TestVaults.zip( Map.of( "big.png", new byte[ 2 * 1024 * 1024 ] ) );
        assertTrue( assertThrows( VaultArchiveException.class, () -> read( zip ) ).getMessage().contains( "100:1" ) );
    }

    @Test
    void incompressibleLargeFileIsNotABomb() throws Exception {
        final byte[] img = new byte[ 3 * 1024 * 1024 ];
        new Random( 1 ).nextBytes( img );
        assertEquals( 1, read( TestVaults.zip( Map.of( "big.png", img ) ) ).files().size() );
    }

    @Test
    void uncompressedCapIs413Limit() throws Exception {
        final ImportLimits tiny = new ImportLimits( 1_000_000, 10, 100, 100, 1, 262144 );
        final Path p = TestVaults.write( TestVaults.zipText( Map.of( "A.md", "0123456789ABC" ) ) );
        try {
            final ImportLimitException e = assertThrows( ImportLimitException.class,
                () -> new VaultArchiveReader( tiny ).read( p ) );
            assertEquals( ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES, e.limitKey() );
        } finally {
            Files.deleteIfExists( p );
        }
    }

    @Test
    void passOneStopsEarlyOnLimit() {
        // A truncated bomb: reading it to the end would raise EOFException, so only an early stop
        // (during the names pass) can yield the limit exception.
        final byte[] full = TestVaults.zip( Map.of( "big.bin", new byte[ 10 * 1024 * 1024 ] ) );
        final byte[] cut = java.util.Arrays.copyOf( full, full.length / 2 );
        final ImportLimits tiny = new ImportLimits( 1_000_000, 1000, 100, 100, 1, 262144 );
        final ImportLimitException e = assertThrows( ImportLimitException.class, () -> read( cut, tiny ) );
        assertEquals( ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES, e.limitKey() );
    }

    @Test
    void passOneStopsEarlyOnRatio() {
        final byte[] full = TestVaults.zip( Map.of( "big.bin", new byte[ 10 * 1024 * 1024 ] ) );
        final byte[] cut = java.util.Arrays.copyOf( full, full.length / 2 );
        final VaultArchiveException e = assertThrows( VaultArchiveException.class, () -> read( cut ) );
        assertTrue( e.getMessage().contains( "100:1" ) && e.getMessage().contains( "big.bin" ), e.getMessage() );
    }

    @Test
    void truncatedZipIsMalformedNotAnIoError() {
        final byte[] full = TestVaults.zipText( Map.of( "A.md", "# hello ".repeat( 500 ) ) );
        final byte[] cut = java.util.Arrays.copyOf( full, full.length / 2 );
        final VaultArchiveException e = assertThrows( VaultArchiveException.class, () -> read( cut ) );
        assertTrue( e.getMessage().startsWith( "malformed zip" ), e.getMessage() );
    }

    @Test
    void slipNameRejectedBeforeLaterEntriesAreInflated() {
        // The later entry is a bomb; reaching it first would raise the 100:1 error instead.
        final Map< String, byte[] > m = new LinkedHashMap<>();
        m.put( "../evil.md", new byte[ 1 ] );
        m.put( "big.bin", new byte[ 10 * 1024 * 1024 ] );
        final byte[] full = TestVaults.zip( m );
        final VaultArchiveException e = assertThrows( VaultArchiveException.class, () -> read( full ) );
        assertTrue( e.getMessage().contains( "unsafe zip entry" ) );
    }

    @Test
    void entryCapIs413Limit() {
        final ImportLimits two = new ImportLimits( 1_000_000, 1_000_000, 2, 100, 1, 262144 );
        final byte[] zip = TestVaults.zipText( ordered( "A.md", "a", "B.md", "b", "C.md", "c" ) );
        final ImportLimitException e = assertThrows( ImportLimitException.class, () -> read( zip, two ) );
        assertEquals( ImportLimits.PROP_MAX_ENTRIES, e.limitKey() );
    }

    @Test
    void pageCapRejectedInPassOneBeforeAnyNoteIsBuffered() throws Exception {
        final ImportLimits two = new ImportLimits( 1_000_000, 10_000_000, 100, 2, 1, 262144 );
        final Map< String, String > m = ordered( "A.md", "a".repeat( 100_000 ), "B.md", "b".repeat( 100_000 ),
            "C.md", "c".repeat( 100_000 ) );
        final Path p = TestVaults.write( TestVaults.zipText( m ) );
        try {
            final VaultArchiveReader reader = new VaultArchiveReader( two );
            final ImportLimitException e = assertThrows( ImportLimitException.class, () -> reader.read( p ) );
            assertEquals( ImportLimits.PROP_MAX_PAGES, e.limitKey() );
            assertEquals( 0, reader.bufferedNoteBytes(), "no note text may be buffered before the page cap is checked" );
        } finally {
            Files.deleteIfExists( p );
        }
    }

    @Test
    void pageCapCountsOnlyImportableNotes() throws Exception {
        final ImportLimits two = new ImportLimits( 1_000_000, 10_000_000, 100, 2, 1, 262144 );
        final VaultArchive a = read( TestVaults.zipText( ordered( "V/A.md", "a", "V/B.md", "b",
            "V/Wikantik Export.md", "x", "V/.trash/C.md", "c", "__MACOSX/V/._A.md", "m" ) ), two );
        assertEquals( 2, a.notes().size() );
    }

    @Test
    void noteTextBudgetIs413Limit() throws Exception {
        final ImportLimits small = new ImportLimits( 1_000_000, 10_000_000, 100, 100, 1, 262144, 150, 2 );
        final ImportLimitException e = assertThrows( ImportLimitException.class,
            () -> read( TestVaults.zipText( ordered( "A.md", "a".repeat( 100 ), "B.md", "b".repeat( 100 ) ) ), small ) );
        assertEquals( ImportLimits.PROP_MAX_NOTE_TEXT_BYTES, e.limitKey() );
        // Only buffered note text counts: a large non-note file does not use the budget.
        assertEquals( 1, read( TestVaults.zipText( ordered( "A.md", "a".repeat( 100 ), "img.png", "x".repeat( 500 ) ) ),
            small ).notes().size() );
    }

    @Test
    void wrapperFolderStripped() throws Exception {
        final VaultArchive a = read( TestVaults.zipText( ordered(
            "MyVault/A.md", "a", "MyVault/.obsidian/app.json", "{}", "__MACOSX/MyVault/._A.md", "x" ) ) );
        assertEquals( "A.md", a.notes().get( 0 ).path() );
        assertEquals( 2, a.ignoredEntries() );
    }

    @Test
    void noWrapperWhenTopLevelFilesDiffer() throws Exception {
        final VaultArchive a = read( TestVaults.zipText( ordered( "A.md", "a", "Sub/B.md", "b" ) ) );
        assertEquals( List.of( "A.md", "Sub/B.md" ), a.notes().stream().map( VaultNote::path ).toList() );
    }

    @Test
    void ignoredPaths() throws Exception {
        final VaultArchive a = read( TestVaults.zipText( ordered(
            ".obsidian/x", "1", ".trash/y.md", "2", ".git/HEAD", "3", ".DS_Store", "4", "sub/.hidden.md", "5",
            ".wikantik/manifest.json", "6", "Wikantik Export.md", "7", "sub/Wikantik Export.md", "8", "keep.md", "k" ) ) );
        assertEquals( List.of( "keep.md", "sub/Wikantik Export.md" ), a.notes().stream().map( VaultNote::path ).toList() );
        assertEquals( 7, a.ignoredEntries() );
    }

    @Test
    void oversizedNoteIsNotBuffered() throws Exception {
        final ImportLimits l = new ImportLimits( 1_000_000, 1_000_000, 100, 100, 1, 4 );
        final VaultNote n = read( TestVaults.zipText( Map.of( "A.md", "123456" ) ), l ).notes().get( 0 );
        assertTrue( n.oversized() );
        assertEquals( 6, n.size() );
    }

    @Test
    void largeNonNoteIsListedWithSizeAndRawEntryName() throws Exception {
        final byte[] img = new byte[ 3 * 1024 * 1024 ];
        new Random( 1 ).nextBytes( img );
        final Map< String, byte[] > m = new LinkedHashMap<>();
        m.put( "big.png", img );
        m.put( "A.md", "a".getBytes( UTF_8 ) );
        final VaultArchive a = read( TestVaults.zip( m ) );
        assertEquals( img.length, a.files().get( 0 ).size() );
        assertEquals( "big.png", a.files().get( 0 ).entryName() );
    }

    @Test
    void bomIsStrippedAndUnicodeKept() throws Exception {
        final VaultArchive a = read( TestVaults.zipText( Map.of( "Caf\u00e9.md", "\uFEFFcr\u00e8me" ) ) );
        assertEquals( "Caf\u00e9.md", a.notes().get( 0 ).path() );
        assertEquals( "cr\u00e8me", a.notes().get( 0 ).text() );
        assertFalse( a.notes().get( 0 ).oversized() );
    }

    @Test
    void notAZipRejected() {
        final VaultArchiveException e = assertThrows( VaultArchiveException.class,
            () -> read( "hello".getBytes( UTF_8 ) ) );
        assertTrue( e.getMessage().contains( "not a zip archive" ) );
    }

    @Test
    void emptyZipIsEmptyArchive() throws Exception {
        final VaultArchive a = read( TestVaults.zip( Map.of() ) );
        assertTrue( a.notes().isEmpty() );
        assertTrue( a.files().isEmpty() );
    }

    @Test
    void vaultPathsHelpers() {
        assertEquals( "C.md", VaultPaths.basename( "a/b/C.md" ) );
        assertEquals( "a/b", VaultPaths.parentFolder( "a/b/C.md" ) );
        assertEquals( "", VaultPaths.parentFolder( "C.md" ) );
        assertEquals( "a/C", VaultPaths.withoutMd( "a/C.MD" ) );
        assertEquals( "a/C.png", VaultPaths.withoutMd( "a/C.png" ) );
        assertTrue( VaultPaths.isNote( "x.Md" ) );
        assertFalse( VaultPaths.isNote( "x.txt" ) );
        assertTrue( VaultPaths.ORDER.compare( "a", "B" ) < 0 );
    }
}
