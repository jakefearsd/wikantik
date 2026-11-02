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

/**
 * A text that counts every character read, through every subsequence, and stops the reader at a budget. Handing a
 * flexmark parser this instead of the plain text bounds its cost deterministically: the attributes extension matches
 * <code>{…}</code> with a backtracking expression that is quadratic on, for example, <code>{edit</code> followed by a long
 * run of spaces, and overflows the stack on a long run of attribute-like words. No wall-clock timer and no thread pool:
 * a regex does not honour interrupts, a counter does.
 *
 * <p>Shared by the read-path rule check ({@link RenderPathAccessRules}, which fails closed) and the render
 * ({@link MarkdownParser}, which falls back to a parse without attributes). The budget is {@link #BUDGET_BASE} plus
 * {@link #BUDGET_PER_CHAR} reads per character; the repo page corpus needs at most about 11 per character (asserted by
 * {@code MarkdownParserRenderBudgetTest#repoCorpusNeverExhaustsTheBudget}).
 *
 * <p><b>Disarm it once the parse returns.</b> Every node of the parsed document reads its characters through this text,
 * so a document that is kept (the render caches keep it for an hour) would otherwise keep spending the parse's budget on
 * every render, and stop rendering after a few hundred views. {@link #disarm()} ends the counting for this text and every
 * subsequence of it.
 */
public final class BudgetedText implements CharSequence {

    /** Character reads allowed per character of the text, plus {@link #BUDGET_BASE}. */
    static final int BUDGET_PER_CHAR = 128;

    /** Character reads every parse may make whatever the length (a few milliseconds of parsing). */
    static final int BUDGET_BASE = 4_000_000;

    private final String text;
    private final Meter meter;
    private final int from;
    private final int to;

    /** The count and the budget one text and all its subsequences share. */
    private static final class Meter {
        private final long budget;
        private long reads;
        private boolean armed = true;

        Meter( final long budget ) {
            this.budget = budget;
        }
    }

    private BudgetedText( final String text, final Meter meter, final int from, final int to ) {
        this.text = text;
        this.meter = meter;
        this.from = from;
        this.to = to;
    }

    /**
     * @param text   the text to read
     * @param budget character reads allowed across this text and every subsequence of it
     * @return a counting view of {@code text}
     */
    public static BudgetedText of( final String text, final long budget ) {
        return new BudgetedText( text, new Meter( budget ), 0, text.length() );
    }

    /**
     * @param length length of the text to be parsed
     * @return the standard read budget for a text of that length
     */
    public static long budgetFor( final int length ) {
        return BUDGET_BASE + ( long ) BUDGET_PER_CHAR * length;
    }

    /** @return character reads made so far across this text and all its subsequences, until {@link #disarm()} */
    public long reads() {
        return meter.reads;
    }

    /**
     * Stops counting, for this text and every subsequence of it: from now on it reads like the plain text. Call it once
     * the parse has returned, so a kept document renders as often as it is asked to.
     */
    public void disarm() {
        meter.armed = false;
    }

    private void count( final long n ) {
        if ( !meter.armed ) {
            return;
        }
        meter.reads += n;
        if ( meter.reads > meter.budget ) {
            throw new Exhausted( meter.budget );
        }
    }

    @Override
    public int length() {
        return to - from;
    }

    @Override
    public char charAt( final int index ) {
        count( 1 );
        return text.charAt( from + index );
    }

    @Override
    public CharSequence subSequence( final int start, final int end ) {
        return new BudgetedText( text, meter, from + start, from + end );
    }

    @Override
    public String toString() {
        count( to - from );
        return text.substring( from, to );
    }

    /** Thrown when a parse has read its budget of characters. */
    public static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exhausted( final long budget ) {
            super( "parse read more than " + budget + " characters", null, false, false );
        }
    }
}
