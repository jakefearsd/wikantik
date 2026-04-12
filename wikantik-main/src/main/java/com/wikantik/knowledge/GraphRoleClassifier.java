package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;

public final class GraphRoleClassifier {

    private GraphRoleClassifier() {}

    public static String classify( final KgNode node, final int degreeIn, final int degreeOut,
                                    final int hubThreshold, final boolean restricted ) {
        if ( restricted ) return "restricted";
        if ( node.sourcePage() == null ) return "stub";
        if ( degreeIn + degreeOut == 0 ) return "orphan";
        if ( degreeIn + degreeOut >= hubThreshold ) return "hub";
        return "normal";
    }
}
