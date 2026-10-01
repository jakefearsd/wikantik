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
package com.wikantik.preview;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Plain-text excerpts of a page body for link-preview cards. Never renders HTML. */
public final class PageExcerpts {
    public static final int MAX_CHARS = 280;
    private static final Parser PARSER = Parser.builder().build();
    /** {@code [{ALLOW …}]}, {@code [{DENY …}]} and every other {@code [{Plugin …}]} span. */
    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{.*?}]", Pattern.DOTALL );
    private static final Pattern SPACES = Pattern.compile( "\\s+" );

    private PageExcerpts() {}

    public static String excerpt( final String markdownBody, final int maxChars ) {
        if ( markdownBody == null || markdownBody.isBlank() ) {
            return "";
        }
        final String masked = PLUGIN.matcher( markdownBody ).replaceAll( " " );
        final StringBuilder text = new StringBuilder();
        for ( final Node block : PARSER.parse( masked ).getChildren() ) {
            if ( block instanceof Heading || block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock
                    || block instanceof HtmlBlock || block instanceof ThematicBreak ) {
                continue;
            }
            final List< Node > images = new ArrayList<>();
            for ( final Node d : block.getDescendants() ) {
                if ( d instanceof Image ) {
                    images.add( d );
                }
            }
            images.forEach( Node::unlink );
            text.append( ' ' ).append( new TextCollectingVisitor().collectAndGetText( block ) );
        }
        return truncate( SPACES.matcher( text ).replaceAll( " " ).trim(), maxChars );
    }

    static String truncate( final String s, final int max ) {
        if ( s.length() <= max ) {
            return s;
        }
        int cut = s.lastIndexOf( ' ', max - 1 );
        if ( cut < max / 2 ) {
            cut = max - 1;
        }
        return s.substring( 0, cut ).trim() + "…";
    }
}
