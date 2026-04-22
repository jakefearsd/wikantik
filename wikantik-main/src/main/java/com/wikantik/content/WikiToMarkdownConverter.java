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
package com.wikantik.content;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy JSPWiki wiki syntax to Markdown.
 * <p>
 * Handles core syntax constructs (headings, formatting, links, lists, tables,
 * code blocks, horizontal rules) and preserves plugin/ACL/variable constructs
 * in Markdown-compatible {@code [{...}]()} form. Unconvertible constructs are
 * flagged with HTML comments.
 */
public final class WikiToMarkdownConverter {

    /** Minimum heuristic score to consider content as likely wiki syntax. */
    static final int HEURISTIC_THRESHOLD = 3;

    private enum State { NORMAL, CODE_BLOCK, TABLE }

    public record ConversionResult( String markdown, List<String> warnings ) {}

    // --- Heuristic detection patterns ---
    private static final Pattern WIKI_HEADING = Pattern.compile( "^!{1,3}\\s" , Pattern.MULTILINE );
    private static final Pattern WIKI_ITALIC = Pattern.compile( "''[^']+?''" );
    private static final Pattern WIKI_PIPE_LINK = Pattern.compile( "\\[([^\\]|]+)\\|([^\\]]+)\\]" );
    private static final Pattern WIKI_PLUGIN_UNCONVERTED = Pattern.compile( "\\[\\{[^}]+\\}\\](?!\\()" );
    private static final Pattern WIKI_CODE_BLOCK_DELIM = Pattern.compile( "\\{\\{\\{" );
    private static final Pattern WIKI_BOLD = Pattern.compile( "__[^_]+?__" );

    // --- Conversion patterns ---
    private static final Pattern HEADING3 = Pattern.compile( "^!!!\\s*(.*)" );
    private static final Pattern HEADING2 = Pattern.compile( "^!!\\s*(.*)" );
    private static final Pattern HEADING1 = Pattern.compile( "^!\\s*(.*)" );
    private static final Pattern HR = Pattern.compile( "^-{4,}\\s*$" );
    private static final Pattern UNORDERED_LIST = Pattern.compile( "^(\\*{1,})\\s+(.*)" );
    private static final Pattern ORDERED_LIST = Pattern.compile( "^(#{1,})\\s+(.*)" );
    private static final Pattern TABLE_HEADER = Pattern.compile( "^\\|\\|(.*)$" );
    private static final Pattern TABLE_ROW = Pattern.compile( "^\\|(.*)$" );
    private static final Pattern PLUGIN_SYNTAX = Pattern.compile( "\\[\\{([^}]*)\\}\\]" );
    private static final Pattern WIKI_LINK_WITH_TEXT = Pattern.compile( "\\[([^\\]|]+)\\|([^\\]]+)\\]" );
    private static final Pattern WIKI_BARE_LINK = Pattern.compile( "\\[([^\\]\\[|]+?)\\](?!\\()" );
    private static final Pattern BOLD_SYNTAX = Pattern.compile( "__([^_]+?)__" );
    private static final Pattern ITALIC_SYNTAX = Pattern.compile( "''([^']+?)''" );
    private static final Pattern INLINE_CODE = Pattern.compile( "\\{\\{([^}]+?)\\}\\}" );
    private static final Pattern LINE_BREAK = Pattern.compile( "\\\\\\\\" );
    private static final Pattern DEFINITION_LIST = Pattern.compile( "^;([^:]*):(.*)$" );

    private WikiToMarkdownConverter() {}

    /**
     * Checks whether the given content is likely legacy JSPWiki wiki syntax
     * rather than Markdown, using a scoring heuristic.
     *
     * @param content the page content to analyze
     * @return {@code true} if the content scores above the heuristic threshold
     */
    public static boolean isLikelyWikiSyntax( final String content ) {
        if( content == null || content.isBlank() ) {
            return false;
        }
        int score = 0;
        if( WIKI_HEADING.matcher( content ).find() )           score += 2;
        if( WIKI_ITALIC.matcher( content ).find() )            score += 2;
        if( WIKI_PIPE_LINK.matcher( content ).find() )         score += 2;
        if( WIKI_PLUGIN_UNCONVERTED.matcher( content ).find() ) score += 2;
        if( WIKI_CODE_BLOCK_DELIM.matcher( content ).find() )  score += 1;
        if( WIKI_BOLD.matcher( content ).find() )              score += 1;
        return score >= HEURISTIC_THRESHOLD;
    }

    /**
     * Converts JSPWiki wiki syntax to Markdown.
     *
     * @param wikiText the raw wiki syntax content
     * @return a {@link ConversionResult} with the converted Markdown and any warnings
     */
    public static ConversionResult convert( final String wikiText ) {
        if( wikiText == null || wikiText.isEmpty() ) {
            return new ConversionResult( "", List.of() );
        }

        final List<String> warnings = new ArrayList<>();
        final StringBuilder result = new StringBuilder();
        final List<String[]> tableBuffer = new ArrayList<>();
        boolean hasHeaderRow = false;
        State state = State.NORMAL;

        for( final String line : wikiText.split( "\n", -1 ) ) {
            // --- Code block handling ---
            if( state == State.CODE_BLOCK ) {
                if( "}}}".equals( line.trim() ) ) {
                    result.append( "```\n" );
                    state = State.NORMAL;
                } else {
                    result.append( line ).append( '\n' );
                }
                continue;
            }

            if( line.trim().startsWith( "{{{" ) ) {
                hasHeaderRow = flushTableIfNeeded( result, tableBuffer, hasHeaderRow );
                result.append( "```\n" );
                final String afterOpen = line.trim().substring( 3 ).trim();
                if( !afterOpen.isEmpty() ) {
                    result.append( afterOpen ).append( '\n' );
                }
                state = State.CODE_BLOCK;
                continue;
            }

            // --- Table row handling ---
            if( tryAccumulateTable( line, tableBuffer ) ) {
                hasHeaderRow = hasHeaderRow || TABLE_HEADER.matcher( line ).matches();
                continue;
            }

            hasHeaderRow = flushTableIfNeeded( result, tableBuffer, hasHeaderRow );

            // --- Line-by-line conversions ---
            result.append( convertBlockLine( line ) ).append( '\n' );
        }

        // Flush any remaining table
        if( !tableBuffer.isEmpty() ) {
            flushTable( result, tableBuffer, hasHeaderRow );
        }

        // Flush any unclosed code block
        if( state == State.CODE_BLOCK ) {
            result.append( "```\n" );
            warnings.add( "Unclosed code block ({{{ without matching }}}) was auto-closed" );
        }

        // Remove trailing newline to match input convention
        String markdown = result.toString();
        if( markdown.endsWith( "\n" ) && !wikiText.endsWith( "\n" ) ) {
            markdown = markdown.substring( 0, markdown.length() - 1 );
        }

        return new ConversionResult( markdown, warnings );
    }

    /**
     * Tries to accumulate the line into the table buffer. Returns true if the line
     * was consumed as a table row (header or data).
     */
    private static boolean tryAccumulateTable( final String line, final List<String[]> tableBuffer ) {
        final Matcher headerMatch = TABLE_HEADER.matcher( line );
        if( headerMatch.matches() ) {
            tableBuffer.add( splitTableCells( headerMatch.group( 1 ), true ) );
            return true;
        }

        final Matcher rowMatch = TABLE_ROW.matcher( line );
        if( !tableBuffer.isEmpty() && rowMatch.matches() ) {
            tableBuffer.add( splitTableCells( rowMatch.group( 1 ), false ) );
            return true;
        }

        // Start of a new table with a data row (no header)
        if( rowMatch.matches() && line.startsWith( "|" ) && !line.startsWith( "||" ) ) {
            tableBuffer.add( splitTableCells( rowMatch.group( 1 ), false ) );
            return true;
        }

        return false;
    }

    /**
     * Flushes the table buffer if non-empty. Returns the reset value for hasHeaderRow (always false).
     */
    private static boolean flushTableIfNeeded( final StringBuilder result,
                                                final List<String[]> tableBuffer,
                                                final boolean hasHeaderRow ) {
        if( !tableBuffer.isEmpty() ) {
            flushTable( result, tableBuffer, hasHeaderRow );
            tableBuffer.clear();
        }
        return false;
    }

    /**
     * Converts a single non-code, non-table line: headings, HR, definition lists,
     * ordered/unordered lists, or plain inline text.
     */
    private static String convertBlockLine( final String line ) {
        Matcher m = HEADING3.matcher( line );
        if( m.matches() ) return "# " + convertInline( m.group( 1 ).trim() );

        m = HEADING2.matcher( line );
        if( m.matches() ) return "## " + convertInline( m.group( 1 ).trim() );

        m = HEADING1.matcher( line );
        if( m.matches() ) return "### " + convertInline( m.group( 1 ).trim() );

        if( HR.matcher( line ).matches() ) return "---";

        m = DEFINITION_LIST.matcher( line );
        if( m.matches() ) {
            final String term = m.group( 1 ).trim();
            final String definition = m.group( 2 ).trim();
            return term.isEmpty()
                ? ": " + convertInline( definition )
                : "**" + convertInline( term ) + "**: " + convertInline( definition );
        }

        m = UNORDERED_LIST.matcher( line );
        if( m.matches() ) {
            return "  ".repeat( m.group( 1 ).length() - 1 ) + "* " + convertInline( m.group( 2 ) );
        }

        m = ORDERED_LIST.matcher( line );
        if( m.matches() ) {
            return "   ".repeat( m.group( 1 ).length() - 1 ) + "1. " + convertInline( m.group( 2 ) );
        }

        return convertInline( line );
    }

    /**
     * Applies inline syntax conversions (plugins, links, bold, italic, code, line breaks).
     */
    static String convertInline( final String line ) {
        String result = line;

        // 0. Escape sequences: [[ → placeholder (restored at end)
        final String ESCAPED_BRACKET = "\u0000ESC_BRACKET\u0000";
        result = result.replace( "[[", ESCAPED_BRACKET );

        // 1. Plugin/ACL/variable syntax: [{...}] → [{...}]()
        //    Must run BEFORE link conversion to avoid false matches
        result = PLUGIN_SYNTAX.matcher( result ).replaceAll( "[{$1}]()" );

        // 2. Wiki links with text: [text|url] → [text](url)
        result = WIKI_LINK_WITH_TEXT.matcher( result ).replaceAll( "[$1]($2)" );

        // 3. Bare wiki links: [PageName] → [PageName]()
        //    Negative lookahead (?!\() prevents matching already-converted links
        result = WIKI_BARE_LINK.matcher( result ).replaceAll( "[$1]()" );

        // 4. Bold: __text__ → **text**
        result = BOLD_SYNTAX.matcher( result ).replaceAll( "**$1**" );

        // 5. Italic: ''text'' → *text*
        result = ITALIC_SYNTAX.matcher( result ).replaceAll( "*$1*" );

        // 6. Inline code: {{text}} → `text`
        //    Pattern already excludes {{{ via the [^}] character class
        result = INLINE_CODE.matcher( result ).replaceAll( "`$1`" );

        // 7. Line breaks: \\ → two trailing spaces
        result = LINE_BREAK.matcher( result ).replaceAll( "  " );

        // 8. Restore escaped brackets: [[ → [
        result = result.replace( ESCAPED_BRACKET, "[" );

        return result;
    }

    /**
     * Splits a wiki table row into individual cell values.
     */
    private static String[] splitTableCells( final String rowContent, final boolean isHeader ) {
        // For header rows, cells are separated by ||
        // For data rows, cells are separated by |
        String content = rowContent;

        // Remove trailing separator if present
        if( isHeader && content.endsWith( "||" ) ) {
            content = content.substring( 0, content.length() - 2 );
        } else if( !isHeader && content.endsWith( "|" ) ) {
            content = content.substring( 0, content.length() - 1 );
        }

        final String[] cells;
        if( isHeader ) {
            cells = content.split( "\\|\\|" );
        } else {
            cells = content.split( "\\|" );
        }

        // Trim and apply inline conversions to each cell
        for( int i = 0; i < cells.length; i++ ) {
            cells[i] = convertInline( cells[i].trim() );
        }
        return cells;
    }

    /**
     * Flushes the accumulated table buffer as a Markdown table.
     */
    private static void flushTable( final StringBuilder result, final List<String[]> rows,
                                    final boolean hasHeaderRow ) {
        if( rows.isEmpty() ) return;

        // Determine maximum column count
        int maxCols = 0;
        for( final String[] row : rows ) {
            maxCols = Math.max( maxCols, row.length );
        }

        if( hasHeaderRow ) {
            // First row is the header
            appendTableRow( result, rows.get( 0 ), maxCols );
            // Separator row
            result.append('|');
            for( int c = 0; c < maxCols; c++ ) {
                result.append( " --- |" );
            }
            result.append( '\n' );
            // Data rows
            for( int r = 1; r < rows.size(); r++ ) {
                appendTableRow( result, rows.get( r ), maxCols );
            }
        } else {
            // No header — create an empty header row
            result.append('|');
            for( int c = 0; c < maxCols; c++ ) {
                result.append( "   |" );
            }
            result.append( '\n' );
            result.append('|');
            for( int c = 0; c < maxCols; c++ ) {
                result.append( " --- |" );
            }
            result.append( '\n' );
            for( final String[] row : rows ) {
                appendTableRow( result, row, maxCols );
            }
        }
    }

    private static void appendTableRow( final StringBuilder sb, final String[] cells, final int maxCols ) {
        sb.append( "| " );
        for( int c = 0; c < maxCols; c++ ) {
            if( c < cells.length ) {
                sb.append( cells[c] );
            }
            sb.append( " | " );
        }
        sb.append( '\n' );
    }
}
