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
package com.wikantik.api.frontmatter;

/**
 * Thrown by {@link FrontmatterParser#parseStrict(String)} when a page declares
 * a {@code ---}-delimited YAML block but the YAML inside fails to parse. Carries
 * the SnakeYAML message plus best-effort line/column info so the MCP write tools
 * and the {@code FrontmatterValidationPageFilter} can return actionable errors
 * to the caller (e.g. "wrap values containing ':' in double quotes").
 *
 * <p>{@link FrontmatterParser#parse(String)} keeps degrading gracefully (returns
 * empty metadata + a {@code WARN} log) — that path stays for read-side callers
 * who must keep rendering pages even when the metadata is broken. Strict parsing
 * is opt-in and used only on the write path.</p>
 */
public class FrontmatterParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 1-based line within the YAML block where the parser hit the error. {@code -1} if unknown. */
    private final int line;
    /** 1-based column within the YAML block. {@code -1} if unknown. */
    private final int column;

    public FrontmatterParseException( final String message, final int line, final int column ) {
        super( message );
        this.line = line;
        this.column = column;
    }

    public FrontmatterParseException( final String message, final int line, final int column, final Throwable cause ) {
        super( message, cause );
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
