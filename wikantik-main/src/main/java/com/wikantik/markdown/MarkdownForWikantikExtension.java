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
package com.wikantik.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.extensions.wikilinks.attributeprovider.WikantikLinkAttributeProviderFactory;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.WikantikNodePostProcessorFactory;
import com.wikantik.markdown.renderer.WikantikNodeRendererFactory;

import java.util.List;
import java.util.regex.Pattern;


/**
 * Flexmark entry point to bootstrap JSPWiki extensions.
 */
public class MarkdownForWikantikExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

	private final Context context;
	private final boolean isImageInlining;
	private final List< Pattern > inlineImagePatterns;

	public MarkdownForWikantikExtension( final Context context,
										final boolean isImageInlining,
										final List< Pattern > inlineImagePatterns ) {
		this.context = context;
		this.isImageInlining = isImageInlining;
		this.inlineImagePatterns = inlineImagePatterns;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void rendererOptions( final MutableDataHolder options ) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void parserOptions( final MutableDataHolder options ) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void extend( final HtmlRenderer.Builder rendererBuilder, final String rendererType ) {
	    rendererBuilder.nodeRendererFactory( new WikantikNodeRendererFactory( context ) );
        rendererBuilder.attributeProviderFactory( new WikantikLinkAttributeProviderFactory( context, isImageInlining, inlineImagePatterns ) );
	}

    /**
	 * {@inheritDoc}
	 */
	@Override
	public void extend( final Parser.Builder parserBuilder ) {
	    parserBuilder.postProcessorFactory( new WikantikNodePostProcessorFactory( context, parserBuilder, isImageInlining, inlineImagePatterns ) );
	}

}
