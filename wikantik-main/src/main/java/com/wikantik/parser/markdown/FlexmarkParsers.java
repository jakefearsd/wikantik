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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.block.BlockParserFactory;
import com.vladsch.flexmark.parser.block.BlockStart;
import com.vladsch.flexmark.parser.block.CustomBlockParserFactory;
import com.vladsch.flexmark.parser.block.ParserState;
import com.vladsch.flexmark.parser.core.HeadingParser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.vladsch.flexmark.util.sequence.mappers.SpecialLeadInHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one way to build a flexmark {@link Parser}: flexmark's own builder with its heading factory wrapped (#129).
 *
 * <p>flexmark's (0.62 through at least 0.64.8) heading factory asks for the open paragraph's text ({@code getParagraphContent()}, which rebuilds
 * it) on every line that reaches block-start matching, before testing the line, so a paragraph of lines that start with
 * a non-letter costs time quadratic in its length: 4,000 such lines in a block quote took seconds. The wrapper hands a
 * line to flexmark's factory only when the line could start a heading, and refuses every other line itself. That changes
 * no parse: flexmark's factory starts a heading only on a line whose text, from its first non-space character, matches
 * its ATX or setext pattern, and the wrapper's test accepts a superset of both (any {@code #} run of 1-6 followed by
 * a space, a tab or the line's end, or what the options that relax that accept; and any line of only {@code =}
 * or only {@code -} with trailing blanks). A line it passes on ends the paragraph or is a heading, so each paragraph's
 * text is rebuilt at most once.</p>
 *
 * <p>flexmark's own heading factory is switched off ({@link Parser#HEADING_PARSER}) and the wrapper takes its place in
 * the block-parser order, with the same before and after dependents. Options that already switch headings off are left
 * as they are.</p>
 */
public final class FlexmarkParsers {

    private FlexmarkParsers() {
    }

    /** @return flexmark's builder for its default options, with the linear heading factory */
    public static Parser.Builder builder() {
        return builder( new MutableDataSet() );
    }

    /**
     * @param options the parser options, including any {@link Parser#EXTENSIONS}; not modified
     * @return flexmark's builder for {@code options}, with the linear heading factory
     */
    public static Parser.Builder builder( final DataHolder options ) {
        if ( !Parser.HEADING_PARSER.get( options ) ) {
            return Parser.builder( options );   // no headings asked for: flexmark's factory is off, nothing to wrap
        }
        final MutableDataSet linear = new MutableDataSet( options );
        final List< Extension > extensions = new ArrayList<>( Parser.EXTENSIONS.get( options ) );
        extensions.add( LinearHeadings.INSTANCE );
        linear.set( Parser.EXTENSIONS, extensions );
        return Parser.builder( linear );
    }

    /** Switches flexmark's heading factory off and registers the wrapper in its place. */
    private static final class LinearHeadings implements Parser.ParserExtension {

        static final LinearHeadings INSTANCE = new LinearHeadings();

        @Override
        public void parserOptions( final MutableDataHolder options ) {
            options.set( Parser.HEADING_PARSER, false );
        }

        @Override
        public void extend( final Parser.Builder builder ) {
            builder.customBlockParserFactory( new Factory() );
        }
    }

    /** flexmark's heading factory, in its place in the order, behind {@link HeadingLine}. */
    private static final class Factory implements CustomBlockParserFactory {

        private final HeadingParser.Factory flexmark = new HeadingParser.Factory();

        @Override
        public BlockParserFactory apply( final DataHolder options ) {
            final BlockParserFactory headings = flexmark.apply( options );
            final HeadingLine candidate = new HeadingLine( options );
            return ( state, matched ) -> candidate.couldStart( state ) ? headings.tryStart( state, matched ) : BlockStart.none();
        }

        @Override
        public Set< Class< ? > > getAfterDependents() {
            return flexmark.getAfterDependents();
        }

        @Override
        public Set< Class< ? > > getBeforeDependents() {
            return flexmark.getBeforeDependents();
        }

        @Override
        public boolean affectsGlobalScope() {
            return flexmark.affectsGlobalScope();
        }

        @Override
        public SpecialLeadInHandler getLeadInHandler( final DataHolder options ) {
            return flexmark.getLeadInHandler( options );
        }
    }

    /** Whether a line could be one flexmark's heading factory turns into a heading: a superset of its ATX and setext patterns. */
    private static final class HeadingLine {

        private static final Pattern SETEXT = Pattern.compile( "^(?:=+|-+)[ \t]*$" );
        private static final Pattern ATX = Pattern.compile( "^#{1,6}(?:[ \t]|$)" );
        /** {@link Parser#HEADING_NO_ATX_SPACE}: flexmark's {@code ^#{1,6}(?:[ \t]*|$)}, which is any {@code #}. */
        private static final Pattern ATX_NO_SPACE = Pattern.compile( "^#" );
        /** {@link Parser#HEADING_NO_EMPTY_HEADING_WITHOUT_SPACE}: flexmark's {@code ^#{1,6}(?:[ \t]*(?=[^ \t#])|[ \t]+$)}. */
        private static final Pattern ATX_NO_EMPTY = Pattern.compile( "^#{1,6}(?:[ \t]*[^ \t#]|[ \t]+$)" );

        private final Pattern atx;

        HeadingLine( final DataHolder options ) {
            atx = Parser.HEADING_NO_ATX_SPACE.get( options ) ? ATX_NO_SPACE
                  : Parser.HEADING_NO_EMPTY_HEADING_WITHOUT_SPACE.get( options ) ? ATX_NO_EMPTY : ATX;
        }

        boolean couldStart( final ParserState state ) {
            final BasedSequence line = state.getLine();
            final int from = state.getNextNonSpaceIndex();
            if ( from >= line.length() ) {
                return false;
            }
            final char first = line.charAt( from );
            final CharSequence rest = line.subSequence( from, line.length() );
            return first == '#' ? atx.matcher( rest ).find() : ( first == '=' || first == '-' ) && SETEXT.matcher( rest ).find();
        }
    }
}
