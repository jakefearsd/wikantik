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
package com.wikantik.frontmatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML frontmatter from wiki page text. Frontmatter is delimited by {@code ---}
 * markers at the very start of the page text. Handles both LF and CRLF line endings
 * (JSPWiki normalizes stored text to CRLF).
 */
public final class FrontmatterParser {

    private static final Logger LOG = LogManager.getLogger( FrontmatterParser.class );

    /** Matches the opening --- followed by a newline (LF or CRLF). */
    private static final Pattern OPENING = Pattern.compile( "\\A---\\r?\\n" );

    /** Matches the closing delimiter: either immediately after opening (empty block) or on its own line. */
    private static final Pattern CLOSING_IMMEDIATE = Pattern.compile( "\\A---\\r?\\n" );
    private static final Pattern CLOSING = Pattern.compile( "\\r?\\n---\\r?\\n" );

    private FrontmatterParser() {
    }

    /**
     * Parses a page text that may contain YAML frontmatter.
     *
     * @param text the raw page text (may be null)
     * @return a ParsedPage with metadata map and body text
     */
    public static ParsedPage parse( final String text ) {
        if ( text == null || text.isEmpty() ) {
            return new ParsedPage( Map.of(), text == null ? "" : text );
        }

        final Matcher openMatcher = OPENING.matcher( text );
        if ( !openMatcher.find() ) {
            return new ParsedPage( Map.of(), text );
        }

        final int yamlStart = openMatcher.end(); // position right after "---\n" or "---\r\n"

        // Check for empty frontmatter: closing --- immediately follows opening ---
        final Matcher immediateMatcher = CLOSING_IMMEDIATE.matcher( text.substring( yamlStart ) );
        if ( immediateMatcher.find() ) {
            final String body = text.substring( yamlStart + immediateMatcher.end() );
            return parseYaml( "", body );
        }

        final Matcher closeMatcher = CLOSING.matcher( text );
        if ( !closeMatcher.find( yamlStart ) ) {
            // No closing delimiter found — check if text ends with \n--- (no trailing newline)
            if ( text.endsWith( "\n---" ) || text.endsWith( "\r\n---" ) ) {
                final int lastDelim = text.lastIndexOf( "---" );
                // Walk back past optional \r
                int bodyEnd = lastDelim;
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\n' ) {
                    bodyEnd--;
                }
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\r' ) {
                    bodyEnd--;
                }
                if ( bodyEnd >= yamlStart ) {
                    return parseYaml( text.substring( yamlStart, bodyEnd ), "" );
                }
            }
            return new ParsedPage( Map.of(), text );
        }

        final String yamlBlock = text.substring( yamlStart, closeMatcher.start() );
        final String body = text.substring( closeMatcher.end() );

        return parseYaml( yamlBlock, body );
    }

    @SuppressWarnings( "unchecked" )
    private static ParsedPage parseYaml( final String yamlBlock, final String body ) {
        if ( yamlBlock.isBlank() ) {
            return new ParsedPage( Map.of(), body );
        }

        try {
            final Yaml yaml = new Yaml();
            final Object parsed = yaml.load( yamlBlock );
            if ( parsed instanceof Map ) {
                return new ParsedPage( Map.copyOf( ( Map< String, Object > ) parsed ), body );
            }
            return new ParsedPage( Map.of(), body );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to parse YAML frontmatter: {}", e.getMessage() );
            return new ParsedPage( Map.of(), body );
        }
    }
}
