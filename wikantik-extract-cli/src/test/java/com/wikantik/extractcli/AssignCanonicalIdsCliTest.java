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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AssignCanonicalIdsCliTest {

    @Test
    void dry_run_reports_missing_without_writing( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "B.md" ), "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n---\nbody" );

        final var result = new AssignCanonicalIdsCli().run( tmp, /* write */ false );

        assertEquals( 2, result.scanned() );
        assertEquals( 1, result.missing() );
        assertEquals( 0, result.updated() );
        assertEquals( "---\ntitle: A\n---\nbody",
                Files.readString( tmp.resolve( "A.md" ) ) );
    }

    @Test
    void write_mode_assigns_unique_canonical_ids( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "B.md" ), "---\ntitle: B\n---\nbody" );

        final var result = new AssignCanonicalIdsCli().run( tmp, /* write */ true );

        assertEquals( 2, result.updated() );
        final String a = Files.readString( tmp.resolve( "A.md" ) );
        final String b = Files.readString( tmp.resolve( "B.md" ) );
        assertTrue( a.contains( "canonical_id:" ) );
        assertTrue( b.contains( "canonical_id:" ) );
        assertNotEquals(
                a.lines().filter( l -> l.startsWith( "canonical_id:" ) ).findFirst().orElseThrow(),
                b.lines().filter( l -> l.startsWith( "canonical_id:" ) ).findFirst().orElseThrow() );
    }

    @Test
    void write_mode_is_idempotent( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );

        new AssignCanonicalIdsCli().run( tmp, true );
        final String afterFirst = Files.readString( tmp.resolve( "A.md" ) );

        final var secondResult = new AssignCanonicalIdsCli().run( tmp, true );
        final String afterSecond = Files.readString( tmp.resolve( "A.md" ) );

        assertEquals( 0, secondResult.updated() );
        assertEquals( afterFirst, afterSecond );
    }

    @Test
    void skips_non_md_files( @TempDir final Path tmp ) throws Exception {
        Files.writeString( tmp.resolve( "A.md" ), "---\ntitle: A\n---\nbody" );
        Files.writeString( tmp.resolve( "ignored.properties" ), "key=value" );

        final var result = new AssignCanonicalIdsCli().run( tmp, true );
        assertEquals( 1, result.scanned() );
        assertEquals( "key=value", Files.readString( tmp.resolve( "ignored.properties" ) ) );
    }
}
