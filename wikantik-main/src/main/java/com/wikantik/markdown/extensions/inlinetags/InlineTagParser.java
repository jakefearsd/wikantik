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
package com.wikantik.markdown.extensions.inlinetags;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.InlineParserExtension;
import com.vladsch.flexmark.parser.InlineParserExtensionFactory;
import com.vladsch.flexmark.parser.LightInlineParser;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.api.markdown.InlineTagSyntax;

import java.util.Set;

/**
 * Recognises a {@code #tag} where {@link InlineTagSyntax} does: the {@code #} follows the start of the text or whitespace,
 * the tag characters hold a non-digit, and the block is not a heading. Code spans and math are consumed by their own
 * parsers before a {@code #} inside them can be reached.
 */
final class InlineTagParser implements InlineParserExtension {

    @Override
    public void finalizeDocument( final InlineParser inlineParser ) {
    }

    @Override
    public void finalizeBlock( final InlineParser inlineParser ) {
    }

    @Override
    public boolean parse( final LightInlineParser inlineParser ) {
        final int index = inlineParser.getIndex();
        final BasedSequence input = inlineParser.getInput();
        if ( inlineParser.getBlock() instanceof Heading || index > 0 && !Character.isWhitespace( input.charAt( index - 1 ) ) ) {
            return false;
        }
        int end = index + 1;
        while ( end < input.length() && InlineTagSyntax.isTagChar( input.charAt( end ) ) ) {
            end++;
        }
        if ( end == index + 1 || !InlineTagSyntax.isTagText( input.subSequence( index + 1, end ).toString() ) ) {
            return false;
        }
        inlineParser.flushTextNode();
        inlineParser.getBlock().appendChild( new InlineTag( input.subSequence( index, end ) ) );
        inlineParser.setIndex( end );
        return true;
    }

    /** Registers the {@code #} trigger. */
    static final class Factory implements InlineParserExtensionFactory {

        @Override
        public CharSequence getCharacters() {
            return "#";
        }

        @Override
        public Set< Class< ? > > getAfterDependents() {
            return null;
        }

        @Override
        public Set< Class< ? > > getBeforeDependents() {
            return null;
        }

        @Override
        public InlineParserExtension apply( final LightInlineParser lightInlineParser ) {
            return new InlineTagParser();
        }

        @Override
        public boolean affectsGlobalScope() {
            return false;
        }
    }
}
