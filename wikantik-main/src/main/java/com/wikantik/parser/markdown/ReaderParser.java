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
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.markdown.extensions.math.InlineMathParser;

/**
 * The one parser behind every reader-side read of a page body ({@link ReaderStructure}, {@link ReaderTags}): the render's
 * block and inline structure plus the inline-math parser, run under the {@link BudgetedText} budget. Sharing it keeps those
 * reads from diverging.
 */
final class ReaderParser {

    /** Immutable and thread-safe: the rendered page's structure, without its context-bound extension. */
    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();

    private ReaderParser() {
    }

    /**
     * @param prepared the text {@link MarkdownBodySource#prepare} made of the body
     * @param budget   character reads allowed, or negative for the standard budget of that text's length
     * @return the parsed document
     * @throws RuntimeException when the budget is exhausted or the parse fails
     */
    static Node parse( final String prepared, final long budget ) {
        final long allowed = budget < 0 ? BudgetedText.budgetFor( prepared.length() ) : budget;
        return PARSER.parse( BasedSequence.of( BudgetedText.of( prepared, allowed ) ) );
    }
}
