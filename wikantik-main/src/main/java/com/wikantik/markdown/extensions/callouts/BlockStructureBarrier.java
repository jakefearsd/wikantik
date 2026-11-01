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
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.ext.attributes.internal.AttributesNodePostProcessor;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.block.DocumentPostProcessor;
import com.vladsch.flexmark.parser.block.DocumentPostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.data.MutableDataHolder;

import java.util.Set;

/**
 * Makes the structural parse ({@code MarkdownDocument.structuralOptions()}: block numbering, the access-rule reading,
 * the mention scan) build callouts as the rendered page's parse does. Registered first in that parse's extension list,
 * it adds a post-processor that does nothing and runs after the callout and {@code {…}}-attributes post-processors —
 * the place the block-id post-processor holds in the rendered page's parse, whose extension list it heads.
 *
 * <p>Why: flexmark orders the callout and attributes post-processors from the dependency graph and registration order,
 * and without such a dependent it ran attributes first. Attaching attributes wraps text in a node of its own; one that
 * spans a callout's title marker makes the callout read as malformed. So {@code > [!note] Title\n> Text {.cls} more}
 * rendered as a callout but was numbered as a plain quote. Declaring the order outright (callout before attributes)
 * is what this should be, but flexmark's resolver reports a dependency cycle for it next to the block-id
 * post-processor's own ordering. {@code BlockIdRenderTest} and {@code BlockIdRenderPropertyTest} pin the two parses
 * together.
 */
public final class BlockStructureBarrier implements Parser.ParserExtension {

    private BlockStructureBarrier() {
    }

    public static BlockStructureBarrier create() {
        return new BlockStructureBarrier();
    }

    @Override
    public void parserOptions( final MutableDataHolder options ) {
        // no options
    }

    @Override
    public void extend( final Parser.Builder parserBuilder ) {
        parserBuilder.postProcessorFactory( new Factory() );
    }

    /** Runs after callouts are built and attributes attached, and changes nothing. */
    static final class Factory extends DocumentPostProcessorFactory {
        @Override
        public Set< Class< ? > > getAfterDependents() {
            return Set.of( CalloutPostProcessor.Factory.class, AttributesNodePostProcessor.Factory.class );
        }

        @Override
        public DocumentPostProcessor apply( final Document document ) {
            return new DocumentPostProcessor() {
                @Override
                public Document processDocument( final Document doc ) {
                    return doc;
                }
            };
        }
    }
}
