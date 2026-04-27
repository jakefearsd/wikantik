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
package com.wikantik.api.kgpolicy;

import java.time.Instant;
import java.util.Optional;

/** What {@code KgInclusionPolicy.explain(canonicalId)} returns. */
public record PolicyExplanation(
        String canonicalId,
        String pageName,
        String cluster,
        boolean systemPage,
        Optional< Boolean > frontmatterOverride,    // empty | true | false
        Optional< ClusterAction > clusterPolicy,    // empty if cluster has no row
        ClusterAction effectiveAction,
        Optional< ExclusionReason > exclusionReason,
        Optional< Instant > lastExtractedAt,
        int kgEntityCount,
        int kgEdgeCount
) {}
