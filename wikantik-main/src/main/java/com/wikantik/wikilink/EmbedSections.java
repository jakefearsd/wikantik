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
package com.wikantik.wikilink;

import com.wikantik.export.HeadingSlugs;
import com.wikantik.parser.markdown.BlockIds;

import java.util.Optional;

/** What an embed's {@code #part} selects from a page's text: a heading's section, or a {@code ^id} block. */
final class EmbedSections {

    private EmbedSections() {
    }

    /** The selected source ({@code heading == null} selects everything); empty when absent or blank. */
    static Optional< String > select( final String text, final String heading ) {
        if ( heading == null ) {
            return Optional.of( text );
        }
        if ( BlockIds.isBlockRef( heading ) ) {
            // strip: a nested block's marker would otherwise render as a second id="^x" anchor inside the embed
            return BlockIds.find( text, BlockIds.idOf( heading ) ).map( b -> BlockIds.strip( BlockIds.sourceOf( text, b ) ) )
                    .filter( s -> !s.isBlank() );
        }
        return HeadingSlugs.sectionBody( text, HeadingSlugs.slug( heading ) ).filter( s -> !s.isBlank() );
    }

    /** The embed's title: the page, then {@code › heading}; a block embed is titled by its page alone ("› ^id" is noise). */
    static String title( final String pageName, final String heading ) {
        return heading == null || BlockIds.isBlockRef( heading ) ? pageName : pageName + " \u203a " + heading;
    }

    /** The message for a selection that matched nothing (unescaped). */
    static String notFound( final String heading ) {
        return ( BlockIds.isBlockRef( heading ) ? "Block not found: " : "Section not found: " ) + heading;
    }
}
