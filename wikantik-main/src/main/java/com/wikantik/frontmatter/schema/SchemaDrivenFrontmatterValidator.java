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
import com.wikantik.api.pagegraph.ClusterPath;
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
                case TEXT, TEXTAREA -> {
                    if ( "cluster".equals( spec.key() ) ) {
                        // cluster: is the one TEXT field that may be YAML-multi-valued
                        // (ClusterDeclarationDesign Phase 5) — it needs its own entry-aware path
                        // rather than validateText's raw.toString(), which would stringify a list.
                        validateCluster( spec, metadata, ctx, out );
                    } else {
                        validateText( spec, raw, ctx, out );
                    }
                }
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
        final String canon = String.join( ", ", spec.canonicalValues() );
        if ( spec.open() ) {
            final String val = raw.toString().trim();
            if ( val.isEmpty() || spec.canonicalValues().contains( val ) ) {
                return;
            }
            final String suggestion = spec.suggestionMap().get( val );
            final String msg = "`" + spec.key() + ": \"" + val + "\"` is not a canonical value for '"
                    + spec.key() + "'. Canonical values: " + canon
                    + ". Tolerated for now, but it will be rejected once the corpus is normalized."
                    + ( suggestion != null ? " Suggested replacement: `" + suggestion + "`." : "" );
            out.add( new FieldViolation( spec.key(), ctx.nonCanonicalEnumSeverity(),
                    spec.key() + ".noncanonical", msg, suggestion ) );
            return;
        }
        // Closed enum (e.g. audience): may be YAML-multi-valued (a list `[agents, humans]`, or a
        // pipe/comma string). SnakeYAML parses a list into a List, so check each token, not toString().
        final List< String > invalid = new ArrayList<>();
        for ( final String token : enumTokens( raw ) ) {
            if ( !spec.canonicalValues().contains( token ) ) {
                invalid.add( token );
            }
        }
        if ( !invalid.isEmpty() ) {
            out.add( FieldViolation.of( spec.key(), Severity.ERROR, spec.key() + ".enum.invalid",
                    "`" + spec.key() + "` has value(s) not allowed: " + invalid
                            + ". Allowed values: " + canon + "." ) );
        }
    }

    /** Splits a closed-enum value into lowercase tokens: a YAML list, or a pipe/comma-separated string. */
    private static List< String > enumTokens( final Object raw ) {
        final List< String > tokens = new ArrayList<>();
        if ( raw instanceof List< ? > list ) {
            for ( final Object o : list ) {
                if ( o != null ) {
                    final String t = o.toString().trim().toLowerCase( Locale.ROOT );
                    if ( !t.isEmpty() ) {
                        tokens.add( t );
                    }
                }
            }
        } else {
            for ( final String part : raw.toString().split( "[|,]" ) ) {
                final String t = part.trim().toLowerCase( Locale.ROOT );
                if ( !t.isEmpty() ) {
                    tokens.add( t );
                }
            }
        }
        return tokens;
    }

    private void validateText( final FieldSpec spec, final Object raw, final ValidationCtx ctx,
                                final List< FieldViolation > out ) {
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
            // Advisory, not blocking: ~15 live clusters are non-kebab (e.g. "Data Structures"), so a
            // blocking error would 422 every edit to those pages. Warn sternly + suggest the slug.
            final String suggestion = slugify( val );
            final String msg = "'" + spec.key() + "' value \"" + val
                    + "\" is not a valid slug — use lowercase kebab-case"
                    + ( suggestion.isEmpty() ? "." : ", e.g. '" + suggestion + "'." )
                    + " Tolerated for now, but it will be rejected once the corpus is normalized.";
            out.add( new FieldViolation( spec.key(), Severity.WARNING, spec.key() + ".slug.malformed",
                    msg, suggestion.isEmpty() ? null : suggestion ) );
        }
    }

    /**
     * Validates {@code cluster:}, the one TEXT field that may be a YAML list rather than a
     * scalar (ClusterDeclarationDesign Phase 5: a non-hub page may belong to several clusters).
     *
     * <p>A {@code type: hub} page declares exactly one cluster — a declaration is singular — so
     * more than one entry there is a blocking {@link Severity#ERROR}. A single-element list on a
     * hub is just a normalised scalar and raises no error. Everywhere else, every membership is
     * validated independently through {@link #validateClusterEntry}, so a violation names the
     * offending entry rather than the stringified list.</p>
     */
    private void validateCluster( final FieldSpec spec, final Map< String, Object > metadata,
                                  final ValidationCtx ctx, final List< FieldViolation > out ) {
        final Object raw = metadata.get( spec.key() );
        if ( raw == null ) {
            return;
        }
        final List< String > memberships = ClusterPath.memberships( raw );
        if ( memberships.isEmpty() ) {
            return;
        }
        final Object typeRaw = metadata.get( "type" );
        final boolean isHub = typeRaw != null && "hub".equalsIgnoreCase( typeRaw.toString().trim() );
        if ( isHub && memberships.size() > 1 ) {
            out.add( FieldViolation.of( spec.key(), Severity.ERROR, "cluster.hub.multivalued",
                    "a hub page declares exactly one cluster; '" + spec.key() + "' names "
                            + memberships.size() + ": " + memberships + ". Remove all but one." ) );
            return;
        }
        for ( final String entry : memberships ) {
            validateClusterEntry( spec, entry, ctx, out );
        }
    }

    /** The per-entry checks {@link #validateCluster} runs against each membership. */
    private void validateClusterEntry( final FieldSpec spec, final String val, final ValidationCtx ctx,
                                       final List< FieldViolation > out ) {
        if ( spec.pattern() != null && !val.matches( spec.pattern() ) ) {
            // Advisory, not blocking: ~15 live clusters are non-kebab (e.g. "Data Structures"), so a
            // blocking error would 422 every edit to those pages. Warn sternly + suggest the slug.
            final String suggestion = slugify( val );
            final String msg = "'" + spec.key() + "' value \"" + val
                    + "\" is not a valid slug — use lowercase kebab-case"
                    + ( suggestion.isEmpty() ? "." : ", e.g. '" + suggestion + "'." )
                    + " Tolerated for now, but it will be rejected once the corpus is normalized.";
            out.add( new FieldViolation( spec.key(), Severity.WARNING, spec.key() + ".slug.malformed",
                    msg, suggestion.isEmpty() ? null : suggestion ) );
        }
        if ( !ctx.clusterIsDeclared().test( val ) ) {
            // ClusterDeclarationDesign: a cluster exists only if a hub page declares it.
            // Advisory by design — naming an undeclared cluster must never block the save
            // (the page stays fully retrievable); it surfaces in the editor and is counted
            // on the /admin/drift burn-down, where the fix is to write the missing hub.
            out.add( FieldViolation.of( spec.key(), Severity.WARNING, "cluster.undeclared",
                    "cluster '" + val + "' is not declared by any hub page. Create a page with"
                            + " 'type: hub' and 'cluster: " + val + "' to declare it." ) );
        }
    }

    private void validateDate( final FieldSpec spec, final Object raw,
                               final List< FieldViolation > out, final boolean allowInstant ) {
        if ( raw == null ) {
            return;
        }
        // SnakeYAML parses YAML date/timestamp scalars (e.g. `date: 2026-03-20`) into a
        // java.util.Date, not a String. Such a value is already a valid temporal — accept it
        // (its toString() is "Fri Mar 20 ..." which would otherwise fail ISO parsing below).
        if ( raw instanceof java.util.Date || raw instanceof java.util.Calendar ) {
            return;
        }
        final String val = raw.toString().trim();
        if ( val.isEmpty() || parsesAsTemporal( val, allowInstant ) ) {
            return;
        }
        // Advisory, not blocking: the corpus has non-ISO dates (localized strings, "YYYY-MM-DD"
        // placeholders); blocking would break editing those pages. Warn rather than 422.
        out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".date.malformed",
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
