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
package com.wikantik.rest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Pure helpers behind {@link PageListResource}'s {@code q=} search and {@code names=} existence check. */
final class PageNameQuery {

    /** Most page names a single {@code names=} request may check. */
    static final int MAX_NAMES = 50;

    private PageNameQuery() {
    }

    /**
     * Case-insensitive substring match on {@code nameOf}, ranked exact match, then prefix match, then
     * any other substring match; alphabetical within a rank. A null/blank {@code q} keeps every item,
     * alphabetically.
     */
    static < T > List< T > rankBySubstring( final Collection< T > items, final Function< T, String > nameOf, final String q ) {
        final Comparator< T > alphabetical = Comparator.comparing( nameOf );
        if ( q == null || q.isBlank() ) {
            return items.stream().sorted( alphabetical ).toList();
        }
        final String needle = q.trim().toLowerCase( Locale.ROOT );
        return items.stream()
                .filter( item -> nameOf.apply( item ).toLowerCase( Locale.ROOT ).contains( needle ) )
                .sorted( Comparator.comparingInt( ( T item ) -> rank( nameOf.apply( item ), needle ) )
                        .thenComparing( alphabetical ) )
                .toList();
    }

    private static int rank( final String name, final String needle ) {
        final String lower = name.toLowerCase( Locale.ROOT );
        if ( lower.equals( needle ) ) {
            return 0;
        }
        return lower.startsWith( needle ) ? 1 : 2;
    }

    /** Splits a comma-separated {@code names=} value: trimmed, blanks dropped, duplicates removed, order kept. */
    static List< String > parseNames( final String raw ) {
        if ( raw == null ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) )
                .map( String::trim )
                .filter( s -> !s.isEmpty() )
                .distinct()
                .toList();
    }
}
