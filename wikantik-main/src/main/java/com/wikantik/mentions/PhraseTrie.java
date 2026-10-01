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
package com.wikantik.mentions;

import com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A phrase trie keyed by lowercase word tokens; terminals name the target page. */
final class PhraseTrie {

    static final Pattern TOKEN = Pattern.compile( "[\\p{L}\\p{N}]+" );

    /** The longest phrase match starting at a token: its target and the index of its last token. */
    record Match( TitleEntry target, int lastToken ) {}

    private final Map< String, PhraseTrie > next = new HashMap<>();
    private TitleEntry target;

    private PhraseTrie() {}

    /** Builds the trie, skipping the page itself, already-linked pages, common single words and short phrases. */
    static PhraseTrie build( final List< TitleEntry > entries, final String selfPage, final Set< String > linked ) {
        final PhraseTrie root = new PhraseTrie();
        final List< TitleEntry > sorted = entries.stream()
                .sorted( Comparator.comparing( TitleEntry::slug ) ).toList();  // deterministic winner on collisions
        for ( final TitleEntry e : sorted ) {
            if ( e.slug().equalsIgnoreCase( selfPage ) || linked.contains( e.slug().toLowerCase( Locale.ROOT ) ) ) {
                continue;
            }
            for ( final String phrase : e.phrases() ) {
                root.add( phrase, e );
            }
        }
        return root;
    }

    private void add( final String phrase, final TitleEntry entry ) {
        final List< String > tokens = tokens( phrase );
        if ( tokens.isEmpty() || phrase.trim().length() < MentionScanner.MIN_PHRASE_CHARS
                || ( tokens.size() == 1 && MentionScanner.COMMON_WORDS.contains( tokens.get( 0 ) ) ) ) {
            return;
        }
        PhraseTrie node = this;
        for ( final String tok : tokens ) {
            node = node.next.computeIfAbsent( tok, k -> new PhraseTrie() );
        }
        if ( node.target == null ) {
            node.target = entry;
        }
    }

    /** Longest phrase starting at token {@code i} of {@code spans} (over {@code masked}), or {@code null}. */
    Match longestMatch( final String masked, final List< int[] > spans, final int i ) {
        PhraseTrie node = this;
        Match best = null;
        for ( int j = i; j < spans.size(); j++ ) {
            node = node.next.get( masked.substring( spans.get( j )[ 0 ], spans.get( j )[ 1 ] ).toLowerCase( Locale.ROOT ) );
            if ( node == null ) {
                break;
            }
            if ( node.target != null ) {
                best = new Match( node.target, j );
            }
        }
        return best;
    }

    private static List< String > tokens( final String phrase ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = TOKEN.matcher( phrase );
        while ( m.find() ) {
            out.add( m.group().toLowerCase( Locale.ROOT ) );
        }
        return out;
    }
}
