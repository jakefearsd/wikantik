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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-place canonical-id assignment for wiki Markdown files. Idempotent — a file
 * that already declares {@code canonical_id:} is returned unchanged.
 */
public final class FrontmatterRewriter {

    private static final Pattern OPEN          = Pattern.compile( "\\A---(\\r?\\n)" );
    private static final Pattern HAS_CANONICAL = Pattern.compile( "(?m)^canonical_id:\\s*\\S" );

    private FrontmatterRewriter() {}

    public static String assignCanonicalId( final String input, final String canonicalId ) {
        if ( input == null ) return "---\ncanonical_id: " + canonicalId + "\n---\n";
        if ( HAS_CANONICAL.matcher( input ).find() ) {
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
