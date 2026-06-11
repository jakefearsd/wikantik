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

import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathStructureValidatorTest {

    private final MathStructureValidator validator = new MathStructureValidator();

    private boolean hasError(final List<MathViolation> v, final String code) {
        return v.stream().anyMatch(x -> x.severity() == Severity.ERROR && x.code().equals(code));
    }

    @Test
    void blocksInlineGluedDisplayWithCommand() {
        // The FastenerEngineering bug.
        final String body = "friction:$$\\text{T} = \\text{F}_p \\left( x \\right)$$Where";
        assertTrue(hasError(validator.validate(body), "math.display.notIsolated"));
    }

    @Test
    void blocksUnterminatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\nno closer here";
        assertTrue(hasError(validator.validate(body), "math.display.unterminated"));
    }

    @Test
    void allowsLineIsolatedDisplay() {
        final String body = "intro\n$$\n\\frac{a}{b}\n$$\noutro";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void allowsCurrencyProse() {
        final String body = "It costs $5 and $10 to ship, total $15.";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void ignoresDisplayInsideCodeFence() {
        final String body = "```\nfriction:$$\\text{T}=x$$Where\n```";
        assertEquals(List.of(), validator.validate(body));
    }

    @Test
    void warnsOnEmptyDisplay() {
        final String body = "a\n$$\n$$\nb";
        final List<MathViolation> v = validator.validate(body);
        assertTrue(v.stream().anyMatch(x -> x.severity() == Severity.WARNING
                && x.code().equals("math.display.empty")));
    }
}
