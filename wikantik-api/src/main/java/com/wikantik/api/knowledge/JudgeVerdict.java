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
package com.wikantik.api.knowledge;

/**
 * Result of a single judge LLM call. Verdicts are one of approved | rejected | abstain.
 * Confidence is in [0,1]; rationale is the judge's free-text explanation.
 */
public record JudgeVerdict(
    String verdict,
    double confidence,
    String rationale,
    String model
) {
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String ABSTAIN  = "abstain";

    /** Prefix applied by judge implementations to rationales that signal a
     *  transient infrastructure failure (HTTP timeout, connection refused,
     *  malformed response, etc.) rather than a real model abstention. The
     *  runner uses this to retry on the next cron pass without polluting
     *  the proposal's review history or counting against max_attempts. */
    public static final String RATIONALE_UNAVAILABLE_PREFIX = "judge_unavailable:";

    /** True when this verdict represents a transient infrastructure failure
     *  rather than a real model output. Such verdicts must NOT be persisted
     *  as machine reviews — let the next cron pass retry. */
    public boolean isTransientUnavailable() {
        return ABSTAIN.equals( verdict )
            && rationale != null
            && rationale.startsWith( RATIONALE_UNAVAILABLE_PREFIX );
    }

    public JudgeVerdict {
        if ( verdict == null
                || !( APPROVED.equals( verdict ) || REJECTED.equals( verdict ) || ABSTAIN.equals( verdict ) ) ) {
            throw new IllegalArgumentException( "verdict must be approved|rejected|abstain, got " + verdict );
        }
        if ( confidence < 0.0 || confidence > 1.0 ) {
            throw new IllegalArgumentException( "confidence must be in [0,1], got " + confidence );
        }
        if ( model == null || model.isBlank() ) {
            throw new IllegalArgumentException( "model must not be blank" );
        }
    }
}
