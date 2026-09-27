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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted tests for {@link LatexRules} branches not exercised by {@code MathRuleProbeTest}:
 * the control-sequence and control-symbol arms of {@code consumeArg}, and {@code unknownCommand}.
 */
class LatexRulesTest {

    // -------------------------------------------------------------------------
    //  consumeArg — control sequence (\cmd) consumes the whole letter run
    // -------------------------------------------------------------------------

    @Test
    void consumeArgConsumesWholeControlSequenceName() {
        // "\alpha" — a multi-letter control sequence — should be consumed in full (index 6).
        assertEquals( 6, LatexRules.consumeArg( "\\alpha", 0 ) );
    }

    @Test
    void consumeArgConsumesControlSequenceEmbeddedInLargerString() {
        // "\frac" followed by other text; only the letters after '\' belong to the control sequence.
        final String s = "\\frac{a}{b}";
        assertEquals( 5, LatexRules.consumeArg( s, 0 ) );
    }

    // -------------------------------------------------------------------------
    //  consumeArg — control symbol (\%, \,) consumes exactly two characters
    // -------------------------------------------------------------------------

    @Test
    void consumeArgConsumesControlSymbolAsTwoCharacters() {
        // "\%" is a control symbol (non-letter after the backslash) — consumes exactly 2 chars.
        assertEquals( 2, LatexRules.consumeArg( "\\%", 0 ) );
    }

    @Test
    void consumeArgClampsControlSymbolAtStringEnd() {
        // A trailing lone backslash: Math.min(pos + 2, s.length()) clamps to the string length.
        assertEquals( 1, LatexRules.consumeArg( "\\", 0 ) );
    }

    // -------------------------------------------------------------------------
    //  unknownCommand
    // -------------------------------------------------------------------------

    @Test
    void unknownCommandReturnsFalseWhenAllCommandsAreKnown() {
        assertFalse( LatexRules.unknownCommand( "\\frac{a}{b} + \\sqrt{c}" ) );
    }

    @Test
    void unknownCommandReturnsTrueForUnrecognizedCommand() {
        assertTrue( LatexRules.unknownCommand( "\\totallyBogusCommand{x}" ) );
    }

    @Test
    void unknownCommandReturnsFalseForPlainTextWithNoCommands() {
        assertFalse( LatexRules.unknownCommand( "x + y = z" ) );
    }
}
