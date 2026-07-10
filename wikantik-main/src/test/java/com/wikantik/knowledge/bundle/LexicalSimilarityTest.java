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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalSimilarityTest {

    @Test
    void identicalText_cosineIsOne() {
        assertEquals( 1.0, LexicalSimilarity.cosine( "alpha beta gamma", "alpha beta gamma" ), 1e-9 );
    }

    @Test
    void disjointText_cosineIsZero() {
        assertEquals( 0.0, LexicalSimilarity.cosine( "alpha beta", "gamma delta" ), 1e-9 );
    }

    @Test
    void emptyOrNull_cosineIsZero() {
        assertEquals( 0.0, LexicalSimilarity.cosine( "", "alpha" ), 1e-9 );
        assertEquals( 0.0, LexicalSimilarity.cosine( null, "alpha" ), 1e-9 );
    }

    @Test
    void partialOverlap_isBetweenZeroAndOne() {
        final double c = LexicalSimilarity.cosine( "alpha beta gamma", "alpha beta delta" );
        assertTrue( c > 0.0 && c < 1.0, "partial overlap must be strictly between 0 and 1, was " + c );
    }

    @Test
    void caseAndPunctuationInsensitive() {
        assertEquals( 1.0, LexicalSimilarity.cosine( "Alpha, Beta!", "alpha beta" ), 1e-9 );
    }
}
