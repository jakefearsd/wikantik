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

import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.html.MutableAttributes;
import org.apache.commons.lang3.Strings;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.LinkParsingOperations;
import com.wikantik.util.TextUtil;

import java.util.List;
import java.util.regex.Pattern;


/**
 * {@link AttributeProvider} to decorate {@link WikantikLink}s.
 * <p>
 * Acts as a factory of {@link NodeAttributeProviderState}s, which are the classes setting the attributes for each concrete type of link.
 */
public class WikantikLinkAttributeProvider implements AttributeProvider {

    protected final Context wikiContext;
    protected final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    public WikantikLinkAttributeProvider( final Context wikiContext,
                                         final boolean isImageInlining,
                                         final List< Pattern > inlineImagePatterns ) {
        this.wikiContext = wikiContext;
        this.linkOperations = new LinkParsingOperations( wikiContext );
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
    }

    /**
     * {@inheritDoc}
     *
     * @see AttributeProvider#setAttributes(Node, AttributablePart, MutableAttributes)
     */
    @Override
    public void setAttributes( final Node node, final AttributablePart part, final MutableAttributes attributes ) {
        if( node instanceof WikantikLink link ) {
            final NodeAttributeProviderState< WikantikLink > linkState;
            if( linkOperations.isExternalLink( link.getWikiLink() ) ) {
                linkState = new ExternalLinkAttributeProviderState( wikiContext, link.hasRef(), isImageInlining, inlineImagePatterns );
            } else if( linkOperations.isInterWikiLink( link.getWikiLink() ) ) {
                linkState = new InterWikiLinkAttributeProviderState( wikiContext, link.hasRef(), isImageInlining, inlineImagePatterns );
            } else if( Strings.CS.startsWith( link.getWikiLink(), "#" ) ) {
                linkState = new LocalFootnoteLinkAttributeProviderState( wikiContext );
            } else if( TextUtil.isNumber( link.getWikiLink() ) ) {
                linkState = new LocalFootnoteRefLinkAttributeProviderState( wikiContext );
            } else {
                linkState = new LocalLinkAttributeProviderState( wikiContext, link.hasRef(), isImageInlining, inlineImagePatterns );
            }
            linkState.setAttributes( attributes, link );
        }
    }

}
