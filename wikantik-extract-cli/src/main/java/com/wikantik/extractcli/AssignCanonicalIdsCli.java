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

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.frontmatter.FrontmatterParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks a wiki pages directory, assigns a ULID {@code canonical_id} to every
 * Markdown file that lacks one, and (optionally) rewrites the file in place.
 *
 * <p>Run modes:</p>
 * <ul>
 *   <li>Dry-run (default): scans and reports, does not write.</li>
 *   <li>--write: writes assignments back to disk, idempotent across reruns.</li>
 * </ul>
 *
 * <p>CLI entry:
 * {@code java -cp wikantik-extract-cli.jar com.wikantik.extractcli.AssignCanonicalIdsCli <pagesDir> [--write]}
 * </p>
 */
public class AssignCanonicalIdsCli {

    public record Result( int scanned, int missing, int updated ) {}

    public Result run( final Path pagesDir, final boolean write ) throws IOException {
        int scanned = 0;
        int missing = 0;
        int updated = 0;

        try ( Stream< Path > stream = Files.list( pagesDir ) ) {
            final List< Path > mdFiles = stream
                    .filter( Files::isRegularFile )
                    .filter( p -> p.getFileName().toString().endsWith( ".md" ) )
                    .sorted()
                    .toList();

            for ( final Path file : mdFiles ) {
                scanned++;
                final String content = Files.readString( file );
                // Parse the real frontmatter block — a substring check would false-positive on
                // any body that *mentions* canonical_id (design docs, code examples, runbooks).
                final var parsed = FrontmatterParser.parse( content );
                final Object existing = parsed.metadata().get( "canonical_id" );
                if ( existing != null && !existing.toString().isBlank() ) {
                    continue;
                }
                missing++;
                if ( write ) {
                    final String newId = UlidCreator.getUlid().toString();
                    final String rewritten = FrontmatterRewriter.assignCanonicalId( content, newId );
                    if ( !rewritten.equals( content ) ) {
                        Files.writeString( file, rewritten );
                        updated++;
                    }
                }
            }
        }

        return new Result( scanned, missing, updated );
    }

    public static void main( final String[] args ) throws Exception {
        if ( args.length < 1 ) {
            System.err.println( "Usage: assign-canonical-ids <pagesDir> [--write]" );
            System.exit( 2 );
        }
        final Path pagesDir = Path.of( args[ 0 ] );
        final boolean write = args.length > 1 && "--write".equals( args[ 1 ] );

        final Result r = new AssignCanonicalIdsCli().run( pagesDir, write );
        System.out.printf( "scanned=%d  missing=%d  updated=%d  mode=%s%n",
                r.scanned(), r.missing(), r.updated(), write ? "WRITE" : "DRY-RUN" );
    }
}
