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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.KgNode;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the frozen system prompt, JSON schema description, and per-chunk user
 * prompt shared by the Claude and Ollama extractors. Isolated so both backends
 * emit identical wording — the {@link EntityExtractorContract} test relies on
 * that invariant when asserting JSON-shape compliance.
 */
public final class ExtractionPromptBuilder {

    private ExtractionPromptBuilder() {}

    /**
     * Stable system prompt: describes the task, the output JSON schema, and
     * hard constraints. Kept identical across requests so prompt caching on
     * Claude actually hits.
     */
    /**
     * Closed relation-type vocabulary, kept in sync with the
     * {@code kg_edges_relationship_type_check} CHECK constraint added in
     * V027 and extended in V030 with {@code generalizes}. Editing this list
     * requires editing the constraint and re-running
     * {@code bin/db/normalize-relationship-types.sql} on existing data.
     */
    public static final String[] RELATION_TYPES = {
        "related_to", "part_of", "contains", "is_a", "instance_of",
        "requires",   "enables", "uses",     "produces", "replaces",
        "precedes",   "extends", "implements", "alternative_to", "contrasts_with",
        "compatible_with", "mitigates", "defines", "applies_to", "located_in",
        "generalizes"
    };

    public static final String SYSTEM_PROMPT =
        "You extract named entities and relationships from wiki content. Output STRICT JSON only — no prose, "
      + "no markdown fence, no commentary. The JSON MUST match this schema exactly:\n"
      + "{\n"
      + "  \"entities\": [ { \"name\": string, \"type\": string, \"confidence\": number in [0,1], \"reasoning\": string } ],\n"
      + "  \"relations\": [ { \"source\": string, \"target\": string, \"type\": ENUM, \"confidence\": number in [0,1], \"reasoning\": string } ]\n"
      + "}\n"
      + "Rules:\n"
      + "- Only include entities that are explicitly named in the chunk. No pronouns, no generic nouns, no dates.\n"
      + "- Entity `type` is a short capitalized noun (Person, Organization, Place, Event, Product, Concept, Project, Version).\n"
      + "- Relations are factual, directional, and grounded in the chunk text. `source` and `target` must appear in `entities`.\n"
      + "- Relation `type` MUST be EXACTLY one of the closed vocabulary below. Pick the closest match. "
      +   "Do NOT invent new types, do NOT vary the casing or separators, and do NOT emit free-form phrases. "
      +   "If no listed type captures the relation cleanly, OMIT the relation entirely — quality over quantity.\n"
      + "  Closed vocabulary (direction is source → target):\n"
      + "    related_to        — generic association; use only when no more specific type fits\n"
      + "    part_of           — A is a part/component of B\n"
      + "    contains          — A contains/includes B (directional emphasis on container)\n"
      + "    is_a              — A is a subtype/kind of B\n"
      + "    instance_of       — A is a concrete example/instance of B\n"
      + "    generalizes       — A is a generalization/abstraction of B (inverse of is_a / instance_of)\n"
      + "    requires          — A requires/depends on B\n"
      + "    enables           — A enables/allows/supports B\n"
      + "    uses              — A uses/invokes/operates on B\n"
      + "    produces          — A produces/emits/generates B\n"
      + "    replaces          — A replaces/supersedes B\n"
      + "    precedes          — A precedes B in time/sequence\n"
      + "    extends           — A extends/builds on B (specialization)\n"
      + "    implements        — A is a concrete implementation of B\n"
      + "    alternative_to    — A is a substitute for B (peer alternatives)\n"
      + "    contrasts_with    — A and B are explicitly differentiated/compared\n"
      + "    compatible_with   — A interoperates with B\n"
      + "    mitigates         — A reduces the harm/risk of B\n"
      + "    defines           — A defines/specifies/describes B\n"
      + "    applies_to        — A is relevant within the scope of B\n"
      + "    located_in        — A is spatially within B\n"
      + "- `confidence` is your calibrated certainty, not a popularity estimate.\n"
      + "- Every `reasoning` value MUST be 15 words or fewer. No multi-sentence explanations.\n"
      + "- REUSE names from the provided dictionary verbatim whenever the chunk refers to an entity that is already known. "
      +   "Do not invent a new entity if a matching dictionary entry exists — case, spelling, and punctuation must match exactly.\n"
      + "- Only emit an entity that is NOT in the dictionary when the chunk clearly names a new, distinct subject. "
      +   "When in doubt, omit it.\n"
      + "- Keep the `entities` and `relations` arrays as small as possible — quality over quantity.\n"
      + "- Return empty arrays when the chunk has no extractable entities.";

    /**
     * Formats the per-chunk user message: page header, heading path, existing
     * node dictionary (capped at {@code maxNodes}), and the chunk text.
     */
    public static String buildUserPrompt( final ExtractionChunk chunk,
                                          final ExtractionContext context,
                                          final int maxNodes ) {
        final StringBuilder sb = new StringBuilder( 1024 );
        sb.append( "Page: " ).append( chunk.pageName() ).append( '\n' );
        if( !chunk.headingPath().isEmpty() ) {
            sb.append( "Section: " )
              .append( String.join( " › ", chunk.headingPath() ) )
              .append( '\n' );
        }

        final String dict = existingNodesDictionary( context, maxNodes );
        if( !dict.isEmpty() ) {
            sb.append( "Known entities (name :: type) — reuse these names when the chunk refers to them:\n" )
              .append( dict ).append( '\n' );
        }

        sb.append( "\nChunk:\n" )
          .append( chunk.text() )
          .append( "\n\nReturn ONLY the JSON object." );
        return sb.toString();
    }

    /**
     * Deterministic, sorted listing of existing node names + types, capped at
     * {@code maxNodes} to bound prompt size.
     */
    public static String existingNodesDictionary( final ExtractionContext context, final int maxNodes ) {
        if( context == null || context.existingNodes().isEmpty() || maxNodes <= 0 ) {
            return "";
        }
        return context.existingNodes().stream()
            .sorted( ( a, b ) -> a.name().compareToIgnoreCase( b.name() ) )
            .limit( maxNodes )
            .map( ExtractionPromptBuilder::formatNode )
            .collect( Collectors.joining( "\n" ) );
    }

    private static String formatNode( final KgNode n ) {
        final String type = n.nodeType() == null ? "Concept" : n.nodeType();
        return "- " + n.name() + " :: " + type.toLowerCase( Locale.ROOT );
    }
}
