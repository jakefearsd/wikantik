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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place canonical-id assignment for wiki Markdown files. Idempotent — a file
 * whose frontmatter already declares {@code canonical_id:} is returned unchanged.
 * Presence is determined by {@link FrontmatterParser}, not a regex, so
 * {@code canonical_id:} appearing in body code blocks or prose does not spuriously
 * skip the file.
 */
public final class FrontmatterRewriter {

    private static final Pattern OPEN = Pattern.compile( "\\A---(\\r?\\n)" );

    private FrontmatterRewriter() {}

    public static String assignCanonicalId( final String input, final String canonicalId ) {
        if ( input == null ) return "---\ncanonical_id: " + canonicalId + "\n---\n";
        final Object existing = FrontmatterParser.parse( input ).metadata().get( "canonical_id" );
        if ( existing != null && !existing.toString().isBlank() ) {
            return input;
        }
        final Matcher open = OPEN.matcher( input );
        if ( !open.find() ) {
            final String nl = input.contains( "\r\n" ) ? "\r\n" : "\n";
            return "---" + nl + "canonical_id: " + canonicalId + nl + "---" + nl + input;
        }
        final String nl = open.group( 1 );
        return input.substring( 0, open.end() )
                + "canonical_id: " + canonicalId + nl
                + input.substring( open.end() );
    }
}
