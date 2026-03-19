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
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.MarkupParser;
import com.wikantik.preferences.Preferences;

import java.text.MessageFormat;
import java.util.ResourceBundle;


/**
 * {@link NodeAttributeProviderState} which sets the attributes for local edit links.
 */
public class LocalEditLinkAttributeProviderState implements NodeAttributeProviderState< WikantikLink > {

    private final Context wikiContext;
    private final String url;

    public LocalEditLinkAttributeProviderState( final Context wikiContext, final String url ) {
        this.wikiContext = wikiContext;
        this.url = url;
    }

    /**
     * {@inheritDoc}
     *
     * @see NodeAttributeProviderState#setAttributes(MutableAttributes, Node)
     */
    @Override
    public void setAttributes( final MutableAttributes attributes, final WikantikLink link ) {
        final ResourceBundle rb = Preferences.getBundle( wikiContext, InternationalizationManager.CORE_BUNDLE );
        attributes.replaceValue( "title", MessageFormat.format( rb.getString( "markupparser.link.create" ), url ) );
        attributes.replaceValue( "class", MarkupParser.CLASS_EDITPAGE );
        attributes.replaceValue( "href", link.getUrl().toString() );
    }

}
