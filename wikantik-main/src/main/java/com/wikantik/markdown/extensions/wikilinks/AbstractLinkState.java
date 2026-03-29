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
package com.wikantik.markdown.extensions.wikilinks;

import com.wikantik.api.core.Context;
import com.wikantik.parser.LinkParsingOperations;

import java.util.List;
import java.util.regex.Pattern;


/**
 * Base class for link post-processor and attribute-provider states. Holds
 * the shared wiki context, link operations helper, and image-inlining
 * configuration that every state implementation needs.
 */
public abstract class AbstractLinkState {

    private final Context wikiContext;
    private final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;

    protected AbstractLinkState( final Context wikiContext,
                                 final boolean isImageInlining,
                                 final List< Pattern > inlineImagePatterns ) {
        this.wikiContext = wikiContext;
        this.linkOperations = new LinkParsingOperations( wikiContext );
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
    }

    protected Context wikiContext() { return wikiContext; }
    protected LinkParsingOperations linkOperations() { return linkOperations; }
    protected boolean isImageInlining() { return isImageInlining; }
    protected List< Pattern > inlineImagePatterns() { return inlineImagePatterns; }

}
