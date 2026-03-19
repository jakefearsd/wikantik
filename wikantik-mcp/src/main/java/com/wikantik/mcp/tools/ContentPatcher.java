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
package com.wikantik.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-logic utility for section and marker-based content patching.
 * Operates on plain Markdown body strings — no wiki dependencies.
 */
public final class ContentPatcher {

    private static final Pattern HEADING_PATTERN = Pattern.compile( "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE );

    private ContentPatcher() {
    }

    /**
     * A detected Markdown section: heading level, heading text, and line offsets.
     */
    public static class Section {
        private final int level;
        private final String heading;
        private final int startLine;  // line index of the heading
        private final int endLine;    // exclusive — first line of next section at same/higher level, or total line count

        public Section( final int level, final String heading, final int startLine, final int endLine ) {
            this.level = level;
            this.heading = heading;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public int level() { return level; }
        public String heading() { return heading; }
        public int startLine() { return startLine; }
        public int endLine() { return endLine; }
    }

    /**
     * Finds all top-level and nested sections in the body text.
     */
    public static List< Section > findSections( final String body ) {
        final String[] lines = body.split( "\n", -1 );
        final List< int[] > headings = new ArrayList<>(); // [lineIndex, level]
        final List< String > headingTexts = new ArrayList<>();

        for ( int i = 0; i < lines.length; i++ ) {
            final Matcher m = HEADING_PATTERN.matcher( lines[ i ].trim() );
            if ( m.matches() ) {
                headings.add( new int[]{ i, m.group( 1 ).length() } );
                headingTexts.add( m.group( 2 ).trim() );
            }
        }

        final List< Section > sections = new ArrayList<>();
        for ( int i = 0; i < headings.size(); i++ ) {
            final int startLine = headings.get( i )[ 0 ];
            final int level = headings.get( i )[ 1 ];
            final String text = headingTexts.get( i );

            // Find end: next heading at same or higher (lower number) level
            int endLine = lines.length;
            for ( int j = i + 1; j < headings.size(); j++ ) {
                if ( headings.get( j )[ 1 ] <= level ) {
                    endLine = headings.get( j )[ 0 ];
                    break;
                }
            }

            sections.add( new Section( level, text, startLine, endLine ) );
        }

        return sections;
    }

    /**
     * Applies a list of patch operations sequentially to the body text.
     *
     * @param body       the Markdown body (without frontmatter)
     * @param operations list of operation maps, each with "action", "section"/"marker", "content"
     * @return the patched body
     * @throws PatchException if a section or marker cannot be found
     */
    @SuppressWarnings( "unchecked" )
    public static String applyOperations( String body, final List< Map< String, Object > > operations ) throws PatchException {
        for ( final Map< String, Object > op : operations ) {
            final String action = ( String ) op.get( "action" );
            final String section = ( String ) op.get( "section" );
            final String marker = ( String ) op.get( "marker" );
            final String content = ( String ) op.get( "content" );

            if ( action == null ) {
                throw new PatchException( "Operation missing required 'action' field" );
            }
            if ( content == null ) {
                throw new PatchException( "Operation missing required 'content' field" );
            }

            switch ( action ) {
                case "append_to_section":
                    body = appendToSection( body, section, content );
                    break;
                case "insert_before":
                    body = insertBefore( body, marker, content );
                    break;
                case "insert_after":
                    body = insertAfter( body, marker, content );
                    break;
                case "replace_section":
                    body = replaceSection( body, section, content );
                    break;
                default:
                    throw new PatchException( "Unknown action: " + action,
                            "Valid actions: append_to_section, insert_before, insert_after, replace_section" );
            }
        }
        return body;
    }

    /**
     * Appends content at the end of the named section (before the next heading of same/higher level).
     */
    public static String appendToSection( final String body, final String sectionHeading, final String content ) throws PatchException {
        final Section section = findSectionByHeading( body, sectionHeading );
        final String[] lines = body.split( "\n", -1 );

        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < section.endLine; i++ ) {
            sb.append( lines[ i ] ).append( "\n" );
        }

        // Ensure content is on its own line
        final String trimmedContent = content.endsWith( "\n" ) ? content : content + "\n";
        sb.append( trimmedContent );

        for ( int i = section.endLine; i < lines.length; i++ ) {
            sb.append( lines[ i ] );
            if ( i < lines.length - 1 ) {
                sb.append( "\n" );
            }
        }

        return sb.toString();
    }

    /**
     * Inserts content on the line before the first occurrence of the marker text.
     */
    public static String insertBefore( final String body, final String marker, final String content ) throws PatchException {
        final int markerLine = findMarkerLine( body, marker );
        final String[] lines = body.split( "\n", -1 );

        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < markerLine; i++ ) {
            sb.append( lines[ i ] ).append( "\n" );
        }

        final String trimmedContent = content.endsWith( "\n" ) ? content : content + "\n";
        sb.append( trimmedContent );

        for ( int i = markerLine; i < lines.length; i++ ) {
            sb.append( lines[ i ] );
            if ( i < lines.length - 1 ) {
                sb.append( "\n" );
            }
        }

        return sb.toString();
    }

    /**
     * Inserts content on the line after the first occurrence of the marker text.
     */
    public static String insertAfter( final String body, final String marker, final String content ) throws PatchException {
        final int markerLine = findMarkerLine( body, marker );
        final String[] lines = body.split( "\n", -1 );

        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i <= markerLine; i++ ) {
            sb.append( lines[ i ] ).append( "\n" );
        }

        final String trimmedContent = content.endsWith( "\n" ) ? content : content + "\n";
        sb.append( trimmedContent );

        for ( int i = markerLine + 1; i < lines.length; i++ ) {
            sb.append( lines[ i ] );
            if ( i < lines.length - 1 ) {
                sb.append( "\n" );
            }
        }

        return sb.toString();
    }

    /**
     * Replaces the content of a section (heading line through end) with new content.
     * The heading line itself is preserved; content replaces everything after the heading
     * up to the next section of same/higher level.
     */
    public static String replaceSection( final String body, final String sectionHeading, final String content ) throws PatchException {
        final Section section = findSectionByHeading( body, sectionHeading );
        final String[] lines = body.split( "\n", -1 );

        final StringBuilder sb = new StringBuilder();
        // Keep heading line
        sb.append( lines[ section.startLine ] ).append( "\n" );

        // Insert new content
        final String trimmedContent = content.endsWith( "\n" ) ? content : content + "\n";
        sb.append( trimmedContent );

        // Prepend everything before the section
        final StringBuilder prefix = new StringBuilder();
        for ( int i = 0; i < section.startLine; i++ ) {
            prefix.append( lines[ i ] ).append( "\n" );
        }

        // Append everything after the section
        final StringBuilder suffix = new StringBuilder();
        for ( int i = section.endLine; i < lines.length; i++ ) {
            suffix.append( lines[ i ] );
            if ( i < lines.length - 1 ) {
                suffix.append( "\n" );
            }
        }

        return prefix.toString() + sb.toString() + suffix.toString();
    }

    private static Section findSectionByHeading( final String body, final String sectionHeading ) throws PatchException {
        if ( sectionHeading == null ) {
            throw new PatchException( "Section heading is required for this operation" );
        }
        final List< Section > sections = findSections( body );
        for ( final Section s : sections ) {
            if ( s.heading().equals( sectionHeading ) ) {
                return s;
            }
        }
        final List< String > available = new ArrayList<>();
        for ( final Section s : sections ) {
            available.add( s.heading() );
        }
        throw new PatchException(
                "Section not found: " + sectionHeading,
                "Available sections: " + available );
    }

    private static int findMarkerLine( final String body, final String marker ) throws PatchException {
        if ( marker == null ) {
            throw new PatchException( "Marker text is required for insert_before/insert_after operations" );
        }
        final String[] lines = body.split( "\n", -1 );
        for ( int i = 0; i < lines.length; i++ ) {
            if ( lines[ i ].contains( marker ) ) {
                return i;
            }
        }
        throw new PatchException(
                "Marker not found: " + marker,
                "Ensure the marker text appears exactly as written in the page body." );
    }
}
