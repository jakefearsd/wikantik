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
package com.wikantik.markdown.extensions.wikilinks.attributeprovider;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.html.MutableAttributes;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.LinkParsingOperations;
import com.wikantik.parser.MarkupParser;

import java.util.List;
import java.util.regex.Pattern;


/**
 * {@link NodeAttributeProviderState} which sets the attributes for external links.
 */
public class ExternalLinkAttributeProviderState implements NodeAttributeProviderState< WikantikLink > {

    private final boolean hasRef;
    private final boolean useRelNofollow;
    private final Context wikiContext;
    private final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    public ExternalLinkAttributeProviderState( final Context wikiContext,
                                               final boolean hasRef,
                                               final boolean isImageInlining,
                                               final List< Pattern > inlineImagePatterns ) {
        this.hasRef = hasRef;
        this.wikiContext = wikiContext;
        this.linkOperations = new LinkParsingOperations( wikiContext );
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
        this.useRelNofollow = wikiContext.getBooleanWikiProperty( MarkupParser.PROP_USERELNOFOLLOW, false );
    }

    /**
     * {@inheritDoc}
     *
     * @see NodeAttributeProviderState#setAttributes(MutableAttributes, Node)
     */
    @Override
    public void setAttributes( final MutableAttributes attributes, final WikantikLink link ) {
        if( linkOperations.isImageLink( link.getUrl().toString(), isImageInlining, inlineImagePatterns ) ) {
            new ImageLinkAttributeProviderState( wikiContext, link.getText().toString(), hasRef ).setAttributes( attributes, link );
        } else {
            attributes.replaceValue( "class", MarkupParser.CLASS_EXTERNAL );
            attributes.replaceValue( "href", link.getUrl().toString() );
        }
        if( useRelNofollow ) {
            attributes.replaceValue( "rel", "nofollow" );
        }
    }

}
