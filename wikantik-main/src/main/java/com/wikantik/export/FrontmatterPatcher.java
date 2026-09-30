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
package com.wikantik.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Patches a page's YAML frontmatter block textually. Re-serialising through SnakeYAML would
 * rewrite scalars (dates become timestamps), which breaks Obsidian properties — so every line
 * we do not own is copied verbatim; owned top-level keys are removed and re-appended.
 */
public final class FrontmatterPatcher {
    private FrontmatterPatcher() {}

    public static String patch( final String raw, final Map< String, Object > replaceOrAdd ) {
        final String text = raw == null ? "" : raw;
        final Split split = splitFrontmatter( text, replaceOrAdd );
        final StringBuilder sb = new StringBuilder( "---\n" );
        for ( final String line : split.kept() ) {
            sb.append( line ).append( '\n' );
        }
        appendPatchEntries( sb, replaceOrAdd );
        return sb.append( "---\n" ).append( split.body() ).toString();
    }

    /** The kept (unowned) frontmatter lines plus the body text following the block. */
    private record Split( List< String > kept, String body ) {}

    /**
     * Locates the {@code ---}-delimited block (if any) and splits it into the lines this patch
     * doesn't own plus the trailing body. Extracted from {@link #patch(String, Map)} to keep that
     * method's complexity low — this is the "is there even a frontmatter block" branch.
     */
    private static Split splitFrontmatter( final String text, final Map< String, Object > replaceOrAdd ) {
        if ( !text.startsWith( "---\n" ) && !text.startsWith( "---\r\n" ) ) {
            return new Split( new ArrayList<>(), text );
        }
        final int firstNl = text.indexOf( '\n' );
        final int close = findClose( text, firstNl + 1 );
        if ( close < 0 ) {
            return new Split( new ArrayList<>(), text );
        }
        final String block = text.substring( firstNl + 1, close );
        final int afterClose = text.indexOf( '\n', close );
        final String body = afterClose < 0 ? "" : text.substring( afterClose + 1 );
        return new Split( filterOwnedLines( block, replaceOrAdd ), body );
    }

    /** Drops top-level keys {@code replaceOrAdd} owns (plus their nested/list lines) from {@code block}. */
    private static List< String > filterOwnedLines( final String block, final Map< String, Object > replaceOrAdd ) {
        final List< String > kept = new ArrayList<>();
        boolean skipping = false;
        for ( final String line : block.split( "\r?\n", -1 ) ) {
            if ( line.isEmpty() && kept.isEmpty() ) {
                continue;
            }
            final boolean topLevel = !line.isEmpty()
                    && !Character.isWhitespace( line.charAt( 0 ) )
                    && !line.startsWith( "- " );
            if ( topLevel ) {
                final int colon = line.indexOf( ':' );
                final String key = colon > 0 ? line.substring( 0, colon ).trim() : "";
                skipping = replaceOrAdd.containsKey( key );
            }
            if ( !skipping ) {
                kept.add( line );
            }
        }
        while ( !kept.isEmpty() && kept.get( kept.size() - 1 ).isEmpty() ) {
            kept.remove( kept.size() - 1 );
        }
        return kept;
    }

    /** Appends the owned keys — scalar or list-valued — as new top-level frontmatter lines. */
    private static void appendPatchEntries( final StringBuilder sb, final Map< String, Object > replaceOrAdd ) {
        for ( final Map.Entry< String, Object > e : replaceOrAdd.entrySet() ) {
            if ( e.getValue() instanceof List< ? > list ) {
                sb.append( e.getKey() ).append( ":\n" );
                for ( final Object item : list ) {
                    sb.append( "  - " ).append( quote( String.valueOf( item ) ) ).append( '\n' );
                }
            } else {
                sb.append( e.getKey() )
                        .append( ": " )
                        .append( quote( String.valueOf( e.getValue() ) ) )
                        .append( '\n' );
            }
        }
    }

    private static int findClose( final String text, final int from ) {
        int idx = from;
        while ( idx < text.length() ) {
            final int nl = text.indexOf( '\n', idx );
            final String line = ( nl < 0 ? text.substring( idx ) : text.substring( idx, nl ) )
                    .stripTrailing();
            if ( line.equals( "---" ) ) {
                return idx;
            }
            if ( nl < 0 ) {
                return -1;
            }
            idx = nl + 1;
        }
        return -1;
    }

    private static String quote( final String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
    }
}
