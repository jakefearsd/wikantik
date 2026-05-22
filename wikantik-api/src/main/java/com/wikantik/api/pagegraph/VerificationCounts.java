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
package com.wikantik.api.pagegraph;

/**
 * Aggregate verification-confidence mix across all pages in the structural
 * index. Produced by {@link StructuralIndexService#verificationCounts()} and
 * consumed by admin triage surfaces (the {@code /admin/verification} endpoint
 * and the admin overview dashboard) so the tally loop lives in one place.
 *
 * <p>The {@code authoritative}, {@code provisional}, and {@code stale} tallies
 * count <em>every</em> page — pages with no verification row are folded in as
 * {@link Verification#unverified()} (PROVISIONAL) — so those three sum to the
 * total page count. {@code noVerification} separately counts the pages that had
 * no verification entry at all (i.e. {@link StructuralIndexService#verificationOf}
 * returned empty), for surfaces that want to highlight unverified pages.</p>
 */
public record VerificationCounts(
        int authoritative,
        int provisional,
        int stale,
        int noVerification
) {
}
