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
package com.wikantik.frontmatter.schema;

import com.wikantik.api.frontmatter.schema.FieldSpec;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;

import java.util.List;
import java.util.Locale;

/**
 *  Shared slug-pattern check: does {@code val} match {@code spec}'s declared pattern, and if
 *  not, a WARNING with a slugified suggestion.
 *
 *  <p>Used both by {@link SchemaDrivenFrontmatterValidator#validateText} (a single scalar
 *  TEXT field) and {@link ClusterFieldValidator} (each membership of the scalar-or-list
 *  {@code cluster} field checked independently) — the two places a schema field's pattern is
 *  enforced against an authored value.</p>
 */
final class SlugPatternCheck {

    private SlugPatternCheck() {
    }

    /**
     *  Checks {@code val} against {@code spec.pattern()} (a no-op when the spec declares none)
     *  and, on mismatch, appends an advisory violation suggesting a slugified replacement.
     *
     *  <p>Advisory, not blocking: ~15 live clusters are non-kebab (e.g. "Data Structures"), so a
     *  blocking error would 422 every edit to those pages. Warn sternly + suggest the slug.</p>
     *
     *  @param spec the field spec — supplies both the pattern and the violation's field key
     *  @param val  the value being checked
     *  @param out  collects the violation, if any
     */
    static void check( final FieldSpec spec, final String val, final List< FieldViolation > out ) {
        if ( spec.pattern() == null || val.matches( spec.pattern() ) ) {
            return;
        }
        final String suggestion = slugify( val );
        final String msg = "'" + spec.key() + "' value \"" + val
                + "\" is not a valid slug — use lowercase kebab-case"
                + ( suggestion.isEmpty() ? "." : ", e.g. '" + suggestion + "'." )
                + " Tolerated for now, but it will be rejected once the corpus is normalized.";
        out.add( new FieldViolation( spec.key(), Severity.WARNING, spec.key() + ".slug.malformed",
                msg, suggestion.isEmpty() ? null : suggestion ) );
    }

    private static String slugify( final String s ) {
        return s.toLowerCase( Locale.ROOT ).trim()
                .replaceAll( "[^a-z0-9]+", "-" )
                .replaceAll( "(^-+|-+$)", "" );
    }
}
