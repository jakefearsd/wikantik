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

import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.block.AbstractBlockParser;
import com.vladsch.flexmark.parser.block.BlockContinue;
import com.vladsch.flexmark.parser.block.BlockParserFactory;
import com.vladsch.flexmark.parser.block.BlockStart;
import com.vladsch.flexmark.parser.block.CustomBlockParserFactory;
import com.vladsch.flexmark.parser.block.MatchedBlockParser;
import com.vladsch.flexmark.parser.block.ParserState;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.parser.markdown.BudgetedText;
import com.wikantik.parser.markdown.FlexmarkParsers;
import com.wikantik.parser.markdown.MarkdownDocument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the {@code $$} display-math blocks of a text with a flexmark parse of the reader's block structure (#128), by the
 * rules the editor's {@code DisplayMath} block parser ({@code dialect/math.js}) follows:
 * <ul>
 *   <li>A block opens at a line that is {@code $$} alone (spaces and tabs around it), indented less than four columns
 *       within its container, with another such line somewhere below it. Never on a {@code > $$} or {@code - $$} line,
 *       and never inside an open fenced code block or HTML block: flexmark does not try block starts there.</li>
 *   <li>It closes at the next {@code $$}-alone line in its container, at any indent; or, when its container (a list
 *       item) ends first, it ends there, unclosed, and that later line is read afresh.</li>
 * </ul>
 * The parse leaves the attributes extension out (it is inline only, so it cannot change a block, and a crafted text
 * makes it quadratic) and runs under the {@link BudgetedText} read budget. A text the parse cannot finish within it has
 * no blocks: its {@code $$} lines stay text, and the render's own budgeted parse decides the rest.
 */
final class DisplayMathBlocks {

    private static final Logger LOG = LogManager.getLogger( DisplayMathBlocks.class );

    private static final Parser LOCATOR = FlexmarkParsers.builder( withoutAttributes( MarkdownDocument.structuralOptions() ) )
            .customBlockParserFactory( new LocatorFactory() )
            .build();

    private DisplayMathBlocks() {
    }

    /** The math block a {@code $$} line opened, as the locating parse read it (the offsets are in the source). */
    static final class Located extends Block {
        int lineStart;
        int indentEnd;
        int openerEnd;
        int contentStart;
        int contentEnd = -1;
        int closerEnd = -1;

        @Override
        public BasedSequence[] getSegments() {
            return EMPTY_SEGMENTS;
        }
    }

    /** @return the blocks of {@code source}, in document order; none when no line is {@code $$} alone */
    static List< Located > find( final String source ) {
        if ( !dollarLineFrom( source, 0 ) ) {
            return List.of();
        }
        final Node document;
        try {
            document = LOCATOR.parse( BasedSequence.of( BudgetedText.of( source, BudgetedText.budgetFor( source.length() ) ) ) );
        } catch ( final RuntimeException | StackOverflowError e ) {
            LOG.warn( "Display math could not be located in a text of {} characters ({}); its $$ lines stay text",
                    source.length(), e.toString() );
            return List.of();
        }
        final List< Located > blocks = new ArrayList<>();
        for ( final Node node : document.getDescendants() ) {
            if ( node instanceof Located located ) {
                blocks.add( located );
            }
        }
        return blocks;
    }

    private static MutableDataSet withoutAttributes( final MutableDataSet options ) {
        final List< Extension > extensions = new ArrayList<>( Parser.EXTENSIONS.get( options ) );
        extensions.removeIf( AttributesExtension.class::isInstance );
        return options.set( Parser.EXTENSIONS, extensions );
    }

    /** Whether {@code s[from, to)} is {@code $$} with only spaces and tabs (and a trailing CR) around it. */
    static boolean isDollarLine( final CharSequence s, final int from, final int to ) {
        int end = to;
        if ( end > from && s.charAt( end - 1 ) == '\r' ) {
            end--;
        }
        int i = from;
        while ( i < end && isBlank( s.charAt( i ) ) ) {
            i++;
        }
        if ( i + 2 > end || s.charAt( i ) != '$' || s.charAt( i + 1 ) != '$' ) {
            return false;
        }
        i += 2;
        while ( i < end && isBlank( s.charAt( i ) ) ) {
            i++;
        }
        return i == end;
    }

    static boolean isBlank( final char c ) {
        return c == ' ' || c == '\t';
    }

    /** Whether a {@code $$}-alone line starts at or after {@code from}. Linear: it stops at the first one. */
    static boolean dollarLineFrom( final CharSequence s, final int from ) {
        int lineStart = from;
        while ( lineStart < s.length() ) {
            int nl = lineStart;
            while ( nl < s.length() && s.charAt( nl ) != '\n' ) {
                nl++;
            }
            if ( isDollarLine( s, lineStart, nl ) ) {
                return true;
            }
            lineStart = nl + 1;
        }
        return false;
    }

    private static final class LocatorFactory implements CustomBlockParserFactory {

        @Override
        public BlockParserFactory apply( final DataHolder options ) {
            return DisplayMathBlocks::tryStart;
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
        public boolean affectsGlobalScope() {
            return false;
        }
    }

    private static BlockStart tryStart( final ParserState state, final MatchedBlockParser matched ) {
        if ( state.getIndent() >= 4 ) {
            return BlockStart.none();
        }
        final BasedSequence line = state.getLine();
        final BasedSequence base = line.getBaseSequence();
        final int start = line.getStartOffset();
        final int end = line.getEndOffset();
        if ( !isDollarLine( base, start, end ) ) {
            return BlockStart.none();
        }
        final int next = state.getLineWithEOL().getEndOffset();
        if ( !dollarLineFrom( base, next ) ) {
            return BlockStart.none();
        }
        final Located block = new Located();
        block.lineStart = start;
        int indentEnd = start;
        while ( isBlank( base.charAt( indentEnd ) ) ) {
            indentEnd++;
        }
        block.indentEnd = indentEnd;
        block.openerEnd = end;
        block.contentStart = next;
        return BlockStart.of( new MathBlockParser( block ) ).atIndex( line.length() );
    }

    /** Takes every line of its container to the next {@code $$}-alone line, which it takes too and stops. */
    private static final class MathBlockParser extends AbstractBlockParser {

        private final Located block;

        MathBlockParser( final Located block ) {
            this.block = block;
        }

        @Override
        public Block getBlock() {
            return block;
        }

        @Override
        public BlockContinue tryContinue( final ParserState state ) {
            final BasedSequence line = state.getLine();
            if ( isDollarLine( line.getBaseSequence(), line.getStartOffset(), line.getEndOffset() ) ) {
                block.closerEnd = line.getEndOffset();
                return BlockContinue.finished();
            }
            block.contentEnd = line.getEndOffset();
            return BlockContinue.atIndex( state.getIndex() );
        }

        @Override
        public void closeBlock( final ParserState state ) {
            // the offsets were recorded line by line
        }
    }
}
