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
package com.wikantik.mcp.tools;

import com.wikantik.mcp.tools.PageCheckResult.Severity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Concrete {@link PageCheck} implementations (Gang of Four: Strategy pattern).
 *
 * <p>Each inner class encapsulates one validation concern.  They are composed
 * by audit and verification tools to build the exact set of checks needed,
 * without duplicating the validation logic across tools.
 *
 * <p>All implementations are stateless after construction, configured via
 * constructor parameters, and safe for concurrent use.
 *
 * @see PageCheck
 * @see PageCheckResult
 * @see AuditClusterTool
 * @see VerifyPagesTool
 */
public final class PageChecks {

    private PageChecks() {
    }

    // -----------------------------------------------------------------------
    //  SEO Checks
    // -----------------------------------------------------------------------

    /**
     * Checks that the page has a summary of appropriate length for use as a
     * meta description.  Google truncates descriptions at ~155 characters;
     * summaries under 50 characters are too terse.
     *
     * <p>A blank (whitespace-only) summary is treated as missing.
     */
    public static class SummaryCheck implements PageCheck {

        private final boolean checkLengthBounds;

        /** @param checkLengthBounds if true, flags summaries that are too short or too long */
        public SummaryCheck( final boolean checkLengthBounds ) {
            this.checkLengthBounds = checkLengthBounds;
        }

        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final List< PageCheckResult > results = new ArrayList<>();
            final Object val = ctx.metadata().get( "summary" );
            final String summary = val != null ? val.toString().strip() : null;

            if ( summary == null || summary.isEmpty() ) {
                results.add( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                        "missing_summary", "No summary — no meta description for search engines" ) );
                return results;
            }

            if ( checkLengthBounds ) {
                final int len = summary.length();
                if ( len < 50 ) {
                    results.add( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                            "summary_too_short",
                            "Summary too short (" + len + " chars) — aim for 50-160 characters" ) );
                } else if ( len > 160 ) {
                    results.add( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                            "summary_too_long",
                            "Summary too long (" + len + " chars) — Google will truncate at ~155 characters" ) );
                }
            }

            return results;
        }
    }

    /**
     * Checks that the page has at least one tag.  Pages without tags are
     * excluded from the Google News Sitemap.
     */
    public static class TagsCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final Object val = ctx.metadata().get( "tags" );
            if ( !( val instanceof List< ? > list ) || list.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                        "no_tags", "No tags — page will not appear in Google News Sitemap" ) );
            }
            return List.of();
        }
    }

    /**
     * Checks hub pages for consistent {@code related} metadata.  Optionally
     * verifies that each related page actually exists.
     */
    public static class HubRelatedCheck implements PageCheck {

        private final boolean verifyExistence;

        /** @param verifyExistence if true, checks that each related page exists in the wiki */
        public HubRelatedCheck( final boolean verifyExistence ) {
            this.verifyExistence = verifyExistence;
        }

        @Override
        @SuppressWarnings( "unchecked" )
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            if ( !"hub".equals( ctx.metadata().get( "type" ) ) ) {
                return List.of();
            }

            final Object val = ctx.metadata().get( "related" );
            if ( !( val instanceof List< ? > list ) || list.isEmpty() ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                        "hub_empty_related",
                        "Hub page has no related pages — CollectionPage JSON-LD will have empty hasPart" ) );
            }

            if ( !verifyExistence || ctx.pageManager() == null ) {
                return List.of();
            }

            final List< PageCheckResult > results = new ArrayList<>();
            for ( final Object rel : list ) {
                if ( ctx.pageManager().getPage( rel.toString() ) == null ) {
                    results.add( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                            "hub_related_missing",
                            "Hub related page '" + rel + "' does not exist — broken hasPart in JSON-LD" ) );
                }
            }
            return results;
        }
    }

    /**
     * Checks that the page has a date field for JSON-LD {@code datePublished}.
     */
    public static class DateCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            if ( !ctx.metadata().containsKey( "date" ) ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                        "no_date", "No date — JSON-LD will lack datePublished" ) );
            }
            return List.of();
        }
    }

    /**
     * Checks that a page with a cluster field also has a type field.
     */
    public static class ClusterTypeCheck implements PageCheck {
        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            if ( ctx.metadata().containsKey( "cluster" ) && !ctx.metadata().containsKey( "type" ) ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.WARNING, "seo",
                        "cluster_without_type",
                        "Has cluster but no type — set type to 'article' or 'hub'" ) );
            }
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    //  Metadata Checks
    // -----------------------------------------------------------------------

    /**
     * Checks that a configurable set of metadata fields are present and non-blank.
     * Optionally generates auto-fix suggestions for missing fields.
     */
    public static class MetadataFieldsCheck implements PageCheck {

        private final Set< String > requiredFields;
        private final boolean generateAutoFixes;
        private final String clusterName;

        /**
         * @param requiredFields    set of field names that must be present
         * @param generateAutoFixes if true, populates autoFix on results where possible
         * @param clusterName       the expected cluster name (for auto-fix of missing cluster field)
         */
        public MetadataFieldsCheck( final Set< String > requiredFields,
                                    final boolean generateAutoFixes, final String clusterName ) {
            this.requiredFields = requiredFields;
            this.generateAutoFixes = generateAutoFixes;
            this.clusterName = clusterName;
        }

        /** Simple constructor for tools that just want to check presence without auto-fix. */
        public MetadataFieldsCheck( final Set< String > requiredFields ) {
            this( requiredFields, false, null );
        }

        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            final List< PageCheckResult > results = new ArrayList<>();

            for ( final String field : requiredFields ) {
                final Object val = ctx.metadata().get( field );
                final boolean missing = val == null
                        || ( val instanceof String s && s.isBlank() )
                        || ( val instanceof List< ? > l && l.isEmpty() );

                if ( missing ) {
                    final Map< String, Object > fix = generateAutoFixes
                            ? buildAutoFix( field, ctx )
                            : null;

                    results.add( new PageCheckResult( ctx.pageName(), Severity.WARNING,
                            "metadata", "missing_" + field,
                            "Missing required metadata field: " + field, fix ) );
                }
            }

            return results;
        }

        private Map< String, Object > buildAutoFix( final String field, final PageCheckContext ctx ) {
            return switch ( field ) {
                case "status" -> Map.of( "action", "set_metadata",
                        "field", "status", "proposedValue", "active",
                        "reason", "Missing status field — defaulting to active" );
                case "date" -> {
                    if ( ctx.page() != null && ctx.page().getLastModified() != null ) {
                        yield Map.of( "action", "set_metadata",
                                "field", "date",
                                "proposedValue", ctx.page().getLastModified().toInstant().toString(),
                                "reason", "Missing date — using last modified date" );
                    }
                    yield null;
                }
                case "cluster" -> clusterName != null
                        ? Map.of( "action", "set_metadata",
                                "field", "cluster", "proposedValue", clusterName,
                                "reason", "Page in cluster but missing cluster field" )
                        : null;
                default -> null;
            };
        }
    }

    // -----------------------------------------------------------------------
    //  Staleness Check
    // -----------------------------------------------------------------------

    /**
     * Flags pages that haven't been modified within a configurable window.
     * Drafts use a shorter window ({@code stalledDraftDays}); archived pages
     * are exempt.
     */
    public static class StalenessCheck implements PageCheck {

        private final int stalenessWindowDays;
        private final int stalledDraftDays;
        private final Instant now;

        public StalenessCheck( final int stalenessWindowDays, final int stalledDraftDays ) {
            this( stalenessWindowDays, stalledDraftDays, Instant.now() );
        }

        /** Visible for testing — allows injecting a fixed clock. */
        StalenessCheck( final int stalenessWindowDays, final int stalledDraftDays, final Instant now ) {
            this.stalenessWindowDays = stalenessWindowDays;
            this.stalledDraftDays = stalledDraftDays;
            this.now = now;
        }

        @Override
        public List< PageCheckResult > check( final PageCheckContext ctx ) {
            if ( ctx.page() == null || ctx.page().getLastModified() == null ) {
                return List.of();
            }

            final String status = ctx.metadata().get( "status" ) instanceof String s ? s : null;
            if ( "archived".equals( status ) ) {
                return List.of();
            }

            final long daysSince = ChronoUnit.DAYS.between(
                    ctx.page().getLastModified().toInstant(), now );

            if ( "draft".equals( status ) && daysSince > stalledDraftDays ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.SUGGESTION,
                        "staleness", "stalled_draft",
                        "Draft page not modified in " + daysSince + " days" ) );
            }

            if ( !"draft".equals( status ) && daysSince > stalenessWindowDays ) {
                return List.of( new PageCheckResult( ctx.pageName(), Severity.SUGGESTION,
                        "staleness", "stale",
                        "Page not modified in " + daysSince + " days" ) );
            }

            return List.of();
        }
    }
}
