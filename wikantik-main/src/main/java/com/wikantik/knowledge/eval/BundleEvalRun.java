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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;

/** One persisted scheduled-eval run: overall + per-category recall@12, plus the regression verdict.
 *  {@code run_at} is stamped by the DB default, so it is not a field here. */
public record BundleEvalRun(
    String configId,
    double overallRecall,
    double overallPrecision,
    double recallSimilarity,
    double recallRelational,
    double recallBoundary,
    int questionsScored,
    boolean regression
) {
    /** Flattens a {@link BundleEvalReport}'s per-category recall map (missing category → 0.0). */
    public static BundleEvalRun from( final BundleEvalReport report, final String configId, final boolean regression ) {
        return new BundleEvalRun(
            configId,
            report.overallRecall(),
            report.overallPrecisionAtK(),
            report.recallByCategory().getOrDefault( BundleCategory.SIMILARITY, 0.0 ),
            report.recallByCategory().getOrDefault( BundleCategory.RELATIONAL, 0.0 ),
            report.recallByCategory().getOrDefault( BundleCategory.BOUNDARY, 0.0 ),
            report.questionsScored(),
            regression );
    }
}
