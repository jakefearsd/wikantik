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
package com.wikantik.markdown.extensions.nativelinks;

import com.vladsch.flexmark.ext.wikilink.WikiImage;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.wikantik.api.core.Context;

import java.util.List;
import java.util.regex.Pattern;


/**
 * Instantiates the {@link NativeWikiLinkPostProcessor} that turns {@code [[ ]]} tokens into links.
 */
public class NativeWikiLinkPostProcessorFactory extends NodePostProcessorFactory {

    private final Context context;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    public NativeWikiLinkPostProcessorFactory( final Context context,
                                               final boolean isImageInlining,
                                               final List< Pattern > inlineImagePatterns ) {
        super( true );
        addNodes( WikiLink.class, WikiImage.class ); // needs to be called before apply( Document )
        this.context = context;
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodePostProcessor apply( final Document document ) {
        return new NativeWikiLinkPostProcessor( context, isImageInlining, inlineImagePatterns );
    }

}
