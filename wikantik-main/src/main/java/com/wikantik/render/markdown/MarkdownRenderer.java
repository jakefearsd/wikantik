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
package com.wikantik.render.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.wikantik.api.core.Context;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import com.wikantik.parser.markdown.MarkdownDocument;
import com.wikantik.parser.markdown.WikantikHtmlSanitizer;
import com.wikantik.render.WikiRenderer;

import java.io.IOException;


/**
 * Class handling the markdown rendering.
 */
public class MarkdownRenderer extends WikiRenderer {

	private final HtmlRenderer renderer;
	private final boolean allowHtml;

	public MarkdownRenderer( final Context context, final WikiDocument doc ) {
		super( context, doc );
		this.allowHtml = context.getBooleanWikiProperty( MarkupParser.PROP_ALLOWHTML, false );
		// The previous implementation instantiated a full throwaway MarkdownParser
		// (building a second flexmark Parser) just to read the image-inlining
		// default and the engine-cached pattern list. Read both directly.
		renderer = HtmlRenderer.builder( MarkdownDocument.options( context,
				MarkupParser.DEFAULT_IMAGE_INLINING,
				MarkupParser.inlineImagePatterns( context.getEngine() ) ) ).build();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getString() throws IOException {
		document.setContext( context );
		if( document instanceof MarkdownDocument markdownDoc ) {
			final String rendered = renderer.render( markdownDoc.getMarkdownNode() );
			// When raw HTML is allowed in the source, any <script>, <iframe>, or event-handler
			// attribute the user typed is about to reach the browser verbatim. Run it through
			// the OWASP sanitizer to strip dangerous constructs. When allowHTML is false,
			// Flexmark's ESCAPE_HTML has already neutralized raw markup, so we skip the extra
			// work.
			return allowHtml ? WikantikHtmlSanitizer.sanitize( rendered ) : rendered;
		} else {
			throw new IOException( "MarkdownRenderer requires to be used with MarkdownParser" );
		}
	}

}
