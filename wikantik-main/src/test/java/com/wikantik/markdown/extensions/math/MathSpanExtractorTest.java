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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathSpanExtractorTest {

    private final MathSpanExtractor extractor = new MathSpanExtractor();

    @Test
    void extractsLineIsolatedDisplayBlock() {
        final String body = "intro\n$$\n\\frac{a}{b}\n$$\noutro";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size());
        assertEquals(MathSpan.Kind.DISPLAY_DOLLAR, spans.get(0).kind());
        assertEquals("\\frac{a}{b}", spans.get(0).content());
    }

    @Test
    void extractsMathFence() {
        final String body = "```math\nx^2\n```";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size());
        assertEquals(MathSpan.Kind.MATH_FENCE, spans.get(0).kind());
        assertEquals("x^2", spans.get(0).content());
    }

    @Test
    void extractsInlineButSkipsCodeAndCurrency() {
        final String body = "value $x+1$ costs `$5` not $5 dollars";
        final List<MathSpan> spans = extractor.extract(body);
        assertEquals(1, spans.size(), "only the real inline span; backtick $5 masked; bare $ is not a span");
        assertEquals(MathSpan.Kind.INLINE_DOLLAR, spans.get(0).kind());
        assertEquals("x+1", spans.get(0).content());
    }

    @Test
    void doesNotExtractInlineDisplayGlue() {
        // $$ glued inline is NOT a valid span (it is a structure error, handled elsewhere)
        final String body = "text:$$\\frac{a}{b}$$more";
        final List<MathSpan> spans = extractor.extract(body);
        assertTrue(spans.isEmpty(), "inline-glued $$ is not a recognised span");
    }
}
