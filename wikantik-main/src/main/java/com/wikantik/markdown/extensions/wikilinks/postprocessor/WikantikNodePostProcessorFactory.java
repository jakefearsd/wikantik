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
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ext.autolink.internal.AutolinkNodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.data.DataHolder;
import com.wikantik.api.core.Context;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * Simple {@link NodePostProcessorFactory} to instantiate {@link WikantikLinkNodePostProcessor}s.
 */
public class WikantikNodePostProcessorFactory extends NodePostProcessorFactory {

    private final Context context;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    public WikantikNodePostProcessorFactory( final Context context,
                                            final DataHolder options,
                                            final boolean isImageInlining,
                                            final List< Pattern > inlineImagePatterns ) {
        super( true );
        addNodes( Link.class, Image.class, AutoLink.class, MailLink.class ); // needs to be called before create( Document )
        this.context = context;
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
    }

    /**
     * Runs after the GFM autolink pass, so the autolinks it creates are decorated as external links too. Note:
     * {@link AutolinkNodePostProcessor} lives in flexmark's {@code internal} package (it is the only handle on that pass's
     * factory); a flexmark upgrade that moves it fails compilation here rather than silently reordering the passes.
     */
    @Override
    public Set< Class< ? > > getAfterDependents() {
        return Set.of( AutolinkNodePostProcessor.Factory.class );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodePostProcessor apply( final Document document ) {
        return new WikantikLinkNodePostProcessor( context, document, isImageInlining, inlineImagePatterns );
    }

}
