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

/** Pure regression verdict: a run regresses when overall recall, or any per-category recall,
 *  is STRICTLY below its threshold floor. The detail string names every breached floor. */
public final class BundleEvalRegressionCheck {

    private BundleEvalRegressionCheck() {}

    public record RegressionResult( boolean regression, String detail ) {}

    public static RegressionResult evaluate( final BundleEvalReport report, final BundleEvalThresholds t ) {
        final StringBuilder breaches = new StringBuilder();
        check( breaches, "OVERALL", report.overallRecall(), t.overall() );
        check( breaches, "SIMILARITY", recall( report, BundleCategory.SIMILARITY ), t.similarity() );
        check( breaches, "RELATIONAL", recall( report, BundleCategory.RELATIONAL ), t.relational() );
        check( breaches, "BOUNDARY", recall( report, BundleCategory.BOUNDARY ), t.boundary() );
        return breaches.isEmpty()
            ? new RegressionResult( false, "all recall floors met" )
            : new RegressionResult( true, "recall below floor: " + breaches );
    }

    private static void check( final StringBuilder sb, final String name, final double value, final double floor ) {
        if ( value < floor ) {
            if ( sb.length() > 0 ) sb.append( "; " );
            sb.append( String.format( "%s %.3f < %.3f", name, value, floor ) );
        }
    }

    private static double recall( final BundleEvalReport report, final BundleCategory c ) {
        return report.recallByCategory().getOrDefault( c, 0.0 );
    }
}
