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

import com.wikantik.api.frontmatter.FrontmatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 *  Reads the repository corpus ({@code docs/wikantik-pages/}) into a {@link CorpusSnapshot}.
 *
 *  <p>Every failure is recorded rather than swallowed: an unreadable directory or an
 *  unparseable page marks the snapshot incomplete, so {@link CorpusDiff} will refuse to
 *  compare it instead of reporting the unread pages as "missing from production".</p>
 */
public final class LocalCorpusSource {

    private static final String MARKDOWN_EXT = ".md";

    private final Path directory;

    public LocalCorpusSource( final Path directory ) {
        this.directory = directory;
    }

    /**
     *  Loads every {@code .md} page in the directory (non-recursively, so the
     *  {@code OLD/} version store is naturally skipped).
     */
    public CorpusSnapshot load() {
        final Map< String, PageFacts > pages = new LinkedHashMap<>();
        final List< String > errors = new ArrayList<>();

        if ( !Files.isDirectory( directory ) ) {
            errors.add( "corpus directory not readable: " + directory );
            return new CorpusSnapshot( "repo", pages, errors );
        }

        try ( Stream< Path > files = Files.list( directory ) ) {
            files.filter( p -> p.getFileName().toString().endsWith( MARKDOWN_EXT ) )
                 .sorted()
                 .forEach( p -> readInto( p, pages, errors ) );
        } catch ( final IOException | UncheckedIOException e ) {
            errors.add( "failed to list " + directory + ": " + e.getMessage() );
        }
        return new CorpusSnapshot( "repo", pages, errors );
    }

    private void readInto( final Path file, final Map< String, PageFacts > pages,
                            final List< String > errors ) {
        final String slug = unmangle( file.getFileName().toString() );
        try {
            final var parsed = FrontmatterParser.parse( Files.readString( file ) );
            final Map< String, Object > meta = parsed.metadata();
            pages.put( slug, new PageFacts( slug,
                                             str( meta.get( "canonical_id" ) ),
                                             str( meta.get( "cluster" ) ),
                                             str( meta.get( "type" ) ) ) );
        } catch ( final IOException | RuntimeException e ) {
            errors.add( "failed to read " + file.getFileName() + ": " + e.getMessage() );
        }
    }

    /**
     *  Reverses {@code AbstractFileProvider.mangleName()} — page names are URL-encoded on
     *  disk, so {@code AgentLoops+Hub.md} is the page {@code "AgentLoops Hub"}. Comparing raw
     *  filenames would report every spaced-name page as diverging from production.
     */
    private static String unmangle( final String fileName ) {
        final String stem = fileName.substring( 0, fileName.length() - MARKDOWN_EXT.length() );
        try {
            return URLDecoder.decode( stem, StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException e ) {
            // Not a valid encoding — the literal name is the best available answer.
            return stem;
        }
    }

    private static String str( final Object raw ) {
        if ( raw == null ) {
            return null;
        }
        final String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
