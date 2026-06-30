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
package com.wikantik.api.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleCoverageTest {

    private static BundleSection sec( final String canonical, final String slug ) {
        return new BundleSection( canonical, slug, List.of( "H" ), "t", 0.9,
                new CitationHandle( canonical == null ? "default" : canonical, 1, List.of( "H" ), "t", "h" ) );
    }

    @Test
    void emptyIsUnknownAndZero() {
        final BundleCoverage c = BundleCoverage.empty();
        assertEquals( 0, c.sectionCount() );
        assertEquals( 0, c.distinctPageCount() );
        assertEquals( -1.0, c.topSimilarity() );
        assertEquals( BundleCoverage.UNKNOWN, c.confidence() );
    }

    @Test
    void distinctPagesCountsUniqueCanonicalIds() {
        assertEquals( 2, BundleCoverage.distinctPages(
                List.of( sec( "A", "Pa" ), sec( "A", "Pa2" ), sec( "B", "Pb" ) ) ) );
    }

    @Test
    void distinctPagesIgnoresNullCanonicalIds() {
        assertEquals( 1, BundleCoverage.distinctPages(
                List.of( sec( "A", "Pa" ), sec( null, "Pn" ) ) ) );
    }

    @Test
    void recountFixesCountsButPreservesCosineAndConfidence() {
        final BundleCoverage original = new BundleCoverage( 12, 5, 0.8, BundleCoverage.STRONG );
        final BundleCoverage r = BundleCoverage.recount( original, List.of( sec( "A", "Pa" ) ) );
        assertEquals( 1, r.sectionCount() );
        assertEquals( 1, r.distinctPageCount() );
        assertEquals( 0.8, r.topSimilarity() );
        assertEquals( BundleCoverage.STRONG, r.confidence() );
    }
}
