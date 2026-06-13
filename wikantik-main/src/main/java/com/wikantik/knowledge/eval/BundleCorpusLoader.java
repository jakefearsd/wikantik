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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.GoldSection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the frozen evaluation corpus CSV into {@link BundleEvalQuestion}s.
 * Rows sharing {@code query_id} form one question; {@code gold_heading_path}
 * is '{@code >}'-separated. Comment ({@code #}) and header lines are skipped.
 */
public final class BundleCorpusLoader {

    private BundleCorpusLoader() {}

    private record Row( String queryId, String query, BundleCategory category, GoldSection gold ) {}

    public static List< BundleEvalQuestion > load( final Path csv ) {
        try ( Reader r = Files.newBufferedReader( csv ) ) {
            return parse( r );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Cannot read corpus: " + csv, e );
        }
    }

    public static List< BundleEvalQuestion > parse( final Reader reader ) {
        final Map< String, List< Row > > byId = new LinkedHashMap<>();
        try ( BufferedReader br = new BufferedReader( reader ) ) {
            String line;
            while ( ( line = br.readLine() ) != null ) {
                final String trimmed = line.strip();
                if ( trimmed.isEmpty() || trimmed.startsWith( "#" ) ) continue;
                if ( trimmed.startsWith( "query_id," ) ) continue; // header
                final Row row = parseRow( trimmed );
                byId.computeIfAbsent( row.queryId(), k -> new ArrayList<>() ).add( row );
            }
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "Error parsing corpus", e );
        }

        final List< BundleEvalQuestion > out = new ArrayList<>( byId.size() );
        for ( final Map.Entry< String, List< Row > > e : byId.entrySet() ) {
            final List< Row > rows = e.getValue();
            final Row first = rows.get( 0 );
            final List< GoldSection > golds = new ArrayList<>( rows.size() );
            for ( final Row row : rows ) golds.add( row.gold() );
            out.add( new BundleEvalQuestion( first.queryId(), first.query(), first.category(), golds ) );
        }
        return out;
    }

    private static Row parseRow( final String line ) {
        // Simple CSV: corpus authors must avoid commas in fields (use a heading-path
        // separator of '>', not ','). 6 columns expected.
        final String[] c = line.split( ",", -1 );
        if ( c.length < 5 ) {
            throw new IllegalArgumentException( "Malformed corpus row (need >=5 columns): " + line );
        }
        final BundleCategory cat;
        try {
            cat = BundleCategory.valueOf( c[ 2 ].strip().toUpperCase( Locale.ROOT ) );
        } catch ( final IllegalArgumentException ex ) {
            throw new IllegalArgumentException( "Unknown category '" + c[ 2 ] + "' in row: " + line, ex );
        }
        final List< String > headingPath = new ArrayList<>();
        for ( final String h : c[ 4 ].strip().split( ">" ) ) {
            if ( !h.isBlank() ) headingPath.add( h.strip() );
        }
        return new Row( c[ 0 ].strip(), c[ 1 ].strip(), cat,
            new GoldSection( c[ 3 ].strip(), headingPath ) );
    }
}
