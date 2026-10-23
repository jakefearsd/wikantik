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
package com.wikantik.api.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Covers the jar-packaged branch of {@link Engine#findConfigFile}: a real file, made once, per name. */
class ConfigFileCopiesTest {

    private static URL jarEntry( final Path dir, final String entry, final String content ) throws Exception {
        final Path jar = dir.resolve( "res.jar" );
        try ( JarOutputStream out = new JarOutputStream( Files.newOutputStream( jar ) ) ) {
            out.putNextEntry( new JarEntry( entry ) );
            out.write( content.getBytes( StandardCharsets.UTF_8 ) );
            out.closeEntry();
        }
        return new URL( "jar:" + jar.toUri() + "!/" + entry );
    }

    @Test
    void copiesJarResourceToARealFileOnce( @TempDir final Path dir ) throws Exception {
        final URL resource = jarEntry( dir, "cfg-once.policy", "grant {};\n" );

        final File first = ConfigFileCopies.tempCopy( "cfg-once.policy", resource );
        final File second = ConfigFileCopies.tempCopy( "cfg-once.policy", resource );

        assertNotNull( first );
        assertEquals( "grant {};\n", Files.readString( first.toPath(), StandardCharsets.UTF_8 ) );
        assertSame( first, second, "repeated lookups must reuse the one temp copy, not accumulate files" );
    }

    @Test
    void returnsNullAndLeavesNoFileWhenResourceCannotBeRead( @TempDir final Path dir ) throws Exception {
        final URL missing = new URL( "jar:" + dir.resolve( "absent.jar" ).toUri() + "!/cfg-missing.policy" );

        assertNull( ConfigFileCopies.tempCopy( "cfg-missing.policy", missing ) );

        final File probe = File.createTempFile( "probe", "" );
        final File tmpDir = probe.getParentFile();
        org.junit.jupiter.api.Assertions.assertTrue( probe.delete() );
        final String[] leftovers = tmpDir.list( ( d, n ) -> n.startsWith( "temp.cfg-missing.policy" ) );
        assertEquals( 0, leftovers == null ? 0 : leftovers.length, "a failed copy must delete its half-made temp file" );
    }
}
