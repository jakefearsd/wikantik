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
import static org.junit.jupiter.api.Assertions.*;

class SymbolDetectorTest {
    @Test
    void detectsCamelCase() { assertTrue(SymbolDetector.hasCodeSymbol("what does HybridChunkSectionSource do")); }
    @Test
    void detectsSnakeCase() { assertTrue(SymbolDetector.hasCodeSymbol("the kg_content_chunks table")); }
    @Test
    void detectsDottedKey() { assertTrue(SymbolDetector.hasCodeSymbol("wikantik.search.graph.boost setting")); }
    @Test
    void naturalLanguageIsFalse() {
        assertFalse(SymbolDetector.hasCodeSymbol("how do I deploy the wiki locally"));
        assertFalse(SymbolDetector.hasCodeSymbol("the actor system in akka"));
        assertFalse(SymbolDetector.hasCodeSymbol(""));
        assertFalse(SymbolDetector.hasCodeSymbol(null));
    }
    @Test
    void singleCapitalizedWordIsNotASymbol() {
        assertFalse(SymbolDetector.hasCodeSymbol("Ollama installation"));
    }
}
