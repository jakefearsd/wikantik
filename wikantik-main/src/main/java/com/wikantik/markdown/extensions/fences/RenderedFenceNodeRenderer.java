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
package com.wikantik.markdown.extensions.fences;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.Set;

/** Emits the {@code wk-fence} envelope for registered fences; delegates every other fence. */
public class RenderedFenceNodeRenderer implements NodeRenderer {

    private static final Logger LOG = LogManager.getLogger( RenderedFenceNodeRenderer.class );

    private final RenderedFenceRegistry registry;

    RenderedFenceNodeRenderer( final RenderedFenceRegistry registry ) {
        this.registry = registry;
    }

    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        return Set.of( new NodeRenderingHandler<>( FencedCodeBlock.class, this::render ) );
    }

    private void render( final FencedCodeBlock node, final NodeRendererContext context, final HtmlWriter html ) {
        final Optional< RenderedFenceRenderer > found =
                registry.forInfo( FenceInfo.parse( node.getInfo().toString() ) );
        if ( found.isEmpty() ) {
            context.delegateRender();
            return;
        }
        final RenderedFenceRenderer renderer = found.get();
        final String language = renderer.language();
        final String source = node.getContentChars().normalizeEOL();
        final FenceVerdict verdict = renderer.check( source );

        String error = verdict.allowed() ? null : verdict.reason();
        String drawing = null;
        if ( error == null && renderer.kind() == FenceKind.SERVER ) {
            try {
                drawing = renderer.renderDrawing( source );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Rendered fence '{}' failed to render its drawing: {}", language, e.getMessage(), e );
                error = "render-failed";
            }
        }

        html.line();
        html.attr( "class", "wk-fence" ).attr( "data-fence", language )
            .attr( "data-fence-kind", renderer.kind() == FenceKind.SERVER ? "server" : "browser" );
        if ( error != null ) {
            html.attr( "data-fence-error", error );
        }
        html.withAttr().tag( "figure" ).line();
        if ( drawing != null ) {
            html.attr( "class", "wk-fence-drawing" ).withAttr().tag( "div" );
            html.raw( drawing );
            html.tag( "/div" ).line();
        }
        html.attr( "class", "wk-fence-source" ).withAttr().tag( "pre" ).openPre();
        html.attr( "class", "language-" + language ).withAttr().tag( "code" );
        html.text( source );
        html.tag( "/code" );
        html.tag( "/pre" ).closePre().line();
        html.tag( "/figure" ).line();
    }

    public static class Factory implements NodeRendererFactory {
        private final RenderedFenceRegistry registry;

        public Factory( final RenderedFenceRegistry registry ) {
            this.registry = registry;
        }

        @Override
        public NodeRenderer apply( final DataHolder options ) {
            return new RenderedFenceNodeRenderer( registry );
        }
    }
}
