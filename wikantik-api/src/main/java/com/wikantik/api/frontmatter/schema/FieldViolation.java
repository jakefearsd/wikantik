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
package com.wikantik.api.frontmatter.schema;

/**
 * A single, field-addressable validation result. The same shape is returned by the REST 422 error
 * body, the 200 warnings array, the {@code /api/frontmatter/validate} dry-run, and the page-scoped
 * KG endpoints — so the form and the MCP text path surface identical, actionable messages.
 *
 * @param field     the frontmatter key, dotted for nesting ({@code runbook.steps}); the synthetic
 *                  {@code __yaml__} marks a whole-block parse error; {@code edge} marks a KG edge refusal.
 * @param severity  {@link Severity#ERROR} (blocks the write) or {@link Severity#WARNING} (advisory).
 * @param code      a stable machine code, e.g. {@code status.noncanonical}, {@code cluster.slug.malformed}.
 * @param message   a human/agent-readable explanation, stern and actionable where relevant.
 * @param suggestion an optional proposed replacement value (e.g. a canonical enum value); {@code null}
 *                   when no suggestion is available.
 */
public record FieldViolation(
        String field,
        Severity severity,
        String code,
        String message,
        String suggestion
) {
    /** Convenience for the common no-suggestion case. */
    public static FieldViolation of( final String field, final Severity severity,
                                     final String code, final String message ) {
        return new FieldViolation( field, severity, code, message, null );
    }
}
