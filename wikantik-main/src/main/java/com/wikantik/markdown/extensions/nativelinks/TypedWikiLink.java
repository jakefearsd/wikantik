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
package com.wikantik.markdown.extensions.nativelinks;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.parser.WikiLinkSyntax.WikiLinkRef;
import com.wikantik.markdown.nodes.NativeWikiLinkNode;
import com.wikantik.markdown.nodes.NativeWikiLinkNode.Kind;
import com.wikantik.parser.MarkupParser;
import com.wikantik.wikilink.WikiLinkFragments;

/**
 * The link in {@link Context#VAR_LINKS_AS_TYPED} mode: no page, title, alias, heading or attachment lookup, and an
 * embed is just a link. The URL comes only from the cleaned text the author typed.
 */
final class TypedWikiLink {

    private TypedWikiLink() {
    }

    static NativeWikiLinkNode node( final Context context, final WikiLinkRef r ) {
        final String fragment = fragment( r );
        if ( r.isSamePage() ) {
            return NativeWikiLinkPostProcessor.linkNode( fragment, r.displayText(), Kind.ANCHOR, "" );
        }
        final String name = MarkupParser.cleanLink( r.target().strip() );
        final String url = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), name ) + fragment;
        return NativeWikiLinkPostProcessor.linkNode( url, r.displayText(), Kind.PAGE, name );
    }

    /** The {@code #heading} anchor a link carries, or the empty string. */
    static String fragment( final WikiLinkRef r ) {
        return r.heading() != null ? "#" + WikiLinkFragments.of( r.heading() ) : "";
    }
}
