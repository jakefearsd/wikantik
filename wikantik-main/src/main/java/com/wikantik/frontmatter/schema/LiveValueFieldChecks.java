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

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.livevalues.LiveValueExpander;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Advisory {@code {{wiki:...}}} checks for frontmatter, split out of {@link SchemaDrivenFrontmatterValidator}.
 * All findings are warnings: a save never fails over a live-value token. A well-formed
 * {@code cluster.<path>.pages} name is never flagged for naming a cluster that does not exist, since the cluster
 * may be created later and a warning would hint at the existence of private clusters.
 */
final class LiveValueFieldChecks {

    private static final String SUMMARY = "summary";

    private LiveValueFieldChecks() {
    }

    static UnaryOperator< String > orIdentity( final UnaryOperator< String > expander ) {
        return expander != null ? expander : UnaryOperator.identity();
    }

    /** The text a length rule measures: the expanded form for {@code summary}, the raw value for any other field. */
    static String measured( final UnaryOperator< String > expander, final String key, final String val ) {
        return SUMMARY.equals( key ) ? Objects.requireNonNullElse( expander.apply( val ), val ) : val;
    }

    static void check( final Map< String, Object > metadata, final List< FieldViolation > out ) {
        for( final Map.Entry< String, Object > e : metadata.entrySet() ) {
            final String text = flatten( e.getValue() );
            if( text == null || !text.contains( "{{wiki:" ) ) {
                continue;
            }
            if( SUMMARY.equals( e.getKey() ) ) {
                checkSummary( text, out );
            } else {
                out.add( FieldViolation.of( e.getKey(), Severity.WARNING, "livevalues.field",
                        "'" + e.getKey() + "' holds a {{wiki:...}} token, but only 'summary' expands live values; "
                                + "the token will be shown as written." ) );
            }
        }
    }

    private static void checkSummary( final String text, final List< FieldViolation > out ) {
        for( final String name : LiveValueExpander.tokenNames( text ) ) {
            if( !LiveValueExpander.isKnownName( name ) ) {
                out.add( FieldViolation.of( SUMMARY, Severity.WARNING, "livevalues.unknown",
                        "Unknown live value '" + name + "'; the token will be shown as written." ) );
            }
        }
        for( final String bad : LiveValueExpander.malformedTokens( text ) ) {
            out.add( FieldViolation.of( SUMMARY, Severity.WARNING, "livevalues.malformed",
                    "Malformed live-value token '" + bad + "'; it will be shown as written." ) );
        }
    }

    private static String flatten( final Object raw ) {
        if( raw == null ) {
            return null;
        }
        if( raw instanceof Map< ?, ? > m ) {
            final StringBuilder sb = new StringBuilder();
            m.values().forEach( v -> sb.append( flatten( v ) ).append( '\n' ) );
            return sb.toString();
        }
        if( raw instanceof Iterable< ? > it ) {
            final StringBuilder sb = new StringBuilder();
            it.forEach( v -> sb.append( flatten( v ) ).append( '\n' ) );
            return sb.toString();
        }
        return raw.toString();
    }
}
