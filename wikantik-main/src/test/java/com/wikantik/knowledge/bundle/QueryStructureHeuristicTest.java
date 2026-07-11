package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryStructureHeuristicTest {

    @Test void comparativeMarkersFire() {
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does canary deployment differ from blue-green and when should I use each" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does graph RAG differ from standard RAG" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "canary vs blue-green deployment" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "compare BM25 and dense retrieval" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "what is the difference between a hub and an article" ) );
    }

    @Test void conjunctiveTwoPartFires() {
        assertTrue( QueryStructureHeuristic.looksMultiPart( "how does Wikantik hybrid retrieval decide between BM25 and dense results" ) );
        assertTrue( QueryStructureHeuristic.looksMultiPart( "what configuration enables the Knowledge Graph rerank and what is the default" ) );
    }

    @Test void singleIntentDoesNotFire() {
        assertFalse( QueryStructureHeuristic.looksMultiPart( "what embedding model does the retrieval harness use" ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "how do I rebuild the search index" ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "canary deployment" ) );
    }

    @Test void nullAndBlankAreSafe() {
        assertFalse( QueryStructureHeuristic.looksMultiPart( null ) );
        assertFalse( QueryStructureHeuristic.looksMultiPart( "   " ) );
    }
}
