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
package com.wikantik.markdown.extensions.highlight;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;

import java.util.Set;

/** Renders a {@link Highlight} as {@code <mark>…</mark>} (the sanitizer already allows {@code mark}). */
final class HighlightNodeRenderer implements NodeRenderer {

    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        return Set.of( new NodeRenderingHandler<>( Highlight.class, this::render ) );
    }

    private void render( final Highlight node, final NodeRendererContext context, final HtmlWriter html ) {
        html.withAttr().tag( "mark" );
        context.renderChildren( node );
        html.tag( "/mark" );
    }

    static final class Factory implements NodeRendererFactory {
        @Override
        public NodeRenderer apply( final DataHolder options ) {
            return new HighlightNodeRenderer();
        }
    }
}
