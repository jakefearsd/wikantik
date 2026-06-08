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
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.knowledge.agent.FrontmatterRunbookValidator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates parsed frontmatter against the server-authoritative {@link FrontmatterSchema}, returning
 * field-addressable {@link FieldViolation}s. Pure function — the same instance backs the save filter,
 * the {@code /api/frontmatter/validate} dry-run, and the MCP write tools, so all surfaces enforce one
 * rule set. The genuinely procedural {@code runbook:} block is delegated to
 * {@link FrontmatterRunbookValidator}; its issues are mapped into the same channel.
 *
 * <p>See {@code docs/superpowers/specs/2026-06-08-structured-page-curation-design.md} §4.3.</p>
 */
public final class SchemaDrivenFrontmatterValidator {

    private static final String KEBAB = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private final FrontmatterSchema schema;

    public SchemaDrivenFrontmatterValidator( final FrontmatterSchema schema ) {
        this.schema = schema;
    }

    public List< FieldViolation > validate( final Map< String, Object > metadata, final ValidationCtx ctx ) {
        final List< FieldViolation > out = new ArrayList<>();
        if ( metadata == null ) {
            return out;
        }
        for ( final FieldSpec spec : schema.fields() ) {
            final Object raw = metadata.get( spec.key() );
            switch ( spec.widget() ) {
                case ENUM -> validateEnum( spec, raw, ctx, out );
                case TEXT, TEXTAREA -> validateText( spec, raw, out );
                case DATE -> validateDate( spec, raw, out, false );
                case DATETIME -> validateDate( spec, raw, out, true );
                case TAGS -> validateTags( spec, raw, out );
                case PAGE_REFS -> validatePageRefs( spec, raw, ctx, out );
                case RUNBOOK_BLOCK -> validateRunbook( metadata, ctx, out );
                default -> { /* TRISTATE, READONLY: no value constraints */ }
            }
            if ( "verified_by".equals( spec.key() ) ) {
                validateVerifiedBy( raw, ctx, out );
            }
        }
        return out;
    }

    private void validateEnum( final FieldSpec spec, final Object raw,
                               final ValidationCtx ctx, final List< FieldViolation > out ) {
        if ( raw == null ) {
            return;
        }
        final String val = raw.toString().trim();
        if ( val.isEmpty() || spec.canonicalValues().contains( val ) ) {
            return;
        }
        final String canon = String.join( ", ", spec.canonicalValues() );
        if ( spec.open() ) {
            final String suggestion = spec.suggestionMap().get( val );
            final String msg = "`" + spec.key() + ": \"" + val + "\"` is not a canonical value for '"
                    + spec.key() + "'. Canonical values: " + canon
                    + ". Tolerated for now, but it will be rejected once the corpus is normalized."
                    + ( suggestion != null ? " Suggested replacement: `" + suggestion + "`." : "" );
            out.add( new FieldViolation( spec.key(), ctx.nonCanonicalEnumSeverity(),
                    spec.key() + ".noncanonical", msg, suggestion ) );
        } else {
            out.add( FieldViolation.of( spec.key(), Severity.ERROR, spec.key() + ".enum.invalid",
                    "`" + spec.key() + ": \"" + val + "\"` is not allowed. Allowed values: " + canon + "." ) );
        }
    }

    private void validateText( final FieldSpec spec, final Object raw, final List< FieldViolation > out ) {
        if ( raw == null ) {
            return;
        }
        final String val = raw.toString();
        if ( spec.minLen() != null || spec.maxLen() != null ) {
            final int len = val.trim().length();
            final boolean tooShort = spec.minLen() != null && len < spec.minLen();
            final boolean tooLong = spec.maxLen() != null && len > spec.maxLen();
            if ( tooShort || tooLong ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".length",
                        "'" + spec.key() + "' should be " + spec.minLen() + "–" + spec.maxLen()
                                + " characters (is " + len + ")." ) );
            }
        }
        if ( spec.pattern() != null && !val.matches( spec.pattern() ) ) {
            final String suggestion = slugify( val );
            final String msg = "'" + spec.key() + "' value \"" + val
                    + "\" is not a valid slug — use lowercase kebab-case"
                    + ( suggestion.isEmpty() ? "." : ", e.g. '" + suggestion + "'." );
            out.add( new FieldViolation( spec.key(), Severity.ERROR, spec.key() + ".slug.malformed",
                    msg, suggestion.isEmpty() ? null : suggestion ) );
        }
    }

    private void validateDate( final FieldSpec spec, final Object raw,
                               final List< FieldViolation > out, final boolean allowInstant ) {
        if ( raw == null ) {
            return;
        }
        final String val = raw.toString().trim();
        if ( val.isEmpty() || parsesAsTemporal( val, allowInstant ) ) {
            return;
        }
        out.add( FieldViolation.of( spec.key(), Severity.ERROR, spec.key() + ".date.malformed",
                "'" + spec.key() + "' is not a valid "
                        + ( allowInstant ? "ISO-8601 timestamp" : "ISO date (YYYY-MM-DD)" )
                        + ": \"" + val + "\"." ) );
    }

    private void validateTags( final FieldSpec spec, final Object raw, final List< FieldViolation > out ) {
        if ( !( raw instanceof List< ? > list ) ) {
            return;
        }
        for ( final Object o : list ) {
            if ( o == null ) {
                continue;
            }
            final String t = o.toString().trim();
            if ( !t.isEmpty() && !t.matches( KEBAB ) ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, "tags.kebab",
                        "tag '" + t + "' is not lowercase kebab-case (e.g. 'graph-theory')." ) );
            }
        }
    }

    private void validatePageRefs( final FieldSpec spec, final Object raw,
                                   final ValidationCtx ctx, final List< FieldViolation > out ) {
        if ( !( raw instanceof List< ? > list ) ) {
            return;
        }
        for ( final Object o : list ) {
            if ( o == null ) {
                continue;
            }
            final String ref = o.toString().trim();
            if ( !ref.isEmpty() && !ctx.pageResolves().test( ref ) ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, "related.unresolved",
                        "related page '" + ref + "' does not resolve to an existing page." ) );
            }
        }
    }

    private void validateVerifiedBy( final Object raw, final ValidationCtx ctx,
                                     final List< FieldViolation > out ) {
        if ( raw == null ) {
            return;
        }
        final String val = raw.toString().trim();
        if ( !val.isEmpty() && !ctx.isTrustedAuthor().test( val ) ) {
            out.add( FieldViolation.of( "verified_by", Severity.WARNING, "verified_by.untrusted",
                    "verified_by '" + val + "' is not a recognized trusted author; "
                            + "verification confidence may be reduced." ) );
        }
    }

    private void validateRunbook( final Map< String, Object > metadata, final ValidationCtx ctx,
                                  final List< FieldViolation > out ) {
        final FrontmatterRunbookValidator.Result r =
                FrontmatterRunbookValidator.validate( metadata, ctx.pageResolves(), ctx.pageResolves() );
        for ( final FrontmatterRunbookValidator.Issue issue : r.issues() ) {
            out.add( new FieldViolation( runbookField( issue.kind() ), Severity.ERROR,
                    "runbook." + issue.kind().name().toLowerCase( Locale.ROOT ), issue.detail(), null ) );
        }
    }

    private static String runbookField( final FrontmatterRunbookValidator.IssueKind kind ) {
        return switch ( kind ) {
            case WHEN_TO_USE_EMPTY -> "runbook.when_to_use";
            case STEPS_TOO_FEW -> "runbook.steps";
            case PITFALLS_EMPTY -> "runbook.pitfalls";
            case RELATED_TOOL_INVALID -> "runbook.related_tools";
            case REFERENCE_UNRESOLVABLE -> "runbook.references";
            case MISSING_BLOCK, MALFORMED_BLOCK -> "runbook";
        };
    }

    private static boolean parsesAsTemporal( final String val, final boolean allowInstant ) {
        try {
            LocalDate.parse( val );
            return true;
        } catch ( final DateTimeParseException notADate ) {
            // not a plain date; try richer forms below when allowed
        }
        if ( allowInstant ) {
            try {
                Instant.parse( val );
                return true;
            } catch ( final DateTimeParseException notAnInstant ) {
                // try offset-date-time below
            }
            try {
                OffsetDateTime.parse( val );
                return true;
            } catch ( final DateTimeParseException notAnOffsetDateTime ) {
                // falls through to false
            }
        }
        return false;
    }

    private static String slugify( final String s ) {
        return s.toLowerCase( Locale.ROOT ).trim()
                .replaceAll( "[^a-z0-9]+", "-" )
                .replaceAll( "(^-+|-+$)", "" );
    }
}
