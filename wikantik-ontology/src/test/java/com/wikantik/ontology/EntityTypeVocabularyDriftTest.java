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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wikantik.api.knowledge.EntityTypeVocabulary;
import org.junit.jupiter.api.Test;

/**
 * Drift guard: every entry in the canonical {@link EntityTypeVocabulary} must have an
 * explicit class mapping in {@link NodeTypeMapping} (mapping to its own PascalCase wk:
 * class), not silently fall through to the {@code Concept} default. Adding a 10th entity
 * type to the vocabulary without a matching NodeTypeMapping entry fails this test.
 */
class EntityTypeVocabularyDriftTest {

    @Test
    void everyEntityTypeHasAnExplicitClassMapping() {
        for ( final String t : EntityTypeVocabulary.ENTITY_CLASSES ) {
            final String expected = Character.toUpperCase( t.charAt( 0 ) ) + t.substring( 1 );
            assertEquals( expected, NodeTypeMapping.classLocalName( t ),
                    "entity type '" + t + "' must map to wk:" + expected
                            + " — add it to NodeTypeMapping.MAP (it currently falls back to "
                            + NodeTypeMapping.DEFAULT_CLASS + ")" );
        }
    }

    @Test
    void defaultEntityClassIsItselfInTheVocabulary() {
        assertEquals( true, EntityTypeVocabulary.ENTITY_CLASS_SET.contains(
                EntityTypeVocabulary.DEFAULT_ENTITY_CLASS ),
                "the fallback entity class must be a member of the vocabulary" );
    }
}
