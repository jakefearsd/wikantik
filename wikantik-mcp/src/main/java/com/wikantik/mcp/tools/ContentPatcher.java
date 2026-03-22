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
 *
 * <p>Performance note: each public method splits the body into lines exactly once.
 * Internal helpers ({@code findSectionsFromLines}, {@code findMarkerLine}) accept
 * pre-split arrays to avoid redundant {@code String.split()} calls.
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
     * Lines inside fenced code blocks ({@code ```}) are skipped.
     */
    public static List< Section > findSections( final String body ) {
        return findSectionsFromLines( body.split( "\n", -1 ) );
    }

    /**
     * Finds sections from a pre-split line array, avoiding a redundant {@code split()}.
     */
    static List< Section > findSectionsFromLines( final String[] lines ) {
        final List< int[] > headings = new ArrayList<>(); // [lineIndex, level]
        final List< String > headingTexts = new ArrayList<>();

        boolean inCodeBlock = false;
        for ( int i = 0; i < lines.length; i++ ) {
            final String trimmed = lines[ i ].trim();
            if ( trimmed.startsWith( "```" ) ) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if ( inCodeBlock ) {
                continue;
            }
            final Matcher m = HEADING_PATTERN.matcher( trimmed );
            if ( m.matches() ) {
                headings.add( new int[]{ i, m.group( 1 ).length() } );
                headingTexts.add( m.group( 2 ).trim() );
            }
        }

        final List< Section > sections = new ArrayList<>( headings.size() );
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
        final String[] lines = body.split( "\n", -1 );
        final Section section = findSectionByHeading( lines, sectionHeading );
        return rebuildWithInsertion( lines, body.length(), section.endLine, section.endLine, content );
    }

    /**
     * Inserts content on the line before the first occurrence of the marker text.
     */
    public static String insertBefore( final String body, final String marker, final String content ) throws PatchException {
        final String[] lines = body.split( "\n", -1 );
        final int markerLine = findMarkerLine( lines, marker );
        return rebuildWithInsertion( lines, body.length(), markerLine, markerLine, content );
    }

    /**
     * Inserts content on the line after the first occurrence of the marker text.
     */
    public static String insertAfter( final String body, final String marker, final String content ) throws PatchException {
        final String[] lines = body.split( "\n", -1 );
        final int markerLine = findMarkerLine( lines, marker );
        return rebuildWithInsertion( lines, body.length(), markerLine + 1, markerLine + 1, content );
    }

    /**
     * Replaces the content of a section (heading line through end) with new content.
     * The heading line itself is preserved; content replaces everything after the heading
     * up to the next section of same/higher level.
     */
    public static String replaceSection( final String body, final String sectionHeading, final String content ) throws PatchException {
        final String[] lines = body.split( "\n", -1 );
        final Section section = findSectionByHeading( lines, sectionHeading );
        // Keep the heading line, skip section body (startLine+1 to endLine), insert new content
        return rebuildWithInsertion( lines, body.length(), section.startLine + 1, section.endLine, content );
    }

    /**
     * Rebuilds the body from lines, inserting new content between {@code insertBefore}
     * and {@code skipUntil}.  Lines in [{@code insertBefore}, {@code skipUntil}) are
     * replaced by the new content.
     *
     * <p>This is the common operation for all patch types — only the insertion point
     * and skip range differ:
     * <ul>
     *   <li>append_to_section: insert at endLine, skip nothing</li>
     *   <li>insert_before: insert at markerLine, skip nothing</li>
     *   <li>insert_after: insert at markerLine+1, skip nothing</li>
     *   <li>replace_section: insert at startLine+1, skip to endLine</li>
     * </ul>
     *
     * @param lines        pre-split line array
     * @param sizeHint     approximate size of the original body (for StringBuilder capacity)
     * @param insertBefore line index at which to insert content
     * @param skipUntil    line index at which to resume copying (exclusive range [insertBefore, skipUntil) is skipped)
     * @param content      content to insert
     * @return the rebuilt body
     */
    private static String rebuildWithInsertion( final String[] lines, final int sizeHint,
                                                  final int insertBefore, final int skipUntil,
                                                  final String content ) {
        final StringBuilder sb = new StringBuilder( sizeHint + content.length() + 64 );

        // Lines before insertion point
        for ( int i = 0; i < insertBefore; i++ ) {
            sb.append( lines[ i ] ).append( '\n' );
        }

        // Inserted content (ensure trailing newline)
        sb.append( content );
        if ( !content.endsWith( "\n" ) ) {
            sb.append( '\n' );
        }

        // Lines after the skipped range
        for ( int i = skipUntil; i < lines.length; i++ ) {
            sb.append( lines[ i ] );
            if ( i < lines.length - 1 ) {
                sb.append( '\n' );
            }
        }

        return sb.toString();
    }

    /**
     * Finds a section by heading text from pre-split lines.
     */
    private static Section findSectionByHeading( final String[] lines, final String sectionHeading ) throws PatchException {
        if ( sectionHeading == null ) {
            throw new PatchException( "Section heading is required for this operation" );
        }
        final List< Section > sections = findSectionsFromLines( lines );
        for ( final Section s : sections ) {
            if ( s.heading().equals( sectionHeading ) ) {
                return s;
            }
        }
        final List< String > available = new ArrayList<>( sections.size() );
        for ( final Section s : sections ) {
            available.add( s.heading() );
        }
        throw new PatchException(
                "Section not found: " + sectionHeading,
                "Available sections: " + available );
    }

    /**
     * Finds the line index of the first line containing the marker text, from pre-split lines.
     */
    private static int findMarkerLine( final String[] lines, final String marker ) throws PatchException {
        if ( marker == null ) {
            throw new PatchException( "Marker text is required for insert_before/insert_after operations" );
        }
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
