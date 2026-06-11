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

/** Half-open source span: [startOffset, endOffset) with 1-based line/column endpoints. */
public record MathSourceRange(int startOffset, int endOffset,
                              int line, int column, int endLine, int endColumn) {

    /** Computes a range from absolute offsets into {@code body}. {@code end} is exclusive. */
    public static MathSourceRange from(final String body, final int start, final int end) {
        int line = 1, col = 1;
        for (int i = 0; i < start && i < body.length(); i++) {
            if (body.charAt(i) == '\n') { line++; col = 1; } else { col++; }
        }
        // endLine/endColumn: walk from start toward end, counting the column of each char.
        // A '\n' within the span moves to the next line; the end position is one past the
        // last character (exclusive), so endColumn advances by 1 for every character including '\n'.
        int endLine = line, endCol = col;
        for (int i = start; i < end && i < body.length(); i++) {
            if (body.charAt(i) == '\n') { endLine++; endCol = 1; } else { endCol++; }
        }
        // If the span ended exactly at a '\n' boundary (body[end-1]=='\n'), the loop above
        // moved endLine forward.  For exclusive-end semantics the caller usually wants the
        // column just past the last non-newline character on the same line, so we undo the
        // newline transition: count the '\n' as a regular column advance instead.
        if (end > start && end <= body.length() && body.charAt(end - 1) == '\n') {
            // Recompute endLine/endCol treating the trailing '\n' as a plain character.
            endLine = line;
            endCol = col;
            for (int i = start; i < end - 1 && i < body.length(); i++) {
                if (body.charAt(i) == '\n') { endLine++; endCol = 1; } else { endCol++; }
            }
            endCol++; // count the '\n' itself as occupying one column position
        }
        return new MathSourceRange(start, end, line, col, endLine, endCol);
    }
}
