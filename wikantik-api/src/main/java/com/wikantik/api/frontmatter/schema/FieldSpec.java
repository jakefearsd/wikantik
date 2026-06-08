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
package com.wikantik.api.frontmatter.schema;

import java.util.List;
import java.util.Map;

/**
 * Describes one frontmatter field: how it renders, what constrains it, and (for enums) how to nudge
 * non-canonical values toward canon. Part of the server-authoritative {@link FrontmatterSchema}; both
 * the React form and the {@code SchemaDrivenFrontmatterValidator} read from the same {@code FieldSpec}.
 *
 * @param key             the frontmatter key (e.g. {@code type}).
 * @param label           a human label for the form.
 * @param widget          how the form renders it; see {@link Widget}.
 * @param canonicalValues for enums, the curated values offered in the UI (empty for non-enums).
 * @param open            for enums: {@code true} = curated-open (non-canonical warns, configurable to
 *                        error), {@code false} = closed (non-member is always an error).
 * @param minLen          optional minimum length (text) or list size; {@code null} = unconstrained.
 * @param maxLen          optional maximum length (text) or list size; {@code null} = unconstrained.
 * @param pattern         optional regex the value must match (e.g. the cluster slug); {@code null} = none.
 * @param suggestionMap   legacy-value to canonical-value hints used to populate
 *                        {@link FieldViolation#suggestion()} (empty if none).
 */
public record FieldSpec(
        String key,
        String label,
        Widget widget,
        List< String > canonicalValues,
        boolean open,
        Integer minLen,
        Integer maxLen,
        String pattern,
        Map< String, String > suggestionMap
) {
    public FieldSpec {
        canonicalValues = canonicalValues == null ? List.of() : List.copyOf( canonicalValues );
        suggestionMap   = suggestionMap   == null ? Map.of()  : Map.copyOf( suggestionMap );
    }

    /** A plain text field. */
    public static FieldSpec text( final String key, final String label ) {
        return new FieldSpec( key, label, Widget.TEXT, List.of(), false, null, null, null, Map.of() );
    }

    /** A read-only (system-managed or derived) field. */
    public static FieldSpec readonly( final String key, final String label ) {
        return new FieldSpec( key, label, Widget.READONLY, List.of(), false, null, null, null, Map.of() );
    }
}
