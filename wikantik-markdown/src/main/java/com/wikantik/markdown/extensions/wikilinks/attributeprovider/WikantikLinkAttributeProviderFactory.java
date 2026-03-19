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
import com.vladsch.flexmark.html.AttributeProviderFactory;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.wikantik.api.core.Context;

import java.util.List;
import java.util.regex.Pattern;


/**
 * Simple {@link AttributeProviderFactory} to instantiate {@link WikantikLinkAttributeProvider}s.
 */
public class WikantikLinkAttributeProviderFactory extends IndependentAttributeProviderFactory {

    final Context wikiContext;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    public WikantikLinkAttributeProviderFactory( final Context wikiContext,
                                                final boolean isImageInlining,
                                                final List< Pattern > inlineImagePatterns ) {
        this.wikiContext = wikiContext;
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
    }

    /**
     * {@inheritDoc}
     *
     * @see com.vladsch.flexmark.html.AttributeProviderFactory#apply(com.vladsch.flexmark.html.renderer.LinkResolverContext)
     */
    @Override
    public AttributeProvider apply( final LinkResolverContext context ) {
        return new WikantikLinkAttributeProvider( wikiContext, isImageInlining, inlineImagePatterns );
    }

}
