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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.RetrievalMode;

import java.util.Map;
import java.util.Optional;

/**
 * Test-only factory wiring {@link DefaultBundleAssemblyService} via its real canonical
 * (decomposition-aware) constructor, with an identity reranker, disabled knee, trivial
 * canonicalId/version resolvers, and default coverage thresholds — so decomposition tests
 * only vary the {@link SectionCandidateSource}, {@link QueryPlanner}, and the on/off flag.
 */
final class DefaultBundleAssemblyServiceTestSupport {

    private DefaultBundleAssemblyServiceTestSupport() {}

    static DefaultBundleAssemblyService withDecomposition( final SectionCandidateSource src,
                                                            final QueryPlanner planner,
                                                            final boolean decompositionEnabled ) {
        return new DefaultBundleAssemblyService(
            Map.of( RetrievalMode.HYBRID, src ), RetrievalMode.HYBRID,
            ( q, sections ) -> sections,             // identity reranker
            slug -> Optional.of( slug ),              // canonicalIdOf
            slug -> 1,                                 // versionOf
            12,                                         // maxSections
            BundleCoverageCalculator.defaults(),
            KneeCutoff.disabled(),
            planner,
            new SubQueryFusion( 60 ),
            decompositionEnabled );
    }
}
