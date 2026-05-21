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
package com.wikantik.search.subsystem.lucene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No filesystem coupling beyond the JUnit temp directory; both implementations
 * just need an existing path to open against.
 */
class LuceneDirectoryFactoryTest {

    @Test
    void parseKind_nioYieldsFalse() {
        assertFalse( LuceneDirectoryFactory.parseKind( "nio" ) );
        assertFalse( LuceneDirectoryFactory.parseKind( "NIO" ) );
        assertFalse( LuceneDirectoryFactory.parseKind( " nio " ) );
    }

    @Test
    void parseKind_mmapYieldsTrue() {
        assertTrue( LuceneDirectoryFactory.parseKind( "mmap" ) );
        assertTrue( LuceneDirectoryFactory.parseKind( "MMAP" ) );
        assertTrue( LuceneDirectoryFactory.parseKind( " mmap " ) );
    }

    @Test
    void parseKind_nullOrBlankIsNioDefault() {
        assertFalse( LuceneDirectoryFactory.parseKind( null ) );
        assertFalse( LuceneDirectoryFactory.parseKind( "" ) );
        assertFalse( LuceneDirectoryFactory.parseKind( "   " ) );
    }

    @Test
    void parseKind_unknownThrows() {
        final IllegalArgumentException ex = assertThrows( IllegalArgumentException.class,
            () -> LuceneDirectoryFactory.parseKind( "ramdisk" ) );
        assertTrue( ex.getMessage().contains( "wikantik.search.lucene.directory.kind" ),
            "exception message should name the property: " + ex.getMessage() );
        assertTrue( ex.getMessage().contains( "ramdisk" ),
            "exception message should echo the bad value: " + ex.getMessage() );
    }

    @Test
    void open_nioReturnsNIOFSDirectory( @TempDir final Path tmp ) throws IOException {
        try ( Directory d = LuceneDirectoryFactory.open( tmp, false ) ) {
            assertInstanceOf( NIOFSDirectory.class, d );
        }
    }

    @Test
    void open_mmapReturnsMMapDirectory( @TempDir final Path tmp ) throws IOException {
        try ( Directory d = LuceneDirectoryFactory.open( tmp, true ) ) {
            assertInstanceOf( MMapDirectory.class, d );
        }
    }

    @Test
    void open_nullPathThrows() {
        assertThrows( IllegalArgumentException.class,
            () -> LuceneDirectoryFactory.open( null, false ) );
        assertThrows( IllegalArgumentException.class,
            () -> LuceneDirectoryFactory.open( null, true ) );
    }

    @Test
    void open_supportsBasicReadWriteRoundTrip( @TempDir final Path tmp ) throws IOException {
        // Round-trip a small file through each Directory impl to make sure
        // the basic IO contract works against the temp filesystem.
        for ( final boolean useMMap : new boolean[] { false, true } ) {
            try ( Directory d = LuceneDirectoryFactory.open( tmp, useMMap );
                  org.apache.lucene.store.IndexOutput out = d.createOutput( "probe.bin",
                      org.apache.lucene.store.IOContext.DEFAULT ) ) {
                out.writeBytes( new byte[] { 1, 2, 3, 4 }, 4 );
            }
            try ( Directory d = LuceneDirectoryFactory.open( tmp, useMMap );
                  org.apache.lucene.store.IndexInput in = d.openInput( "probe.bin",
                      org.apache.lucene.store.IOContext.DEFAULT ) ) {
                assertEquals( 4, in.length(), "round-trip file size should be 4 bytes" );
            }
            Files.deleteIfExists( tmp.resolve( "probe.bin" ) );
        }
    }
}
