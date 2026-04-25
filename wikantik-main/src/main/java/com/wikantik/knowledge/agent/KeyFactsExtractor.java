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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.KeyFact;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pulls {@link KeyFact} entries from a page in two passes:
 *
 * <ol>
 *   <li>If frontmatter has a {@code key_facts:} list, every non-null entry is
 *       passed through verbatim ({@code toString()}) with
 *       {@code sourceHint = "frontmatter"}.
 *   <li>Otherwise, the first {@link #MAX_PARAGRAPHS} paragraphs are split into
 *       sentences and the first {@link #MAX_FACTS} sentences containing
 *       <em>both</em> a verb-shaped token and a capitalised word or a numeric
 *       value are surfaced with {@code sourceHint = "body"}.
 * </ol>
 *
 * <p>The heuristic is deliberately cheap. It is not a competitor to a real
 * summariser — its job is to surface "definite-sounding" sentences when the
 * author hasn't authored {@code key_facts} themselves.</p>
 */
public final class KeyFactsExtractor {

    private static final Logger LOG = LogManager.getLogger( KeyFactsExtractor.class );

    public static final int MAX_FACTS = 6;
    public static final int MAX_PARAGRAPHS = 3;
    private static final int MAX_FACT_CHARS = 240;

    private static final Pattern SENTENCE_SPLIT = Pattern.compile( "(?<=[.!?])\\s+" );
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile( "\\r?\\n\\s*\\r?\\n" );
    // Verb-shaped: small set of common copula/auxiliaries, or any -s/-ed/-ing token.
    private static final Pattern HAS_VERB = Pattern.compile(
            "\\b(?:is|are|was|were|has|have|had|does|do|did|can|will|" +
            "[A-Za-z]+(?:s|ed|ing))\\b" );
    private static final Pattern HAS_NUMBER = Pattern.compile( "\\b\\d+(?:[.,]\\d+)?\\b" );
    private static final Pattern HAS_CAPITAL_WORD = Pattern.compile( "\\b[A-Z][A-Za-z0-9]+\\b" );

    public List< KeyFact > extract( final Map< String, Object > frontmatter, final String body ) {
        // 1. Frontmatter-authored.
        if ( frontmatter != null ) {
            final Object raw = frontmatter.get( "key_facts" );
            if ( raw instanceof List< ? > list ) {
                final List< KeyFact > out = new ArrayList<>();
                for ( final Object o : list ) {
                    if ( o == null ) continue;
                    final String s = o.toString().trim();
                    if ( s.isEmpty() ) continue;
                    out.add( new KeyFact( truncate( s ), "frontmatter" ) );
                    if ( out.size() >= MAX_FACTS ) break;
                }
                if ( !out.isEmpty() ) {
                    return List.copyOf( out );
                }
            }
        }

        // 2. Heuristic over body paragraphs.
        if ( body == null || body.isEmpty() ) {
            return List.of();
        }
        final String[] paragraphs = PARAGRAPH_SPLIT.split( body, -1 );
        final List< KeyFact > out = new ArrayList<>();
        final int paragraphLimit = Math.min( paragraphs.length, MAX_PARAGRAPHS );
        for ( int i = 0; i < paragraphLimit; i++ ) {
            final String para = paragraphs[ i ].strip();
            if ( para.isEmpty() ) continue;
            for ( final String sentence : SENTENCE_SPLIT.split( para ) ) {
                final String s = sentence.trim();
                if ( s.length() < 20 ) continue;
                if ( !HAS_VERB.matcher( s ).find() ) continue;
                if ( !HAS_NUMBER.matcher( s ).find() && !HAS_CAPITAL_WORD.matcher( s ).find() ) continue;
                out.add( new KeyFact( truncate( s ), "body" ) );
                if ( out.size() >= MAX_FACTS ) {
                    return List.copyOf( out );
                }
            }
        }
        return List.copyOf( out );
    }

    private static String truncate( final String s ) {
        if ( s.length() <= MAX_FACT_CHARS ) return s;
        return s.substring( 0, MAX_FACT_CHARS - 1 ).trim() + "…";
    }
}
