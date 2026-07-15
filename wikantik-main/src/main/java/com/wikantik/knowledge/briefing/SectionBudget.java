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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable token-budget ledger for a single briefing assembly: the sections kept from the
 * prompt-driven retrieval pass, the running token spend against {@link #budget}, and the
 * {@link BundleCoverage} recomputed over the currently-kept sections.
 *
 * <p>Shared, by reference, between {@code DefaultBriefingAssemblyService.Assembly} (which fills
 * it from the retrieval pass) and {@link ItemAssembler} (whose pin-supersedes-its-own-sections
 * rule removes sections and recounts coverage) — extracted purely to keep those two concerns
 * out of one class (God Class burn-down); the fields and their invariants are unchanged from the
 * pre-extraction {@code Assembly} class.</p>
 */
final class SectionBudget {

    final int budget;
    int used;
    final List< BundleSection > sections = new ArrayList<>();
    BundleCoverage bundleCoverage;             // original retrieval coverage, for recount
    BundleCoverage coverage = BundleCoverage.empty();

    SectionBudget( final int budget ) {
        this.budget = budget;
    }
}
