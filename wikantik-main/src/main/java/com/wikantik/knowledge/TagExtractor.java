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
package com.wikantik.knowledge;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * Extracts top-N keywords from text using log-dampened term frequency scoring
 * with Lucene's {@link EnglishAnalyzer} for stopword removal and Porter stemming.
 *
 * @since 1.0
 */
public final class TagExtractor {

    private static final Logger LOG = LoggerFactory.getLogger( TagExtractor.class );

    private TagExtractor() {}

    /**
     * Extracts the top {@code maxTags} keywords from the given text.
     *
     * <p>The algorithm:</p>
     * <ol>
     *   <li>Strips markdown via {@link NodeTextAssembler#stripMarkdown(String)}</li>
     *   <li>Tokenizes with {@link EnglishAnalyzer} (stopword removal + Porter stemming)</li>
     *   <li>Counts term frequencies, skipping single-character tokens</li>
     *   <li>Scores each term by log-dampened TF: {@code 1 + log(tf)}</li>
     *   <li>Returns the top {@code maxTags} terms sorted by score descending, as lowercase</li>
     * </ol>
     *
     * @param text    raw text or markdown; may be null or blank
     * @param maxTags maximum number of keywords to return
     * @return ordered list of keywords (at most {@code maxTags}), never null
     */
    public static List< String > extract( final String text, final int maxTags ) {
        if( text == null || text.isBlank() ) return List.of();

        final String stripped = NodeTextAssembler.stripMarkdown( text );
        if( stripped.isBlank() ) return List.of();

        final Map< String, Integer > termFreqs = new HashMap<>();
        try( Analyzer analyzer = new EnglishAnalyzer() ) {
            try( TokenStream stream = analyzer.tokenStream( "content", new StringReader( stripped ) ) ) {
                final CharTermAttribute termAttr = stream.addAttribute( CharTermAttribute.class );
                stream.reset();
                while( stream.incrementToken() ) {
                    final String term = termAttr.toString();
                    if( term.length() > 1 ) {
                        termFreqs.merge( term, 1, Integer::sum );
                    }
                }
                stream.end();
            }
        } catch( final IOException e ) {
            LOG.warn( "Unexpected IOException while tokenizing text for tag extraction: {}", e.getMessage() );
        }

        if( termFreqs.isEmpty() ) return List.of();

        // Score by log-dampened TF: 1 + log(tf)
        final List< Map.Entry< String, Integer > > entries = new ArrayList<>( termFreqs.entrySet() );
        entries.sort( ( a, b ) -> {
            final double scoreA = 1.0 + Math.log( a.getValue() );
            final double scoreB = 1.0 + Math.log( b.getValue() );
            // Descending by score, then ascending by term for deterministic ordering
            final int cmp = Double.compare( scoreB, scoreA );
            return cmp != 0 ? cmp : a.getKey().compareTo( b.getKey() );
        } );

        final int limit = Math.min( maxTags, entries.size() );
        final List< String > result = new ArrayList<>( limit );
        for( int i = 0; i < limit; i++ ) {
            result.add( entries.get( i ).getKey().toLowerCase( Locale.ROOT ) );
        }
        return Collections.unmodifiableList( result );
    }
}
