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
