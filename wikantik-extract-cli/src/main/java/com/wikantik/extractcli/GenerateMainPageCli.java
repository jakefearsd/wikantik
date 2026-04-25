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

import com.wikantik.extractcli.mainpage.MainPageData;
import com.wikantik.extractcli.mainpage.MainPageDataLoader;
import com.wikantik.extractcli.mainpage.MainPageRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the Main.md generator. Two modes:
 *
 * <ul>
 *   <li>{@code --check}  (default for CI): reports whether the on-disk
 *       Main.md already matches what the generator would produce; exits
 *       0 on match, 2 on drift, 3 on missing inputs.</li>
 *   <li>{@code --write}  (dev / migration): overwrites Main.md with the
 *       generated content; exits 0 unless inputs are missing.</li>
 * </ul>
 *
 * <p>Both modes also surface any pin-resolution warnings on stderr without
 * failing — those warnings are advisory.</p>
 *
 * <p>CLI:
 * {@code generate-main-page <pagesDir> [--write|--check]}
 * </p>
 */
public final class GenerateMainPageCli {

    public enum Mode { CHECK, WRITE }

    public record Result( int exitCode, String summary ) {}

    public Result run( final Path pagesDir, final Mode mode ) throws IOException {
        if ( !Files.isDirectory( pagesDir ) ) {
            return new Result( 3, "pages directory does not exist: " + pagesDir );
        }
        final Path pinsFile  = pagesDir.resolve( "Main.pins.yaml" );
        final Path mainFile  = pagesDir.resolve( "Main.md" );

        final MainPageData data = MainPageDataLoader.load( pagesDir, pinsFile );
        for ( final String w : data.warnings() ) {
            System.err.println( "WARNING: " + w );
        }
        final String generated = new MainPageRenderer().render( data );

        if ( mode == Mode.WRITE ) {
            Files.writeString( mainFile, generated, StandardCharsets.UTF_8 );
            return new Result( 0, "wrote " + mainFile + " (" + generated.length() + " bytes)" );
        }

        // CHECK mode
        if ( !Files.exists( mainFile ) ) {
            return new Result( 2, "Main.md does not exist; run with --write to generate it." );
        }
        final String existing = Files.readString( mainFile, StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
        if ( existing.equals( generated ) ) {
            return new Result( 0, "Main.md is in sync with Main.pins.yaml." );
        }
        return new Result( 2,
                "Main.md is out of sync with Main.pins.yaml.\n"
                + "  Run `mvn package -pl wikantik-wikipages-builder -Dgenerate.main.write=true`\n"
                + "  (or `java ... GenerateMainPageCli " + pagesDir + " --write`) to regenerate." );
    }

    public static void main( final String[] args ) throws Exception {
        if ( args.length < 1 ) {
            System.err.println( "Usage: generate-main-page <pagesDir> [--write|--check]" );
            System.exit( 64 );
        }
        final Path pagesDir = Path.of( args[ 0 ] );
        Mode mode = Mode.CHECK;
        for ( int i = 1; i < args.length; i++ ) {
            switch ( args[ i ] ) {
                case "--write" -> mode = Mode.WRITE;
                case "--check" -> mode = Mode.CHECK;
                default -> {
                    System.err.println( "unknown flag: " + args[ i ] );
                    System.exit( 64 );
                }
            }
        }
        final Result result = new GenerateMainPageCli().run( pagesDir, mode );
        System.out.println( result.summary() );
        System.exit( result.exitCode() );
    }
}
