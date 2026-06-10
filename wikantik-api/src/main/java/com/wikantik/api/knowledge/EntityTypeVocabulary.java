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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the canonical entity-type vocabulary — the lowercase
 * {@code node_type} values that both extractors (chunk + page) emit and that map
 * one-to-one onto the wk: ontology entity classes in
 * {@code com.wikantik.ontology.NodeTypeMapping}.
 *
 * <p>These nine are the <em>entity</em> classes (extracted KG nodes). They are
 * deliberately distinct from the page-structural types (article, hub, runbook, …),
 * which are not produced by entity extraction. A drift-guard test in
 * {@code wikantik-ontology} asserts each entry here has an explicit class mapping.</p>
 */
public final class EntityTypeVocabulary {

    private EntityTypeVocabulary() {}

    /** Canonical lowercase entity node_type values (extractor output + wk: entity classes). */
    public static final List< String > ENTITY_CLASSES = List.of(
            "person", "organization", "place", "event", "product",
            "technology", "concept", "project", "version" );

    /** Membership-test view of {@link #ENTITY_CLASSES} for allowlist checks. */
    public static final Set< String > ENTITY_CLASS_SET = Set.copyOf( ENTITY_CLASSES );

    /** The fallback class used when an extracted type is outside the vocabulary. */
    public static final String DEFAULT_ENTITY_CLASS = "concept";

    /**
     * High-precision synonym map: common LLM-emitted type labels that are NOT one of the nine
     * canonical classes but unambiguously denote one of them. Resolving these prevents real
     * technologies (the model often labels them "database"/"framework"/"library"/"tool"/…) from
     * silently collapsing to {@code concept} (chunk extractor) or being dropped (page extractor),
     * which is what put 16 mis-typed technologies under the {@code wk:implements} SHACL shape.
     *
     * <p>Keys are lowercase. Every value MUST be a member of {@link #ENTITY_CLASS_SET} — a unit test
     * enforces that invariant so an alias can never point outside the vocabulary.</p>
     */
    public static final Map< String, String > TYPE_ALIASES = Map.ofEntries(
            // → technology (the dominant miss: software/tooling artifacts)
            Map.entry( "framework", "technology" ), Map.entry( "library", "technology" ),
            Map.entry( "tool", "technology" ), Map.entry( "software", "technology" ),
            Map.entry( "database", "technology" ), Map.entry( "language", "technology" ),
            Map.entry( "programming language", "technology" ), Map.entry( "api", "technology" ),
            Map.entry( "sdk", "technology" ), Map.entry( "platform", "technology" ),
            Map.entry( "package", "technology" ), Map.entry( "module", "technology" ),
            Map.entry( "component", "technology" ), Map.entry( "runtime", "technology" ),
            Map.entry( "compiler", "technology" ), Map.entry( "server", "technology" ),
            Map.entry( "middleware", "technology" ), Map.entry( "protocol", "technology" ),
            Map.entry( "operating system", "technology" ), Map.entry( "os", "technology" ),
            Map.entry( "datastore", "technology" ), Map.entry( "data store", "technology" ),
            Map.entry( "service", "technology" ), Map.entry( "application", "technology" ),
            Map.entry( "app", "technology" ), Map.entry( "engine", "technology" ),
            Map.entry( "cli", "technology" ), Map.entry( "daemon", "technology" ),
            Map.entry( "driver", "technology" ),
            // → organization
            Map.entry( "company", "organization" ), Map.entry( "corporation", "organization" ),
            Map.entry( "vendor", "organization" ), Map.entry( "institution", "organization" ),
            Map.entry( "agency", "organization" ), Map.entry( "business", "organization" ),
            Map.entry( "firm", "organization" ), Map.entry( "organisation", "organization" ),
            Map.entry( "team", "organization" ), Map.entry( "foundation", "organization" ),
            Map.entry( "consortium", "organization" ),
            // → person
            Map.entry( "people", "person" ), Map.entry( "individual", "person" ),
            Map.entry( "human", "person" ), Map.entry( "author", "person" ),
            Map.entry( "developer", "person" ), Map.entry( "engineer", "person" ),
            Map.entry( "researcher", "person" ), Map.entry( "scientist", "person" ),
            Map.entry( "maintainer", "person" ),
            // → place
            Map.entry( "location", "place" ), Map.entry( "city", "place" ),
            Map.entry( "country", "place" ), Map.entry( "region", "place" ),
            Map.entry( "geography", "place" ), Map.entry( "geographic location", "place" ),
            Map.entry( "continent", "place" ), Map.entry( "state", "place" ),
            // → event
            Map.entry( "incident", "event" ), Map.entry( "occurrence", "event" ),
            Map.entry( "conference", "event" ), Map.entry( "meeting", "event" ),
            Map.entry( "war", "event" ), Map.entry( "election", "event" ),
            Map.entry( "summit", "event" ), Map.entry( "outage", "event" ),
            // → project
            Map.entry( "initiative", "project" ), Map.entry( "program", "project" ),
            Map.entry( "programme", "project" ), Map.entry( "effort", "project" ),
            // → version
            Map.entry( "release", "version" ), Map.entry( "edition", "version" ),
            Map.entry( "build", "version" ), Map.entry( "revision", "version" ),
            // → concept (mostly rescues the page extractor, which drops unknown types outright)
            Map.entry( "idea", "concept" ), Map.entry( "topic", "concept" ),
            Map.entry( "principle", "concept" ), Map.entry( "design pattern", "concept" ),
            Map.entry( "pattern", "concept" ), Map.entry( "algorithm", "concept" ),
            Map.entry( "method", "concept" ), Map.entry( "methodology", "concept" ),
            Map.entry( "theory", "concept" ), Map.entry( "technique", "concept" ),
            Map.entry( "data structure", "concept" ), Map.entry( "paradigm", "concept" ),
            Map.entry( "approach", "concept" ), Map.entry( "best practice", "concept" ),
            Map.entry( "standard", "concept" ), Map.entry( "model", "concept" ),
            Map.entry( "abstraction", "concept" ), Map.entry( "strategy", "concept" ) );

    /**
     * Resolves a raw LLM-emitted type onto a canonical entity class, applying {@link #TYPE_ALIASES}.
     * Returns the canonical class for a direct match or a known synonym; {@link Optional#empty()} for
     * {@code null}/blank/unrecognised input. Callers decide the fallback: the chunk extractor defaults
     * to {@link #DEFAULT_ENTITY_CLASS}; the page extractor drops the entity.
     */
    public static Optional< String > canonicalOrAlias( final String rawType ) {
        if ( rawType == null || rawType.isBlank() ) {
            return Optional.empty();
        }
        final String key = rawType.trim().toLowerCase( Locale.ROOT );
        if ( ENTITY_CLASS_SET.contains( key ) ) {
            return Optional.of( key );
        }
        return Optional.ofNullable( TYPE_ALIASES.get( key ) );
    }
}
