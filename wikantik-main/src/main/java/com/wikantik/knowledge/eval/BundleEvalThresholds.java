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

import java.util.Properties;

/** Per-category + overall context-recall floors (from eval/bundle-corpus/thresholds.properties).
 *  A missing floor reads as 0.0 — fail-open, so an unconfigured category never manufactures a regression. */
public record BundleEvalThresholds( double overall, double similarity, double relational, double boundary ) {

    public static BundleEvalThresholds fromProperties( final Properties p ) {
        return new BundleEvalThresholds(
            d( p, "recall.OVERALL.min" ),
            d( p, "recall.SIMILARITY.min" ),
            d( p, "recall.RELATIONAL.min" ),
            d( p, "recall.BOUNDARY.min" ) );
    }

    private static double d( final Properties p, final String key ) {
        final String raw = p.getProperty( key );
        if ( raw == null || raw.isBlank() ) return 0.0;
        try {
            return Double.parseDouble( raw.trim() );
        } catch ( final NumberFormatException e ) {
            return 0.0;
        }
    }
}
