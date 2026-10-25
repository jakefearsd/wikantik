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
package com.wikantik.markdown.extensions.fences;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parsed form of a fenced code block's info string, e.g. {@code mermaid example title=x}.
 *
 * @param language  the first whitespace-delimited word, lowercased; {@code ""} when absent
 * @param example   true when a modifier is {@code example}
 * @param modifiers the remaining words, lowercased, in source order
 */
public record FenceInfo( String language, boolean example, List< String > modifiers ) {

    public FenceInfo {
        modifiers = List.copyOf( modifiers );
    }

    /**
     * Parses an info string. Trims with {@link String#trim()} (chars up to U+0020) and splits on ASCII whitespace, matching the JS port. Null or blank input yields an empty language and no modifiers.
     */
    public static FenceInfo parse( final String info ) {
        final String stripped = info == null ? "" : info.trim();
        if ( stripped.isEmpty() ) {
            return new FenceInfo( "", false, List.of() );
        }
        final String[] words = stripped.split( "\\s+" );
        final String language = words[ 0 ].toLowerCase( Locale.ROOT );
        final List< String > modifiers = Arrays.stream( words, 1, words.length )
                .map( w -> w.toLowerCase( Locale.ROOT ) )
                .toList();
        return new FenceInfo( language, modifiers.contains( "example" ), modifiers );
    }
}
