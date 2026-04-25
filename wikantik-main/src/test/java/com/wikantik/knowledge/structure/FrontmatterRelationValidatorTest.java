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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.RelationType;
import com.wikantik.knowledge.structure.FrontmatterRelationValidator.IssueKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRelationValidatorTest {

    private static final String SRC = "01SOURCEXXXXXXXXXXXXXXXXXX";

    @Test
    void null_field_returns_empty_no_issues() {
        final var r = FrontmatterRelationValidator.validate( SRC, null, t -> true );
        assertTrue( r.valid().isEmpty() );
        assertTrue( r.issues().isEmpty() );
    }

    @Test
    void parses_well_formed_list() {
        final var raw = List.of(
                Map.of( "type", "part-of", "target", "01HUBXXXXXXXXXXXXXXXXXXXXXX" ),
                Map.of( "type", "example-of", "target", "01EXMXXXXXXXXXXXXXXXXXXXXX" )
        );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, t -> true );
        assertEquals( 2, r.valid().size() );
        assertFalse( r.hasIssues() );
        assertEquals( RelationType.PART_OF, r.valid().get( 0 ).type() );
        assertEquals( RelationType.EXAMPLE_OF, r.valid().get( 1 ).type() );
    }

    @Test
    void unknown_type_is_reported_and_dropped() {
        final var raw = List.of( Map.of( "type", "is-a", "target", "01TARGETXXXXXXXXXXXXXXXXXX" ) );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, t -> true );
        assertTrue( r.valid().isEmpty() );
        assertEquals( 1, r.issues().size() );
        assertEquals( IssueKind.UNKNOWN_TYPE, r.issues().get( 0 ).kind() );
    }

    @Test
    void missing_target_is_reported() {
        final var raw = List.of( Map.of( "type", "part-of" ) );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, t -> true );
        assertEquals( 1, r.issues().size() );
        assertEquals( IssueKind.MISSING_TARGET, r.issues().get( 0 ).kind() );
    }

    @Test
    void target_that_does_not_resolve_is_reported() {
        final var known = Set.of( "01HUBXXXXXXXXXXXXXXXXXXXXXX" );
        final var raw = List.of( Map.of( "type", "part-of", "target", "01MISSINGXXXXXXXXXXXXXXXXX" ) );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, known::contains );
        assertEquals( 1, r.issues().size() );
        assertEquals( IssueKind.TARGET_MISSING, r.issues().get( 0 ).kind() );
    }

    @Test
    void self_reference_is_rejected() {
        final var raw = List.of( Map.of( "type", "part-of", "target", SRC ) );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, t -> true );
        assertEquals( IssueKind.SELF_REFERENCE, r.issues().get( 0 ).kind() );
    }

    @Test
    void duplicate_triples_are_deduped() {
        final var raw = List.of(
                Map.of( "type", "part-of", "target", "01TGTXXXXXXXXXXXXXXXXXXXXX" ),
                Map.of( "type", "part-of", "target", "01TGTXXXXXXXXXXXXXXXXXXXXX" )
        );
        final var r = FrontmatterRelationValidator.validate( SRC, raw, t -> true );
        assertEquals( 1, r.valid().size() );
        assertFalse( r.hasIssues() );
    }

    @Test
    void non_list_field_is_reported_as_malformed() {
        final var r = FrontmatterRelationValidator.validate( SRC, "part-of: 01X", t -> true );
        assertEquals( 1, r.issues().size() );
        assertEquals( IssueKind.MALFORMED_ENTRY, r.issues().get( 0 ).kind() );
    }
}
