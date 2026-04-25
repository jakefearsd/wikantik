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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.Confidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure-function rule engine that decides a page's {@link Confidence} from the
 * verification facts in frontmatter plus the trusted-authors registry.
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>Explicit override authored in frontmatter (e.g. {@code confidence: stale})</li>
 *   <li>{@link Confidence#STALE} when {@code verifiedAt} is null or older than {@code staleDays}</li>
 *   <li>{@link Confidence#AUTHORITATIVE} when the verifier appears in {@code isTrustedAuthor}</li>
 *   <li>{@link Confidence#PROVISIONAL} otherwise</li>
 * </ol>
 *
 * <p>This is the single source of truth for confidence — every other component
 * (rebuild, REST, the Phase 2 {@code /for-agent} projection) consumes the same
 * answer.</p>
 */
public final class ConfidenceComputer {

    /** Default stale window: 90 days. Override via {@code wikantik.verification.stale_days}. */
    public static final int DEFAULT_STALE_DAYS = 90;

    private final Predicate< String > isTrustedAuthor;
    private final int staleDays;

    public ConfidenceComputer( final Predicate< String > isTrustedAuthor, final int staleDays ) {
        this.isTrustedAuthor = isTrustedAuthor == null ? name -> false : isTrustedAuthor;
        this.staleDays = staleDays > 0 ? staleDays : DEFAULT_STALE_DAYS;
    }

    public ConfidenceComputer( final Predicate< String > isTrustedAuthor ) {
        this( isTrustedAuthor, DEFAULT_STALE_DAYS );
    }

    /**
     * Compute the page's effective confidence.
     *
     * @param verifiedAt        last human-verification timestamp (null = never verified)
     * @param verifiedBy        verifying author's login_name (null = unknown)
     * @param explicitOverride  optional frontmatter override (e.g. author flagged a known-stale page)
     * @param now               current instant (injected for testability)
     * @return the effective confidence value
     */
    public Confidence compute( final Instant verifiedAt,
                                final String verifiedBy,
                                final Optional< Confidence > explicitOverride,
                                final Instant now ) {
        if ( explicitOverride != null && explicitOverride.isPresent() ) {
            return explicitOverride.get();
        }
        if ( verifiedAt == null ) {
            return Confidence.STALE;
        }
        if ( now != null && Duration.between( verifiedAt, now ).toDays() > staleDays ) {
            return Confidence.STALE;
        }
        if ( verifiedBy != null && !verifiedBy.isBlank() && isTrustedAuthor.test( verifiedBy ) ) {
            return Confidence.AUTHORITATIVE;
        }
        return Confidence.PROVISIONAL;
    }
}
