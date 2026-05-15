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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the closed relationship-type vocabulary enforced by the
 * {@code kg_edges_relationship_type_check} CHECK constraint (V027, extended in V030
 * with {@code generalizes}).
 *
 * <p>Consumers: extraction prompt builders (chunk + page), the schema discovery
 * service (admin UI dropdowns), and any future validator. Editing this list requires
 * editing the DB CHECK constraint in a new migration and re-running
 * {@code bin/db/normalize-relationship-types.sql} on existing data.</p>
 *
 * <p>Each entry carries a short prompt-facing description so all three callers emit
 * identical wording — this is what avoids the silent drift previously observed
 * between {@code ExtractionPromptBuilder} and {@code PageExtractionPromptBuilder}.</p>
 */
public final class RelationshipTypeVocabulary {

    private RelationshipTypeVocabulary() {}

    /**
     * Closed vocabulary in canonical order. Direction is always source → target.
     */
    public static final List< String > CLOSED_VOCAB = List.of(
            "related_to", "part_of", "contains", "is_a", "instance_of",
            "generalizes", "requires", "enables", "uses", "produces",
            "replaces", "precedes", "extends", "implements", "alternative_to",
            "contrasts_with", "compatible_with", "mitigates", "defines",
            "applies_to", "located_in"
    );

    private static final Set< String > CLOSED_VOCAB_SET = Set.copyOf( CLOSED_VOCAB );

    /** Whether the candidate is in the closed vocabulary. {@code null} returns {@code false}. */
    public static boolean isValid( final String candidate ) {
        return candidate != null && CLOSED_VOCAB_SET.contains( candidate );
    }

    /**
     * Returns up to {@code limit} closest matches to {@code candidate} from the closed vocabulary,
     * ranked by ascending Levenshtein distance. Used to render "did you mean…" hints in the
     * error message when the caller submits an unknown relationship type — keeps agents from
     * having to read the full 21-entry vocabulary on every typo.
     */
    public static List< String > closestMatches( final String candidate, final int limit ) {
        if ( candidate == null || candidate.isBlank() ) return List.of();
        final String lower = candidate.toLowerCase();
        record Scored( String name, int distance ) {}
        final List< Scored > scored = new ArrayList<>( CLOSED_VOCAB.size() );
        for ( final String v : CLOSED_VOCAB ) {
            scored.add( new Scored( v, levenshtein( lower, v ) ) );
        }
        scored.sort( ( a, b ) -> Integer.compare( a.distance, b.distance ) );
        final List< String > out = new ArrayList<>( limit );
        for ( int i = 0; i < scored.size() && out.size() < limit; i++ ) {
            out.add( scored.get( i ).name );
        }
        return out;
    }

    private static int levenshtein( final String a, final String b ) {
        final int la = a.length(), lb = b.length();
        if ( la == 0 ) return lb;
        if ( lb == 0 ) return la;
        int[] prev = new int[ lb + 1 ];
        int[] curr = new int[ lb + 1 ];
        for ( int j = 0; j <= lb; j++ ) prev[ j ] = j;
        for ( int i = 1; i <= la; i++ ) {
            curr[ 0 ] = i;
            for ( int j = 1; j <= lb; j++ ) {
                final int cost = a.charAt( i - 1 ) == b.charAt( j - 1 ) ? 0 : 1;
                curr[ j ] = Math.min( Math.min( curr[ j - 1 ] + 1, prev[ j ] + 1 ), prev[ j - 1 ] + cost );
            }
            final int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[ lb ];
    }

    /**
     * Indented, multi-line bullet description suitable for system prompts. One line
     * per relationship type: {@code "    <name>            — <description>"}.
     */
    public static String promptDescription() {
        return PROMPT_DESCRIPTION;
    }

    private static final String PROMPT_DESCRIPTION = String.join( "\n",
            "    related_to        — generic association; use only when no more specific type fits",
            "    part_of           — A is a part/component of B",
            "    contains          — A contains/includes B (directional emphasis on container)",
            "    is_a              — A is a subtype/kind of B",
            "    instance_of       — A is a concrete example/instance of B",
            "    generalizes       — A is a generalization/abstraction of B (inverse of is_a / instance_of)",
            "    requires          — A requires/depends on B",
            "    enables           — A enables/allows/supports B",
            "    uses              — A uses/invokes/operates on B",
            "    produces          — A produces/emits/generates B",
            "    replaces          — A replaces/supersedes B",
            "    precedes          — A precedes B in time/sequence",
            "    extends           — A extends/builds on B (specialization)",
            "    implements        — A is a concrete implementation of B",
            "    alternative_to    — A is a substitute for B (peer alternatives)",
            "    contrasts_with    — A and B are explicitly differentiated/compared",
            "    compatible_with   — A interoperates with B",
            "    mitigates         — A reduces the harm/risk of B",
            "    defines           — A defines/specifies/describes B",
            "    applies_to        — A is relevant within the scope of B",
            "    located_in        — A is spatially within B"
    );
}
