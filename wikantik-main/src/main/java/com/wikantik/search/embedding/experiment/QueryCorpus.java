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
package com.wikantik.search.embedding.experiment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@code eval/retrieval-queries.csv} seed dataset. The file has
 * three columns — {@code query,ideal_page,notes} — where {@code notes} is the
 * category tag ({@code indirect}, {@code specific}, etc.). Lines starting with
 * {@code #} are comments; the first non-comment row is the header and is
 * discarded.
 */
public final class QueryCorpus {

    public record EvalQuery( String query, String idealPage, String category ) {}

    private QueryCorpus() {}

    public static List< EvalQuery > load( final Path csv ) throws IOException {
        try( final Reader r = Files.newBufferedReader( csv ) ) {
            return parse( r );
        }
    }

    public static List< EvalQuery > parseString( final String csv ) {
        try {
            return parse( new StringReader( csv ) );
        } catch( final IOException e ) {
            throw new IllegalStateException( "unexpected IO on StringReader", e );
        }
    }

    public static List< EvalQuery > parse( final Reader reader ) throws IOException {
        final List< EvalQuery > out = new ArrayList<>();
        boolean headerSeen = false;
        try( final BufferedReader r = new BufferedReader( reader ) ) {
            String line;
            while( ( line = r.readLine() ) != null ) {
                if( line.isBlank() ) continue;
                if( line.startsWith( "#" ) ) continue;
                if( !headerSeen ) {
                    headerSeen = true;
                    continue;
                }
                final List< String > fields = splitCsv( line );
                if( fields.size() < 3 ) {
                    throw new IOException( "expected 3 columns, got " + fields.size() + " in: " + line );
                }
                out.add( new EvalQuery( fields.get( 0 ), fields.get( 1 ), fields.get( 2 ) ) );
            }
        }
        return out;
    }

    /**
     * Minimal CSV splitter: supports double-quoted fields with {@code ""} as
     * an escaped quote. Does not try to be RFC-4180-complete — the seed file
     * is controlled and simple.
     */
    static List< String > splitCsv( final String line ) {
        final List< String > out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for( int i = 0; i < line.length(); i++ ) {
            final char c = line.charAt( i );
            if( inQuotes ) {
                if( c == '"' ) {
                    if( i + 1 < line.length() && line.charAt( i + 1 ) == '"' ) {
                        cur.append( '"' );
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append( c );
                }
            } else {
                if( c == ',' ) {
                    out.add( cur.toString().trim() );
                    cur.setLength( 0 );
                } else if( c == '"' && cur.length() == 0 ) {
                    inQuotes = true;
                } else {
                    cur.append( c );
                }
            }
        }
        out.add( cur.toString().trim() );
        return out;
    }
}
