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
package com.wikantik.markdown.extensions.math;

import com.vladsch.flexmark.ext.gitlab.GitLabInlineMath;
import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.InlineParserExtension;
import com.vladsch.flexmark.parser.InlineParserExtensionFactory;
import com.vladsch.flexmark.parser.LightInlineParser;
import com.vladsch.flexmark.util.sequence.BasedSequence;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Flexmark inline parser extension that recognises standard {@code $...$} math delimiters
 * and produces {@link GitLabInlineMath} AST nodes. The GitLab extension's renderer then
 * emits them with the configured {@code math-inline} CSS class.
 *
 * <p>This replaces the GitLab extension's own inline math parser, which uses the
 * {@code $`...`$} syntax instead of the more widely adopted {@code $...$} convention.</p>
 */
public class InlineMathParser implements InlineParserExtension {

    /**
     * Matches inline math: a single {@code $} followed by non-empty content (that does not
     * start or end with a space) and a closing {@code $}. A second {@code $} immediately
     * after the opening one ({@code $$}) is NOT matched — that is display math.
     */
    private static final Pattern INLINE_MATH = Pattern.compile(
            "\\$([^ $](?:[^$]*[^ $])?)\\$|\\$([^ $])\\$"
    );

    public InlineMathParser( @SuppressWarnings( "unused" ) final LightInlineParser inlineParser ) {
    }

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

        // Skip $$ — that's display math, not inline
        if ( index + 1 < input.length() && input.charAt( index + 1 ) == '$' ) {
            return false;
        }

        final BasedSequence remaining = input.subSequence( index );
        final Matcher matcher = INLINE_MATH.matcher( remaining );
        if ( matcher.lookingAt() ) {
            final BasedSequence openMarker = remaining.subSequence( 0, 1 );
            final BasedSequence content = remaining.subSequence( 1, matcher.end() - 1 );
            final BasedSequence closeMarker = remaining.subSequence( matcher.end() - 1, matcher.end() );

            inlineParser.flushTextNode();
            final GitLabInlineMath node = new GitLabInlineMath( openMarker, content, closeMarker );
            node.setCharsFromContent();
            inlineParser.getBlock().appendChild( node );
            inlineParser.setIndex( index + matcher.end() );
            return true;
        }

        return false;
    }

    /**
     * Factory that registers the {@code $} trigger character for inline math parsing.
     */
    public static class Factory implements InlineParserExtensionFactory {

        @Override
        public CharSequence getCharacters() {
            return "$";
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
            return new InlineMathParser( lightInlineParser );
        }

        @Override
        public boolean affectsGlobalScope() {
            return false;
        }
    }

}
