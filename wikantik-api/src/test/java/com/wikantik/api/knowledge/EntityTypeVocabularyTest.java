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
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypeVocabularyTest {

    @Test
    void canonicalOrAlias_keepsDirectMembersCaseAndSpaceInsensitively() {
        assertEquals( Optional.of( "technology" ), EntityTypeVocabulary.canonicalOrAlias( "Technology" ) );
        assertEquals( Optional.of( "concept" ), EntityTypeVocabulary.canonicalOrAlias( "  CONCEPT " ) );
        assertEquals( Optional.of( "person" ), EntityTypeVocabulary.canonicalOrAlias( "person" ) );
    }

    @Test
    void canonicalOrAlias_resolvesTechnologySynonyms() {
        // The dominant misclassification: software/tooling artifacts the model labels otherwise.
        for ( final String syn : new String[] { "database", "framework", "library", "tool",
                "programming language", "api", "sdk", "service", "module", "component" } ) {
            assertEquals( Optional.of( "technology" ), EntityTypeVocabulary.canonicalOrAlias( syn ),
                    () -> "'" + syn + "' should resolve to technology, not fall through to concept" );
        }
    }

    @Test
    void canonicalOrAlias_resolvesOtherClassSynonyms() {
        assertEquals( Optional.of( "organization" ), EntityTypeVocabulary.canonicalOrAlias( "company" ) );
        assertEquals( Optional.of( "person" ), EntityTypeVocabulary.canonicalOrAlias( "Developer" ) );
        assertEquals( Optional.of( "place" ), EntityTypeVocabulary.canonicalOrAlias( "location" ) );
        assertEquals( Optional.of( "event" ), EntityTypeVocabulary.canonicalOrAlias( "war" ) );
        assertEquals( Optional.of( "concept" ), EntityTypeVocabulary.canonicalOrAlias( "algorithm" ) );
    }

    @Test
    void canonicalOrAlias_returnsEmptyForNullBlankOrUnknown() {
        assertEquals( Optional.empty(), EntityTypeVocabulary.canonicalOrAlias( null ) );
        assertEquals( Optional.empty(), EntityTypeVocabulary.canonicalOrAlias( "   " ) );
        assertEquals( Optional.empty(), EntityTypeVocabulary.canonicalOrAlias( "gadget" ) );
    }

    @Test
    void everyAliasTargetIsACanonicalClass() {
        // Invariant: an alias may never point outside the nine-class vocabulary, or it would write a
        // node_type with no wk: ontology mapping.
        EntityTypeVocabulary.TYPE_ALIASES.forEach( ( alias, target ) ->
                assertTrue( EntityTypeVocabulary.ENTITY_CLASS_SET.contains( target ),
                        () -> "alias '" + alias + "' targets non-canonical class '" + target + "'" ) );
    }

    @Test
    void aliasKeysAreLowercaseAndNotAlreadyCanonical() {
        EntityTypeVocabulary.TYPE_ALIASES.keySet().forEach( key -> {
            assertEquals( key.toLowerCase( java.util.Locale.ROOT ), key, "alias keys must be lowercase" );
            assertTrue( !EntityTypeVocabulary.ENTITY_CLASS_SET.contains( key ),
                    () -> "alias '" + key + "' is already a canonical class — remove the redundant entry" );
        } );
    }
}
