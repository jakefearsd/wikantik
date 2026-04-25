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
package com.wikantik.api.structure;

import java.time.Instant;

/**
 * Per-page verification metadata as carried in frontmatter and mirrored to
 * {@code page_verification}. {@link #verifiedAt} is when (if ever) a human
 * verified the page; {@link #verifiedBy} is the verifier's login_name;
 * {@link #confidence} is the rule-engine output (or an explicit override);
 * {@link #audience} controls whether the page surfaces to humans, agents, or
 * both.
 *
 * <p>{@link #unverified()} is the well-known "default" instance for fresh
 * pages — provisional confidence, both audiences, no verifier, no timestamp.</p>
 */
public record Verification(
        Instant verifiedAt,
        String verifiedBy,
        Confidence confidence,
        Audience audience
) {
    public Verification {
        if ( confidence == null ) {
            confidence = Confidence.PROVISIONAL;
        }
        if ( audience == null ) {
            audience = Audience.HUMANS_AND_AGENTS;
        }
        if ( verifiedBy != null && verifiedBy.isBlank() ) {
            verifiedBy = null;
        }
    }

    public static Verification unverified() {
        return new Verification( null, null, Confidence.PROVISIONAL, Audience.HUMANS_AND_AGENTS );
    }
}
