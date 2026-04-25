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
package com.wikantik.extractcli.mainpage;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks {@code docs/wikantik-pages/} (or any pages directory) once, builds a
 * canonical_id → page-descriptor index from frontmatter, then resolves every
 * pinned page in {@link PinsConfig} against that index. Pinned IDs that fail
 * to resolve are dropped and surfaced via {@link MainPageData#warnings()}.
 *
 * <p>This loader is offline by design — it does not depend on the running
 * StructuralIndexService or any database. The same frontmatter is the
 * authority for both, so the offline path produces equivalent output.</p>
 */
public final class MainPageDataLoader {

    /** A row in the canonical_id → frontmatter index. */
    record FrontmatterIndex( Map< String, Resolved > byCanonicalId ) {}

    /** Resolved page facts as carried in the index. */
    record Resolved( String slug, String title, String summary ) {}

    private MainPageDataLoader() {}

    /**
     * Build {@link MainPageData} from {@code pinsFile} and the frontmatter
     * found under {@code pagesDir}. Either argument may be missing; in that
     * case the result is empty (with no warnings — the caller decides whether
     * an empty config is an error).
     */
    public static MainPageData load( final Path pagesDir, final Path pinsFile ) throws IOException {
        final FrontmatterIndex index = indexPagesDirectory( pagesDir );
        final PinsConfig pins = Files.exists( pinsFile )
                ? PinsParser.parse( pinsFile )
                : new PinsConfig( "", "", List.of() );

        final List< String > warnings = new ArrayList<>();
        final List< MainPageData.Section > sections = new ArrayList<>( pins.sections().size() );
        for ( final PinsConfig.PinsSection ps : pins.sections() ) {
            final List< MainPageData.Page > pages = new ArrayList<>( ps.pages().size() );
            for ( final PinsConfig.PinsPage pp : ps.pages() ) {
                final Resolved r = index.byCanonicalId().get( pp.canonicalId() );
                if ( r == null ) {
                    warnings.add( "section '" + ps.label() + "': canonical_id "
                            + pp.canonicalId() + " does not resolve to any page (skipped)" );
                    continue;
                }
                final String summary = pp.summaryOverride() != null
                        ? pp.summaryOverride()
                        : r.summary();
                pages.add( new MainPageData.Page( pp.canonicalId(), r.slug(), r.title(), summary ) );
            }
            sections.add( new MainPageData.Section( ps.label(), ps.cluster(), pages ) );
        }
        return new MainPageData( pins.intro(), pins.footer(), sections, warnings );
    }

    /**
     * Walk {@code pagesDir} once and build a canonical_id → resolved-page index.
     * Pages without a {@code canonical_id} frontmatter field are silently skipped
     * — they cannot be referenced from pins anyway.
     */
    @SuppressWarnings( "unchecked" )
    static FrontmatterIndex indexPagesDirectory( final Path pagesDir ) throws IOException {
        final Map< String, Resolved > out = new HashMap<>();
        if ( !Files.isDirectory( pagesDir ) ) {
            return new FrontmatterIndex( out );
        }
        try ( Stream< Path > stream = Files.list( pagesDir ) ) {
            final List< Path > mdFiles = stream
                    .filter( Files::isRegularFile )
                    .filter( p -> p.getFileName().toString().endsWith( ".md" ) )
                    .toList();
            for ( final Path file : mdFiles ) {
                final String slug = stripMdExtension( file.getFileName().toString() );
                final ParsedPage parsed = FrontmatterParser.parse( Files.readString( file ) );
                final Map< String, Object > fm = parsed.metadata();
                final String canonicalId = stringOrNull( fm.get( "canonical_id" ) );
                if ( canonicalId == null ) {
                    continue;
                }
                final String title = firstNonBlank( stringOrNull( fm.get( "title" ) ), slug );
                final String summary = stringOrEmpty( fm.get( "summary" ) );
                out.put( canonicalId, new Resolved( slug, title, summary ) );
            }
        }
        return new FrontmatterIndex( out );
    }

    private static String stringOrNull( final Object o ) {
        if ( o == null ) {
            return null;
        }
        final String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String stringOrEmpty( final Object o ) {
        return o == null ? "" : o.toString().trim();
    }

    private static String firstNonBlank( final String a, final String b ) {
        return ( a == null || a.isBlank() ) ? b : a;
    }

    private static String stripMdExtension( final String filename ) {
        return filename.endsWith( ".md" ) ? filename.substring( 0, filename.length() - 3 ) : filename;
    }
}
