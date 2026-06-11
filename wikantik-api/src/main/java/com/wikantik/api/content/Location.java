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
package com.wikantik.api.content;

/**
 * Body-relative source location for a {@link ContentViolation}. All coordinates are
 * relative to the page <em>body</em> (after the closing {@code ---} of the YAML
 * frontmatter block, or the full text for pages without frontmatter).
 *
 * @param line        1-based start line in the body.
 * @param column      1-based start column in the body.
 * @param endLine     1-based end line in the body (inclusive, last char of span).
 * @param endColumn   1-based end column in the body (exclusive, one past last char).
 * @param startOffset 0-based character offset of span start in the body.
 * @param endOffset   0-based exclusive character offset of span end in the body.
 * @param excerpt     A single-line window (~100 chars) of the body line containing the
 *                    span start. Truncated with leading/trailing {@code …} when cut.
 * @param caret       A string of spaces followed by one or more {@code ^} characters
 *                    aligned under the span start in {@code excerpt}; usable as a
 *                    compiler-style pointer in plain-text agent output.
 */
public record Location(
        int line,
        int column,
        int endLine,
        int endColumn,
        int startOffset,
        int endOffset,
        String excerpt,
        String caret
) {}
