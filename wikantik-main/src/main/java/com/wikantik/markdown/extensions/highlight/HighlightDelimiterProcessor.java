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
package com.wikantik.markdown.extensions.highlight;

import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.core.delimiter.Delimiter;
import com.vladsch.flexmark.parser.delimiter.DelimiterProcessor;
import com.vladsch.flexmark.parser.delimiter.DelimiterRun;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Pairs {@code ==} delimiter runs into a {@link Highlight}. The flanking rules are CommonMark's (the same as emphasis and
 * strikethrough, so {@code == x ==} and {@code a == b} are text and {@code ==**x**==} nests). Only a run of exactly two
 * {@code =} on both sides pairs, as the editor preview's micromark construct does: {@code ===x===} stays text.
 */
final class HighlightDelimiterProcessor implements DelimiterProcessor {

    @Override
    public char getOpeningCharacter() {
        return '=';
    }

    @Override
    public char getClosingCharacter() {
        return '=';
    }

    @Override
    public int getMinLength() {
        return 2;
    }

    @Override
    public boolean canBeOpener( final String before, final String after, final boolean leftFlanking, final boolean rightFlanking,
                                final boolean beforeIsPunctuation, final boolean afterIsPunctuation,
                                final boolean beforeIsWhitespace, final boolean afterIsWhiteSpace ) {
        return leftFlanking;
    }

    @Override
    public boolean canBeCloser( final String before, final String after, final boolean leftFlanking, final boolean rightFlanking,
                                final boolean beforeIsPunctuation, final boolean afterIsPunctuation,
                                final boolean beforeIsWhitespace, final boolean afterIsWhiteSpace ) {
        return rightFlanking;
    }

    @Override
    public boolean skipNonOpenerCloser() {
        return false;
    }

    @Override
    public int getDelimiterUse( final DelimiterRun opener, final DelimiterRun closer ) {
        return opener.length() == 2 && closer.length() == 2 ? 2 : 0;
    }

    @Override
    public Node unmatchedDelimiterNode( final InlineParser inlineParser, final DelimiterRun delimiter ) {
        return null;
    }

    @Override
    public void process( final Delimiter opener, final Delimiter closer, final int delimitersUsed ) {
        final Highlight highlight = new Highlight( opener.getTailChars( delimitersUsed ), com.vladsch.flexmark.util.sequence.BasedSequence.NULL,
                closer.getLeadChars( delimitersUsed ) );
        opener.moveNodesBetweenDelimitersTo( highlight, closer );
    }
}
