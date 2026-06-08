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
package com.wikantik.frontmatter.schema;

import com.wikantik.api.frontmatter.schema.Severity;

import java.util.function.Predicate;

/**
 * Side inputs the validator needs without dragging in heavy services. Supplied by the save filter
 * (engine-backed) and by tests (in-memory).
 *
 * @param pageResolves              {@code true} if a page name/title/canonical_id resolves to a page.
 * @param isTrustedAuthor           {@code true} if a {@code verified_by} value is a trusted author.
 * @param nonCanonicalEnumSeverity  severity for non-canonical values of curated-open enums
 *                                  ({@code type}/{@code status}); {@link Severity#WARNING} until the
 *                                  corpus is normalized, then escalatable to {@link Severity#ERROR}.
 */
public record ValidationCtx(
        Predicate< String > pageResolves,
        Predicate< String > isTrustedAuthor,
        Severity nonCanonicalEnumSeverity
) {
    /** Permissive context for unit tests / no-service callers: everything resolves, enums warn. */
    public static ValidationCtx lenient() {
        return new ValidationCtx( p -> true, a -> true, Severity.WARNING );
    }
}
