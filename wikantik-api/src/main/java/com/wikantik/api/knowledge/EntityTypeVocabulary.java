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
}
