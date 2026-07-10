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
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BundleEvalRunTest {

    private static BundleEvalReport report( final double overall, final double sim, final double rel, final double bnd ) {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, sim );
        recall.put( BundleCategory.RELATIONAL, rel );
        recall.put( BundleCategory.BOUNDARY, bnd );
        final Map< BundleCategory, Double > prec = new EnumMap<>( BundleCategory.class );
        for ( final BundleCategory c : BundleCategory.values() ) prec.put( c, 0.0 );
        return new BundleEvalReport( overall, 0.0, recall, prec, 42 );
    }

    @Test
    void from_flattensCategoriesAndCarriesFields() {
        final BundleEvalRun run = BundleEvalRun.from( report( 0.74, 0.70, 0.80, 0.72 ), "2.3.6-SNAPSHOT", false );
        assertEquals( "2.3.6-SNAPSHOT", run.configId() );
        assertEquals( 0.74, run.overallRecall(), 1e-9 );
        assertEquals( 0.70, run.recallSimilarity(), 1e-9 );
        assertEquals( 0.80, run.recallRelational(), 1e-9 );
        assertEquals( 0.72, run.recallBoundary(), 1e-9 );
        assertEquals( 42, run.questionsScored() );
        assertFalse( run.regression() );
    }

    @Test
    void from_missingCategory_defaultsToZero() {
        final Map< BundleCategory, Double > recall = new EnumMap<>( BundleCategory.class );
        recall.put( BundleCategory.SIMILARITY, 0.5 );  // RELATIONAL, BOUNDARY absent
        final Map< BundleCategory, Double > prec = new EnumMap<>( BundleCategory.class );
        final BundleEvalReport r = new BundleEvalReport( 0.5, 0.0, recall, prec, 1 );
        final BundleEvalRun run = BundleEvalRun.from( r, "x", true );
        assertEquals( 0.0, run.recallRelational(), 1e-9 );
        assertEquals( 0.0, run.recallBoundary(), 1e-9 );
    }
}
