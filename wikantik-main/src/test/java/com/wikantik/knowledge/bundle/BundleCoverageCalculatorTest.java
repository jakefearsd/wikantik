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

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleCoverageCalculatorTest {

    private final BundleCoverageCalculator calc = BundleCoverageCalculator.defaults(); // 0.55 / 0.40

    private static List< BundleSection > sections( final int n ) {
        return IntStream.range( 0, n ).mapToObj( i -> new BundleSection(
                "C" + i, "P" + i, List.of( "H" ), "t", 0.9,
                new CitationHandle( "C" + i, 1, List.of( "H" ), "t", "h" ) ) ).toList();
    }

    @Test
    void unavailableCosineIsUnknown() {
        assertEquals( BundleCoverage.UNKNOWN, calc.compute( -1.0, sections( 5 ) ).confidence() );
    }

    @Test
    void zeroSectionsIsWeak() {
        assertEquals( BundleCoverage.WEAK, calc.compute( 0.9, List.of() ).confidence() );
    }

    @Test
    void strongNeedsHighCosineAndThreeSections() {
        assertEquals( BundleCoverage.STRONG, calc.compute( 0.60, sections( 3 ) ).confidence() );
        // high cosine but too few sections → not strong
        assertEquals( BundleCoverage.PARTIAL, calc.compute( 0.60, sections( 2 ) ).confidence() );
    }

    @Test
    void partialBetweenThresholds() {
        assertEquals( BundleCoverage.PARTIAL, calc.compute( 0.45, sections( 5 ) ).confidence() );
    }

    @Test
    void belowPartialIsWeak() {
        assertEquals( BundleCoverage.WEAK, calc.compute( 0.30, sections( 5 ) ).confidence() );
    }

    @Test
    void populatesCountsAndCosine() {
        final BundleCoverage c = calc.compute( 0.72, sections( 4 ) );
        assertEquals( 4, c.sectionCount() );
        assertEquals( 4, c.distinctPageCount() );
        assertEquals( 0.72, c.topSimilarity() );
    }
}
