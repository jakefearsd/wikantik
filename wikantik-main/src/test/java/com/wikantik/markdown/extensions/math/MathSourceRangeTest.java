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

class MathSourceRangeTest {

    @Test
    void computesLineAndColumnFromOffsets() {
        final String body = "abc\n$$x$$\nyz";   // line 2 starts at offset 4
        final int start = body.indexOf("$$");     // 4
        final int end = start + 6;                // after closing $$ (offset 10)
        final MathSourceRange r = MathSourceRange.from(body, start, end);
        assertEquals(start, r.startOffset());
        assertEquals(end, r.endOffset());
        assertEquals(2, r.line());
        assertEquals(1, r.column());
        assertEquals(2, r.endLine());
        assertEquals(7, r.endColumn());           // 1 + length("$$x$$")=6 -> col 7
    }
}
