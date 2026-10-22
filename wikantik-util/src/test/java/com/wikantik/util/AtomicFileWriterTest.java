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
package com.wikantik.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

class AtomicFileWriterTest {

    private static long entries( final Path dir ) throws IOException {
        try( Stream< Path > s = Files.list( dir ) ) {
            return s.count();
        }
    }

    @Test
    void writesNewFile( @TempDir final Path dir ) throws IOException {
        final Path target = dir.resolve( "page.md" );
        AtomicFileWriter.write( target, "héllo".getBytes( StandardCharsets.UTF_8 ) );
        Assertions.assertEquals( "héllo", Files.readString( target ) );
        Assertions.assertEquals( 1, entries( dir ), "no temp file left behind" );
    }

    @Test
    void replacesExistingFile( @TempDir final Path dir ) throws IOException {
        final Path target = dir.resolve( "page.md" );
        Files.writeString( target, "old" );
        AtomicFileWriter.write( target, out -> out.write( "new".getBytes( StandardCharsets.UTF_8 ) ) );
        Assertions.assertEquals( "new", Files.readString( target ) );
        Assertions.assertEquals( 1, entries( dir ) );
    }

    @Test
    void failureMidWriteLeavesPreviousContentAndNoTempFile( @TempDir final Path dir ) throws IOException {
        final Path target = dir.resolve( "page.md" );
        Files.writeString( target, "previous" );

        final IOException ex = Assertions.assertThrows( IOException.class, () ->
            AtomicFileWriter.write( target, out -> {
                out.write( "partial".getBytes( StandardCharsets.UTF_8 ) );
                throw new IOException( "disk full" );
            } ) );

        Assertions.assertEquals( "disk full", ex.getMessage() );
        Assertions.assertEquals( "previous", Files.readString( target ), "target must never be partially written" );
        Assertions.assertEquals( 1, entries( dir ), "temp file must be deleted on failure" );
    }

    @Test
    void failureOnNewFileCreatesNothing( @TempDir final Path dir ) {
        final Path target = dir.resolve( "page.md" );
        Assertions.assertThrows( IOException.class, () ->
            AtomicFileWriter.write( target, out -> { throw new IOException( "boom" ); } ) );
        Assertions.assertFalse( Files.exists( target ) );
    }

    @Test
    void unwritableDirectoryThrows( @TempDir final Path dir ) throws IOException {
        Assumptions.assumeTrue( dir.toFile().setWritable( false ) );
        Assumptions.assumeFalse( dir.toFile().canWrite(), "permission bits not enforced here (running as root?)" );
        try {
            Assertions.assertThrows( IOException.class, () -> AtomicFileWriter.write( dir.resolve( "x.md" ), new byte[] { 1 } ) );
        } finally {
            dir.toFile().setWritable( true );
        }
    }

    @Test
    void writerThatClosesItsStreamStillGetsFsyncedAndMoved( @TempDir final Path dir ) throws IOException {
        final Path target = dir.resolve( "x.ser" );
        AtomicFileWriter.write( target, out -> {
            try( java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream( out ) ) {
                oos.writeUTF( "hi" );
            }
        } );
        try( java.io.ObjectInputStream in = new java.io.ObjectInputStream( Files.newInputStream( target ) ) ) {
            Assertions.assertEquals( "hi", in.readUTF() );
        }
    }

    @Test
    void storesProperties( @TempDir final Path dir ) throws IOException {
        final Path target = dir.resolve( "page.properties" );
        final Properties props = new Properties();
        props.setProperty( "1.author", "jake" );
        AtomicFileWriter.write( target, out -> props.store( out, "test" ) );
        final Properties read = new Properties();
        try( var in = Files.newInputStream( target ) ) {
            read.load( in );
        }
        Assertions.assertEquals( "jake", read.getProperty( "1.author" ) );
    }
}
