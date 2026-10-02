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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRegionsTest {

    @Test
    void masksInlineCodeSpan() {
        final String body = "use `$$x$$` here";
        final CodeRegions cr = CodeRegions.scan(body);
        assertTrue(cr.isMasked(body.indexOf("$$x$$")), "inside backticks must be masked");
        assertFalse(cr.isMasked(0), "prose before the code span is not masked");
    }

    @Test
    void masksFencedCodeButNotMathFence() {
        final String fenced = "```java\n$$x$$\n```";
        assertTrue(CodeRegions.scan(fenced).isMasked(fenced.indexOf("$$x$$")),
                   "java fence content is masked");

        final String math = "```math\n\\frac{a}{b}\n```";
        assertFalse(CodeRegions.scan(math).isMasked(math.indexOf("\\frac")),
                    "a ```math fence is NOT code — it is math");
    }

    /**
     * A 256 KB page (wikantik.api.maxPageBytes) of unmatched backtick runs of distinct lengths: each opener used to
     * rescan the rest of the line, O(n^1.5) — about 100 ms per scan. Twenty scans must fit well inside a second.
     */
    @Test
    void inlineCodeScanIsLinearOnUnmatchedRunStaircase() {
        final StringBuilder sb = new StringBuilder();
        for (int k = 1; sb.length() + k + 1 < 262_144; k++) {
            sb.append("`".repeat(k)).append('a');
        }
        final String body = sb.toString();
        final CodeRegions last = assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
            CodeRegions r = null;
            for (int i = 0; i < 20; i++) {
                r = CodeRegions.scan(body);
            }
            return r;
        });
        assertFalse(last.isMasked(body.length() - 1));
    }

    @Test
    void inlineCodePairsTheNextRunOfEqualLengthSkippingEnclosedRuns() {
        final String md = "a ``x ` y`` b `z` c ``` d `e";
        final CodeRegions r = CodeRegions.scan(md);
        assertTrue(r.isMasked(md.indexOf('x')) && r.isMasked(md.indexOf('y')));
        assertFalse(r.isMasked(md.indexOf('b')));
        assertTrue(r.isMasked(md.indexOf('z')));
        assertFalse(r.isMasked(md.indexOf('d')));
        assertFalse(r.isMasked(md.indexOf('e')));
    }
}
