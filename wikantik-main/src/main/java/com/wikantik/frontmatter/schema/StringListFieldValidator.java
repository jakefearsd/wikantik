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

/**
 *  Validates {@code STRING_LIST} frontmatter fields (advisory warnings only). Split out of
 *  {@link SchemaDrivenFrontmatterValidator} to keep that class under the complexity gate.
 */
final class StringListFieldValidator {

    private StringListFieldValidator() {
    }

    static void validate( final FieldSpec spec, final Object raw, final List< FieldViolation > out ) {
        if ( raw == null ) {
            return;
        }
        if ( !( raw instanceof List< ? > list ) ) {
            out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".list",
                    "'" + spec.key() + "' should be a list, e.g. [first name, second name]." ) );
            return;
        }
        for ( final Object o : list ) {
            final String s = o == null ? "" : o.toString().trim();
            if ( s.isEmpty() ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".blank",
                        "'" + spec.key() + "' contains a blank entry." ) );
            } else if ( spec.maxLen() != null && s.length() > spec.maxLen() ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".length",
                        "'" + spec.key() + "' entry is longer than " + spec.maxLen() + " characters." ) );
            }
        }
    }
}
