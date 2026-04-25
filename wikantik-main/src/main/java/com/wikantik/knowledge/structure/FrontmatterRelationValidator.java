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

import com.wikantik.api.structure.Relation;
import com.wikantik.api.structure.RelationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parses and validates the {@code relations:} frontmatter field. Phase 2 is
 * warn-only — invalid entries are dropped and surfaced via {@link Result#issues}
 * but the page still saves. Phase 4 will flip this to a hard rejection at
 * save time.
 *
 * <p>The validator is target-aware: callers pass a {@link Predicate} that
 * answers "does this canonical_id resolve?" so unresolvable targets get a
 * dedicated {@link IssueKind#TARGET_MISSING} signal.</p>
 */
public final class FrontmatterRelationValidator {

    public enum IssueKind {
        UNKNOWN_TYPE, MISSING_TARGET, TARGET_MISSING, SELF_REFERENCE, MALFORMED_ENTRY
    }

    public record Issue( IssueKind kind, String detail ) {}

    public record Result( List< Relation > valid, List< Issue > issues ) {
        public Result {
            valid  = valid  == null ? List.of() : List.copyOf( valid );
            issues = issues == null ? List.of() : List.copyOf( issues );
        }
        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private FrontmatterRelationValidator() {}

    /**
     * @param sourceId      canonical_id of the page whose frontmatter is being validated
     * @param relationsField raw value of the {@code relations:} key (List of Map, typically)
     * @param targetExists  predicate: returns true iff the given canonical_id resolves to a known page
     * @return parsed valid relations + issues classifying every entry that didn't survive
     */
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    public static Result validate( final String sourceId,
                                    final Object relationsField,
                                    final Predicate< String > targetExists ) {
        if ( relationsField == null ) {
            return new Result( List.of(), List.of() );
        }
        if ( !( relationsField instanceof List< ? > rawList ) ) {
            return new Result( List.of(),
                    List.of( new Issue( IssueKind.MALFORMED_ENTRY,
                            "relations: must be a list, not " + relationsField.getClass().getSimpleName() ) ) );
        }

        final List< Relation > valid = new ArrayList<>();
        final List< Issue > issues = new ArrayList<>();
        for ( final Object entry : rawList ) {
            if ( !( entry instanceof Map< ?, ? > mapEntry ) ) {
                issues.add( new Issue( IssueKind.MALFORMED_ENTRY,
                        "expected {type, target} map, got: " + entry ) );
                continue;
            }
            final Object typeRaw   = mapEntry.get( "type" );
            final Object targetRaw = mapEntry.get( "target" );

            final var type = RelationType.fromWire( typeRaw );
            if ( type.isEmpty() ) {
                issues.add( new Issue( IssueKind.UNKNOWN_TYPE,
                        "unrecognised relation type: " + typeRaw ) );
                continue;
            }
            if ( targetRaw == null || targetRaw.toString().isBlank() ) {
                issues.add( new Issue( IssueKind.MISSING_TARGET,
                        "relation type=" + type.get().wireName() + " is missing a target" ) );
                continue;
            }
            final String target = targetRaw.toString().trim();
            if ( target.equals( sourceId ) ) {
                issues.add( new Issue( IssueKind.SELF_REFERENCE,
                        "relation target equals source: " + sourceId ) );
                continue;
            }
            if ( !targetExists.test( target ) ) {
                issues.add( new Issue( IssueKind.TARGET_MISSING,
                        "target canonical_id does not resolve: " + target
                          + " (type=" + type.get().wireName() + ")" ) );
                continue;
            }
            try {
                valid.add( new Relation( sourceId, target, type.get() ) );
            } catch ( final IllegalArgumentException ex ) {
                issues.add( new Issue( IssueKind.MALFORMED_ENTRY, ex.getMessage() ) );
            }
        }

        // Defensive: dedupe (source, target, type) triples.
        final Set< String > seen = new java.util.HashSet<>();
        final List< Relation > deduped = new ArrayList<>( valid.size() );
        for ( final Relation r : valid ) {
            final String key = r.sourceId() + "|" + r.targetId() + "|" + r.type();
            if ( seen.add( key ) ) {
                deduped.add( r );
            }
        }
        return new Result( deduped, issues );
    }
}
