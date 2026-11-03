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
package com.wikantik.markdown.extensions.inlinetags;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.RefNode;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import com.wikantik.api.markdown.InlineTagSyntax;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Renders an {@link InlineTag} as {@code <a class="tag inline-tag" href="/search?tag=a-b">#a/b</a>}: the href carries the
 * normalised tag, the text what the author wrote. Inside a link the tag stays plain text, so anchors never nest.
 */
final class InlineTagNodeRenderer implements NodeRenderer {

    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        return Set.of( new NodeRenderingHandler<>( InlineTag.class, this::render ) );
    }

    private void render( final InlineTag node, final NodeRendererContext context, final HtmlWriter html ) {
        final String text = "#" + node.written();
        if ( insideLink( node ) ) {
            html.text( text );
            return;
        }
        final String href = "/search?tag=" + URLEncoder.encode( InlineTagSyntax.normalise( node.written() ), StandardCharsets.UTF_8 );
        html.attr( "class", "tag inline-tag" ).attr( "href", href ).withAttr().tag( "a" );
        html.text( text );
        html.tag( "/a" );
    }

    /**
     * Whether the tag sits in text the page shows as a link: an inline link, a wikilink or an image, or a reference whose
     * definition exists. An unresolved {@code [reference]} renders as plain text, so a tag in it is a tag (the index agrees).
     */
    private static boolean insideLink( final InlineTag node ) {
        for ( Node n = node.getParent(); n != null; n = n.getParent() ) {
            if ( n instanceof Link || n instanceof WikiLink || n instanceof com.vladsch.flexmark.ast.Image ) {
                return true;
            }
            if ( n instanceof RefNode && ( ( RefNode ) n ).isDefined() ) {
                return true;
            }
        }
        return false;
    }

    static final class Factory implements NodeRendererFactory {
        @Override
        public NodeRenderer apply( final DataHolder options ) {
            return new InlineTagNodeRenderer();
        }
    }
}
