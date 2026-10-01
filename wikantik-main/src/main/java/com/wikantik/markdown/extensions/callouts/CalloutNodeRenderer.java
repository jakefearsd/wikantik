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
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;

import java.util.Set;

/** Renders {@link CalloutBlock}/{@link CalloutTitle} as the shared callout HTML (see the plan / fixture). */
public class CalloutNodeRenderer implements NodeRenderer {

    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        return Set.of( new NodeRenderingHandler<>( CalloutBlock.class, this::render ),
                       new NodeRenderingHandler<>( CalloutTitle.class, ( n, ctx, html ) -> ctx.renderChildren( n ) ) );
    }

    private void render( final CalloutBlock node, final NodeRendererContext context, final HtmlWriter html ) {
        final boolean folded = node.fold() != CalloutBlock.Fold.NONE;
        final String tag = folded ? "details" : "div";
        html.line();
        html.attr( "class", "callout callout-" + node.style() ).attr( "data-callout", node.style() );
        if ( node.fold() == CalloutBlock.Fold.EXPANDED ) {
            html.attr( "open", "" );
        }
        html.withAttr().tag( tag ).line();
        final String titleTag = folded ? "summary" : "div";
        html.attr( "class", "callout-title" ).withAttr().tag( titleTag );
        html.raw( "<span class=\"callout-icon\" aria-hidden=\"true\"></span><span class=\"callout-title-inner\">" );
        if ( node.title().hasChildren() ) {
            context.renderChildren( node.title() );
        } else {
            html.text( CalloutTypes.defaultTitle( node.rawType() ) );
        }
        html.raw( "</span>" ).closeTag( titleTag ).line();
        html.attr( "class", "callout-content" ).withAttr().tag( "div" ).line();
        for ( Node c = node.title().getNext(); c != null; c = c.getNext() ) {
            context.render( c );
        }
        html.closeTag( "div" ).line();
        html.closeTag( tag ).line();
    }

    public static class Factory implements NodeRendererFactory {
        @Override
        public NodeRenderer apply( final DataHolder options ) {
            return new CalloutNodeRenderer();
        }
    }
}
