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
package com.wikantik.markdown.renderer;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.LinkType;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.html.renderer.ResolvedLink;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.WikiHtmlInline;
import com.wikantik.markdown.nodes.NativeWikiLinkNode;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.markdown.nodes.WikiEmbedBlock;
import com.wikantik.wikilink.WikiEmbedRenderer;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;


/**
 * Flexmark {@link NodeRenderer} for {@link WikantikLink}s.
 */
public class WikantikLinkRenderer implements NodeRenderer {

    /** The context of the render this renderer serves (flexmark builds one renderer per render); null when headless. */
    private final Context wikiContext;
    private WikiEmbedRenderer embeds;

    public WikantikLinkRenderer( final Context wikiContext ) {
        this.wikiContext = wikiContext;
    }

    /**
     * {@inheritDoc}
     *
     * @see com.vladsch.flexmark.html.renderer.NodeRenderer#getNodeRenderingHandlers()
     */
    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        final HashSet< NodeRenderingHandler< ? > > set = new HashSet<>();
        set.add( new NodeRenderingHandler<>( WikantikLink.class, WikantikLinkRenderer::renderLink ) );
        // flexmark dispatches by exact node class, so the native [[ ]] subclass needs its own registration
        set.add( new NodeRenderingHandler<>( NativeWikiLinkNode.class, WikantikLinkRenderer::renderLink ) );
        set.add( new NodeRenderingHandler<>( WikiEmbedBlock.class, this::renderEmbed ) );
        set.add( new NodeRenderingHandler<>( WikiHtmlInline.class, new NodeRenderingHandler.CustomNodeRenderer<>() {

            /**
             * {@inheritDoc}
             */
            @Override
            public void render( final WikiHtmlInline node, final NodeRendererContext context, final HtmlWriter html ) {
                html.raw( node.getChars().normalizeEOL() );
            }
        } ) );
        return set;
    }

    /**
     * Transcludes the embed now, for this render's viewer (the parsed document holds only a placeholder). When the
     * render may not transclude (plugins disabled, embed budget spent) the embed is the plain page link instead.
     */
    private void renderEmbed( final WikiEmbedBlock node, final NodeRendererContext context, final HtmlWriter html ) {
        final Optional< String > block = embedHtml( node );
        html.line();
        if ( block.isPresent() ) {
            html.raw( block.get() );
        } else {
            html.tag( "p" );
            context.render( node.fallbackLink() );
            html.tag( "/p" );
        }
        html.line();
    }

    private Optional< String > embedHtml( final WikiEmbedBlock node ) {
        if ( wikiContext == null ) {
            return Optional.empty();
        }
        if ( embeds == null ) {
            embeds = WikiEmbedRenderer.forEngine( wikiContext.getEngine() );
        }
        return embeds.renderBudgeted( wikiContext, node.target(), node.heading() );
    }

    private static void renderLink( final WikantikLink node, final NodeRendererContext context, final HtmlWriter html ) {
        if( context.isDoNotRenderLinks() ) {
            context.renderChildren( node );
        } else {
            // standard Link Rendering
            final ResolvedLink resolvedLink = context.resolveLink( LinkType.LINK, node.getUrl().unescape(), null );

            html.attr( "href", resolvedLink.getUrl() );
            if( node.getTitle().isNotNull() ) {
                html.attr( "title", node.getTitle().unescape() );
            }
            html.srcPos( node.getChars() ).withAttr( resolvedLink ).tag( "a" );
            context.renderChildren( node );
            html.tag( "/a" );
        }
    }

}
