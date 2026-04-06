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

import com.wikantik.api.knowledge.KgNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Assembles a text document from a KG node's metadata and wiki page body
 * for TF-IDF vectorization. Also provides markdown stripping.
 *
 * @since 1.0
 */
public final class NodeTextAssembler {

    private NodeTextAssembler() {}

    // Markdown stripping patterns
    private static final Pattern FRONTMATTER = Pattern.compile( "\\A---\\s*\\n.*?\\n---\\s*\\n", Pattern.DOTALL );
    private static final Pattern CODE_FENCE = Pattern.compile( "```[\\s\\S]*?```" );
    private static final Pattern IMAGE = Pattern.compile( "!\\[([^\\]]*)\\]\\([^)]*\\)" );
    private static final Pattern LINK = Pattern.compile( "\\[([^\\]]*)\\]\\([^)]*\\)" );
    private static final Pattern HEADING = Pattern.compile( "^#{1,6}\\s+", Pattern.MULTILINE );
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile( "__([^_]+)__" );
    private static final Pattern BOLD_STAR = Pattern.compile( "\\*\\*([^*]+)\\*\\*" );
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile( "_([^_]+)_" );
    private static final Pattern ITALIC_STAR = Pattern.compile( "\\*([^*]+)\\*" );
    private static final Pattern INLINE_CODE = Pattern.compile( "`([^`]+)`" );
    private static final Pattern HTML_TAG = Pattern.compile( "<[^>]+>" );
    private static final Pattern MULTI_SPACE = Pattern.compile( "\\s+" );

    /**
     * Strips markdown formatting from text, keeping only prose content.
     * Code fences are discarded entirely; link/image URLs are discarded
     * but their display text is kept.
     */
    public static String stripMarkdown( final String markdown ) {
        if( markdown == null || markdown.isEmpty() ) return "";
        String s = markdown;
        s = FRONTMATTER.matcher( s ).replaceAll( "" );
        s = CODE_FENCE.matcher( s ).replaceAll( " " );
        s = IMAGE.matcher( s ).replaceAll( "$1" );
        s = LINK.matcher( s ).replaceAll( "$1" );
        s = HEADING.matcher( s ).replaceAll( "" );
        s = BOLD_UNDERSCORE.matcher( s ).replaceAll( "$1" );
        s = BOLD_STAR.matcher( s ).replaceAll( "$1" );
        s = ITALIC_UNDERSCORE.matcher( s ).replaceAll( "$1" );
        s = ITALIC_STAR.matcher( s ).replaceAll( "$1" );
        s = INLINE_CODE.matcher( s ).replaceAll( "$1" );
        s = HTML_TAG.matcher( s ).replaceAll( " " );
        s = MULTI_SPACE.matcher( s ).replaceAll( " " );
        return s.trim();
    }

    /**
     * Assembles a text document from node metadata and wiki page body.
     * Applies term-boosting via repetition for high-signal fields.
     *
     * @param node     the KG node
     * @param pageBody raw markdown page body, or null if unavailable
     * @return assembled text ready for TF-IDF tokenization
     */
    public static String assemble( final KgNode node, final String pageBody ) {
        final StringBuilder sb = new StringBuilder();

        // Name (3x boost)
        repeat( sb, node.name(), 3 );

        // Node type (2x boost)
        if( node.nodeType() != null ) {
            repeat( sb, node.nodeType(), 2 );
        }

        // Properties
        final Map< String, Object > props = node.properties();
        if( props != null ) {
            appendString( sb, props, "title" );
            appendString( sb, props, "description" );
            appendString( sb, props, "summary" );
            appendList( sb, props, "tags", 2 );
            appendList( sb, props, "keywords", 2 );
        }

        // Page body (stripped of markdown)
        if( pageBody != null && !pageBody.isBlank() ) {
            sb.append( ' ' ).append( stripMarkdown( pageBody ) );
        }

        return sb.toString().trim();
    }

    private static void repeat( final StringBuilder sb, final String text, final int times ) {
        for( int i = 0; i < times; i++ ) {
            if( !sb.isEmpty() ) sb.append( ' ' );
            sb.append( text );
        }
    }

    private static void appendString( final StringBuilder sb,
                                       final Map< String, Object > props, final String key ) {
        final Object val = props.get( key );
        if( val instanceof String s && !s.isBlank() ) {
            sb.append( ' ' ).append( s );
        }
    }

    @SuppressWarnings( "unchecked" )
    private static void appendList( final StringBuilder sb,
                                     final Map< String, Object > props, final String key,
                                     final int boost ) {
        final Object val = props.get( key );
        if( val instanceof List< ? > list ) {
            final StringBuilder items = new StringBuilder();
            for( final Object item : list ) {
                if( item != null ) {
                    if( !items.isEmpty() ) items.append( ' ' );
                    items.append( item );
                }
            }
            if( !items.isEmpty() ) {
                for( int i = 0; i < boost; i++ ) {
                    sb.append( ' ' ).append( items );
                }
            }
        }
    }
}
