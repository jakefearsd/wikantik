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

/**
 * Derives a human-readable title from a wiki page name by splitting CamelCase,
 * underscores, and hyphens into separate words and capitalizing each word.
 * All-caps acronyms are preserved intact.
 */
public final class TitleDeriver {

    private TitleDeriver() {
    }

    /**
     * Derives a human-readable title from the given wiki page name.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "MyPageName"}      → {@code "My Page Name"}</li>
     *   <li>{@code "my_page_name"}    → {@code "My Page Name"}</li>
     *   <li>{@code "HTTPServerConfig"} → {@code "HTTP Server Config"}</li>
     *   <li>{@code "some-page-name"}  → {@code "Some Page Name"}</li>
     * </ul>
     *
     * @param pageName the raw wiki page name; must not be {@code null}
     * @return the derived human-readable title
     */
    public static String derive( final String pageName ) {
        // Replace underscores and hyphens with spaces
        String s = pageName.replace( '_', ' ' ).replace( '-', ' ' );

        // Insert a space between a run of uppercase letters followed by an uppercase+lowercase
        // sequence, e.g. "HTTPServer" → "HTTP Server"
        s = s.replaceAll( "([A-Z]+)([A-Z][a-z])", "$1 $2" );

        // Insert a space between a lowercase (or digit) letter followed by an uppercase letter,
        // e.g. "PageName" → "Page Name"
        s = s.replaceAll( "([a-z\\d])([A-Z])", "$1 $2" );

        // Collapse any runs of whitespace to a single space and trim
        s = s.replaceAll( "\\s+", " " ).trim();

        // Capitalize the first character of each word; preserve all-caps words
        final String[] words = s.split( " " );
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < words.length; i++ ) {
            final String word = words[ i ];
            if ( i > 0 ) {
                sb.append( ' ' );
            }
            if ( word.isEmpty() ) {
                continue;
            }
            // If the word is already all-uppercase (acronym), leave it as-is
            if ( word.equals( word.toUpperCase() ) ) {
                sb.append( word );
            } else {
                sb.append( Character.toUpperCase( word.charAt( 0 ) ) );
                sb.append( word.substring( 1 ) );
            }
        }
        return sb.toString();
    }
}
