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
}
