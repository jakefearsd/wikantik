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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.ext.definition.DefinitionExtension;
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.markdown.MarkdownForWikantikExtension;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;


/**
 * Simple placeholder for Markdown Nodes
 */
public class MarkdownDocument extends WikiDocument {

    private static final long serialVersionUID = 1L;

    private final Node md;

    public MarkdownDocument( final Page page, final Node md ) {
        super( page );
        this.md = md;
    }

    public Node getMarkdownNode() {
        return md;
    }

    /**
     * Configuration options for MarkdownRenderers.
     *
     * @param context current wiki context
     * @return configuration options for MarkdownRenderers.
     */
    public static MutableDataSet options( final Context context, final boolean isImageInlining, final List< Pattern > inlineImagePatterns ) {
        final MutableDataSet options = new MutableDataSet();
        options.setFrom( ParserEmulationProfile.COMMONMARK );
        options.set( AttributesExtension.ASSIGN_TEXT_ATTRIBUTES, true );
        // align style of Markdown's footnotes extension with jspwiki footnotes refs
        options.set( FootnoteExtension.FOOTNOTE_LINK_REF_CLASS, MarkupParser.CLASS_FOOTNOTE_REF );
        options.set( HtmlRenderer.ESCAPE_HTML, !context.getBooleanWikiProperty( MarkupParser.PROP_ALLOWHTML, false ) );
        options.set( Parser.EXTENSIONS, Arrays.asList( new Extension[] { new MarkdownForWikantikExtension( context, isImageInlining, inlineImagePatterns ),
                                                                         AttributesExtension.create(),
                                                                         DefinitionExtension.create(),
                                                                         FootnoteExtension.create(),
                                                                         TablesExtension.create(),
                                                                         TocExtension.create() } ) );
        return options;
    }

}
