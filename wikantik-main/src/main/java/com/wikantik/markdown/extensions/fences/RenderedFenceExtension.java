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

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;

/**
 * Wraps registered fenced code blocks (for example {@code mermaid}) in the fixed
 * {@code <figure class="wk-fence">} envelope. Every other fence is delegated untouched.
 */
public final class RenderedFenceExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

    private final RenderedFenceRegistry registry;

    private RenderedFenceExtension( final RenderedFenceRegistry registry ) {
        this.registry = registry;
    }

    public static RenderedFenceExtension create( final RenderedFenceRegistry registry ) {
        return new RenderedFenceExtension( registry );
    }

    @Override public void parserOptions( final MutableDataHolder options ) { }
    @Override public void rendererOptions( final MutableDataHolder options ) { }
    @Override public void extend( final Parser.Builder parserBuilder ) { }

    @Override
    public void extend( final HtmlRenderer.Builder rendererBuilder, final String rendererType ) {
        rendererBuilder.nodeRendererFactory( new RenderedFenceNodeRenderer.Factory( registry ) );
    }
}
