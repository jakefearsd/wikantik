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
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatexSyntaxLinterTest {

    private final LatexSyntaxLinter linter = new LatexSyntaxLinter();

    private boolean warns(final String latex, final String code) {
        return linter.lint(latex).stream().anyMatch(v -> v.code().equals(code));
    }

    @Test
    void cleanLatexHasNoWarnings() {
        assertTrue(linter.lint("\\frac{a}{b} + \\sqrt{x^2}").isEmpty());
        assertTrue(linter.lint("\\left( \\frac{a}{b} \\right)").isEmpty());
        assertTrue(linter.lint("\\begin{matrix} a & b \\end{matrix}").isEmpty());
    }

    @Test
    void unbalancedBraces() { assertTrue(warns("\\frac{a}{b", "math.syntax.unbalancedBraces")); }

    @Test
    void leftWithoutRight() { assertTrue(warns("\\left( x", "math.syntax.leftRight")); }

    @Test
    void beginWithoutEnd() { assertTrue(warns("\\begin{matrix} a", "math.syntax.beginEnd")); }

    @Test
    void mismatchedBeginEnd() { assertTrue(warns("\\begin{matrix} a \\end{pmatrix}", "math.syntax.beginEnd")); }

    @Test
    void unknownCommand() { assertTrue(warns("\\frooble{x}", "math.syntax.unknownCommand")); }

    @Test
    void knownCommandsPass() { assertFalse(warns("\\alpha \\cdot \\beta", "math.syntax.unknownCommand")); }
}
