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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathScannerTest {

    @Test
    void aDirectoryIsWalkedRecursivelyAndEntriesArePrefixedWithTheRootPackage( @TempDir final Path dir ) throws IOException {
        Files.writeString( dir.resolve( "top.txt" ), "x", StandardCharsets.UTF_8 );
        Files.createDirectories( dir.resolve( "sub" ) );
        Files.writeString( dir.resolve( "sub" ).resolve( "deep.txt" ), "y", StandardCharsets.UTF_8 );
        final List< String > results = new ArrayList<>();
        ClasspathScanner.fileEntriesUnder( results, dir.toFile(), "root/pkg" );
        assertEquals( List.of( "root/pkg/sub/deep.txt", "root/pkg/top.txt" ), results.stream().sorted().toList() );
    }

    @Test
    void aPlainFileIsStoredByItsOwnName( @TempDir final Path dir ) throws IOException {
        final Path file = Files.writeString( dir.resolve( "only.txt" ), "x", StandardCharsets.UTF_8 );
        final List< String > results = new ArrayList<>();
        ClasspathScanner.fileEntriesUnder( results, file.toFile(), "root" );
        assertEquals( List.of( "only.txt" ), results );
    }

    @Test
    void jarEntriesUnderListsFilesUnderTheRootAndSkipsDirectoriesAndOthers( @TempDir final Path dir ) throws IOException {
        final File jar = dir.resolve( "t.jar" ).toFile();
        try( JarOutputStream out = new JarOutputStream( Files.newOutputStream( jar.toPath() ) ) ) {
            for( final String name : new String[] { "root/", "root/a.txt", "root/sub/", "root/sub/b.txt", "other/c.txt" } ) {
                out.putNextEntry( new JarEntry( name ) );
                out.closeEntry();
            }
        }
        final JarURLConnection con = ( JarURLConnection )URI.create( "jar:" + jar.toURI() + "!/" ).toURL().openConnection();
        final List< String > results = new ArrayList<>();
        ClasspathScanner.jarEntriesUnder( results, con, "root" );
        assertEquals( List.of( "root/a.txt", "root/sub/b.txt" ), results );
    }

    @Test
    void aPackageOnTheFileClasspathIsScanned() {
        final List< String > results = ClasspathScanner.classpathEntriesUnder( "com/wikantik/util" );
        assertTrue( results.contains( "com/wikantik/util/TextUtil.class" ), results.toString() );
    }

    @Test
    void aPackageInsideAJarIsScanned() {
        final List< String > results = ClasspathScanner.classpathEntriesUnder( "org/junit/jupiter/api/io" );
        assertTrue( results.contains( "org/junit/jupiter/api/io/TempDir.class" ), results.toString() );
    }

    @Test
    void anEmptyOrUnknownPackageFindsNothing() {
        assertEquals( List.of(), ClasspathScanner.classpathEntriesUnder( "" ) );
        assertEquals( List.of(), ClasspathScanner.classpathEntriesUnder( "no/such/pkg/anywhere" ) );
    }
}
